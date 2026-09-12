// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.classifier.worker.ClassifierCatchUp;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Malformed Output's findings: a call site whose outputs are failing their declared schema at a rate its own
 * history does not predict.
 *
 * <p><b>tool_error's engine, not a copy of it.</b> An output either parses against its call site's schema or it
 * does not, which is a Bernoulli rate exactly as a tool call either succeeds or fails. So the hourly tallies are
 * replayed through {@link ToolErrorTrend} and {@link ToolErrorConfig}'s tuning, the grouped CUSUM whose run
 * lengths tool_error's eval rig measured, with the call site where a tool would be. What is this classifier's own
 * is only what differs: where the tallies come from ({@link MalformedOutputRateRepository}), the table the carried
 * state lives in, and the finding it writes.
 *
 * <p><b>Why a rate and not a count.</b> A fixed count fires on a busy call site that is fine and stays silent on
 * a quiet one that broke completely. A call site that has never produced a malformed output starts from
 * Jeffreys-smoothed zero, and the shift floor is what makes it watchable: three in a row is enough there.
 *
 * <p><b>Only the rise is a finding.</b> The engine watches both directions. A call site failing its schema less
 * often than it used to is a fix, and nothing a person needs to hear about.
 *
 * <p>Recomputed when the sweep catches up, never mid-backlog; see {@link ClassifierCatchUp}.
 */
@Service
public class MalformedOutputRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(MalformedOutputRateService.class);

    /** How far back a replay reads: tool_error's window, for its reasons. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /**
     * How long a spell may go unrefreshed before the next firing starts a new one. Short, as tool_error's is,
     * because every call site is re-evaluated whenever the sweep catches up, so a recovered one stops refreshing
     * within minutes.
     */
    static final Duration QUIET_WINDOW = Duration.ofHours(6);

    /** Failing spans kept on a finding for a reader to open. */
    static final int MAX_WITNESSES = 50;

    private final MalformedOutputRateRepository rates;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ObjectMapper mapper;

    public MalformedOutputRateService(
            MalformedOutputRateRepository rates,
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ObjectMapper mapper) {
        this.rates = rates;
        this.findings = findings;
        this.evidence = evidence;
        this.mapper = mapper;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.MALFORMED_OUTPUT);
    }

    @Override
    public void caughtUp(ClassifierJobRow job, ClassifierRow signal, @Nullable String checkedBefore) {
        if (checkedBefore == null) return;
        refresh(job.projectId(), signal, checkedBefore, Instant.now());
    }

    /**
     * Replay every call site's tallies from where the last pass left them, carry the state forward, and write a
     * finding for each call site whose rise is alarming now.
     *
     * <p>State first, findings second, as tool_error orders it: if this dies between the two, the next pass
     * re-derives the same findings from the same state.
     *
     * @return how many call sites are in a spell
     */
    int refresh(String projectId, ClassifierRow signal, String checkedBefore, Instant at) {
        Instant started = Instant.now();
        ToolErrorConfig config = ToolErrorConfig.of(mapper, signal.configJson());
        List<HourlyToolTally> tallies =
                rates.hourlyTallies(projectId, signal.id(), at.minus(REPLAY_WINDOW), checkedBefore);
        if (tallies.isEmpty()) return 0;

        ToolErrorStateRepository states = rates.states();
        Map<String, CarriedState> carried = new HashMap<>();
        for (CarriedState state : states.list(projectId)) carried.put(state.toolKey(), rebuildingFrom(state));
        ToolErrorTrend.Sweep sweep = ToolErrorTrend.sweep(tallies, config, Map.of(), carried);
        String now = at.toString();
        for (CarriedState advanced : sweep.advanced()) states.save(projectId, advanced, now);

        int filed = 0;
        for (Spell spell : sweep.spells()) {
            if (spell.decision().direction() != Direction.UP) continue;
            persist(projectId, signal, spell, at);
            filed++;
        }

        StructuredLog.info(log, Markers.OPS, "malformed.refresh")
                .message(
                        "replayed %d hourly bucket(s) across %d call site(s) for malformed output, %d in a spell",
                        tallies.size(), sweep.advanced().size(), filed)
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("buckets", tallies.size())
                .field("callSites", sweep.advanced().size())
                .field("spells", filed)
                .durationMs(started)
                .log();
        return filed;
    }

    /**
     * The carried state with its accumulator and watermark cleared, so the replay rebuilds the whole window
     * against the frozen reference instead of resuming after the last hour it folded.
     *
     * <p><b>Why this classifier rebuilds where tool_error resumes.</b> A resume folds each hour once and then
     * never reads it again, which is sound only if an hour's tally is final when it is first read. Here it is
     * not: a backfill lands one hour's spans across several uploads, and a schema arriving rewinds the sweep to
     * check hours that were already tallied as clean. A resumed accumulator would keep the first, partial count
     * of each such hour for good. Rebuilding every pass reads each hour as it stands, which is tool_error's
     * original no-watermark design (PROGRAM.md §5).
     *
     * <p>What is kept is the reference, learned once and frozen, so a slow degradation cannot drag it along; and
     * the pending-pin and reset columns, which record human decisions. tool_error needed to resume once a human
     * closing a case had to reset the accumulator; when a ruling on a Malformed Output finding can do the same,
     * this must learn to resume too.
     */
    private static CarriedState rebuildingFrom(CarriedState state) {
        return new CarriedState(
                state.toolKey(),
                ToolErrorDetector.State.EMPTY,
                state.baseline(),
                null,
                state.stateEpoch(),
                state.pendingPinBy(),
                state.pendingPinAt());
    }

    private void persist(String projectId, ClassifierRow signal, Spell spell, Instant at) {
        Decision decision = spell.decision();
        String callSite = spell.toolKey();
        String now = at.toString();
        FindingRepository.Recorded recorded = findings.recordRecomputedRate(
                Ids.ulid(),
                projectId,
                signal.classifierKey(),
                CauseKey.malformedOutput(signal.id(), callSite),
                FindingRow.Cause.MALFORMED_RATE,
                callSite,
                FindingRow.SubjectKind.CLASSIFIER,
                signal.id(),
                signal.name(),
                decision.callsSinceOnset(),
                callSite,
                decision.onsetAt(),
                payload(callSite, decision),
                at.minus(QUIET_WINDOW).toString(),
                now);

        // Read once: an accessor called in the guard and again in the branch is two calls that only happen to agree.
        String onset = decision.onsetAt();
        String since = onset != null ? onset : at.minus(REPLAY_WINDOW).toString();
        List<FindingEvidenceRepository.Ref> failures =
                rates.recentFailures(projectId, signal.id(), callSite, since, MAX_WITNESSES);
        int stored = evidence.recordUpTo(
                projectId, recorded.findingId(), FindingEvidenceRow.Role.WITNESS, failures, MAX_WITNESSES, now);

        StructuredLog.info(log, Markers.OPS, "malformed.spell")
                .message(
                        "%s outputs are failing their schema at %.1f%% since %s, against %.2f%% before",
                        callSite, decision.currentRate() * 100, since, decision.baselineRate() * 100)
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("callSiteId", callSite)
                .field("finding", recorded.findingId())
                .field("opened", recorded.created())
                .field("callsSinceOnset", decision.callsSinceOnset())
                .field("failuresSinceOnset", decision.failuresSinceOnset())
                .field("witnessesStored", stored)
                .log();
    }

    /**
     * What a finding says about itself: the reference it was measured against and the run since onset, the same
     * quantities a tool-error finding carries, named for a call site.
     */
    private String payload(String callSite, Decision d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("call_site_id", callSite);
        body.put("direction", "up");
        body.put("baseline_calls", d.baselineCalls());
        body.put("baseline_rate", d.baselineRate());
        body.put("current_rate", d.currentRate());
        body.put("calls_since_onset", d.callsSinceOnset());
        body.put("failures_since_onset", d.failuresSinceOnset());
        body.put("delta_pp", d.deltaPp());
        body.put("effect_size", d.effectSize());
        body.put("statistic", d.statistic());
        body.put("threshold", d.threshold());
        body.put("criticality", d.criticality());
        if (d.onsetAt() != null) body.put("onset_at", d.onsetAt());
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"call_site_id\":\"" + callSite + "\"}";
        }
    }
}
