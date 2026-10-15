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
 * A call site whose outputs fail their schema at a rate its history does not predict, on tool_error's engine
 * ({@link ToolErrorTrend}). Only a rise is a finding. Rebuilt every pass rather than resumed, because a backfill or a
 * newly arriving schema changes hours already tallied; the reset fence keeps a resolve durable.
 */
@Service
public class MalformedOutputRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(MalformedOutputRateService.class);

    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /** Short because every call site is re-evaluated whenever the sweep catches up. */
    public static final Duration QUIET_WINDOW = Duration.ofHours(6);

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
     * State first, findings second: if this dies between the two, the next pass re-derives the same findings from the
     * same state. @return how many call sites are in a spell
     */
    int refresh(String projectId, ClassifierRow signal, String checkedBefore, Instant at) {
        Instant started = Instant.now();
        ToolErrorConfig config = ToolErrorConfig.of(mapper, signal.configJson());
        List<HourlyToolTally> tallies =
                rates.hourlyTallies(projectId, signal.id(), at.minus(REPLAY_WINDOW), checkedBefore);
        if (tallies.isEmpty()) return 0;

        ToolErrorStateRepository states = rates.states();
        Map<String, CarriedState> carried = new HashMap<>();
        for (CarriedState state : states.list(projectId)) carried.put(state.toolKey(), state.rebuilding());
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
                null,
                decision.callsSinceOnset(),
                callSite,
                decision.onsetAt(),
                payload(callSite, decision),
                now,
                at.minus(QUIET_WINDOW).toString(),
                now);
        if (recorded == null) return; // a ruled finding already covers this call site up to now

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
        return mapper.valueToTree(body).toString();
    }
}
