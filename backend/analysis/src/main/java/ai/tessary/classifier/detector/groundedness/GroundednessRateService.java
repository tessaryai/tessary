// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.FlaggedAnswer;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Groundedness's rate test: per call site, whether the share of traces with a flagged answer has risen above the
 * rate that call site learned as its normal.
 *
 * <p><b>tool_error's engine, not a copy of it</b>, as Frustration uses it: a trace is a Bernoulli trial on each
 * call site it had an answer scored on, and it fails when one of those answers carries an uncleared flag. The
 * hourly tallies ({@link GroundednessRateRepository}) are replayed through {@link ToolErrorTrend} on {@link
 * GroundednessConfig#engine()}. Judging starts once the reference holds {@code min_baseline_traces}, and the
 * reference keeps learning each later hour until it holds {@code freeze_baseline_traces}, then stops moving. The
 * model's false-alarm rate depends on the domain, so a learned per-call-site reference is what absorbs it; a
 * call site that answers badly from its first day learns that as its normal and is flagged only for getting
 * worse.
 *
 * <p><b>Rebuilt every pass</b> ({@link CarriedState#rebuilding}), because a trace scored across several uploads
 * lands in its original hour, and one cleared by a {@code false_alarm} resolve stops being a failure. The reset
 * fence keeps a resolve durable; a change to the scorer or the tuning is handled as a reset too, with the note
 * {@value #TUNING_CHANGED}, because a reference learned under another threshold is not comparable.
 *
 * <p><b>A spell files a finding for triage</b>, the Malformed Output pattern and not Frustration's: nothing is
 * ruled at filing and no case is opened here. Triage rules on the finding, and a positive opens the case. A later
 * pass over the same spell refreshes the open finding's numbers and evidence; a spell a ruling already covers is
 * left alone.
 *
 * <p><b>The evidence is the rate's two sides</b>: every trace scored on the call site since onset as {@code
 * member}, and every flagged one as {@code witness}, a trace row followed by a span row for each answer that was
 * flagged in it. Neither is capped, and both are written up to the end of the spell's last hour, so they match
 * its counts. A false-alarm resolve clears the witnesses' flags.
 *
 * <p>Only a rise is reported. Runs when the sweep reaches the head of the stream, never mid-page, so the
 * assessment table is complete up to the cursor whenever this reads it.
 */
@Service
public class GroundednessRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(GroundednessRateService.class);

    /** How far back a replay reads, from the newest scored answer: tool_error's window. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /** The reset note written when the scorer or the tuning changed under a call site's state. */
    static final String TUNING_CHANGED = "tuning changed";

    /** How long a spell may go unrefreshed before the next firing is a new one: tool_error's window. */
    static final Duration QUIET_WINDOW = Duration.ofHours(6);

    private final GroundednessRateRepository rates;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ObjectMapper mapper;

    public GroundednessRateService(
            GroundednessRateRepository rates,
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
        return Set.of(BuiltInDetector.Kind.GROUNDEDNESS);
    }

    @Override
    public void caughtUp(ClassifierJobRow job, ClassifierRow signal, @Nullable String checkedBefore) {
        refresh(job.projectId(), signal, Instant.now());
    }

    /**
     * Replay every call site's tallies over the window, save each call site's state, and file or refresh a
     * finding for each call site whose flagged-trace rate has risen and is alarming now. Returns those spells.
     *
     * <p>State first, findings second, as tool_error orders it: if this dies between the two, the next pass
     * re-derives the same findings from the same state.
     */
    List<Spell> refresh(String projectId, ClassifierRow signal, Instant at) {
        Instant started = Instant.now();
        GroundednessConfig config = GroundednessConfig.of(mapper, signal.configJson());
        String scorerVersion = config.scorerVersion();
        Optional<Instant> newest = rates.newestObservationAt(projectId, signal.id(), scorerVersion);
        if (newest.isEmpty()) return List.of();

        Instant windowFrom = newest.get().minus(REPLAY_WINDOW);
        List<HourlyToolTally> all = rates.hourlyTallies(projectId, signal.id(), scorerVersion, windowFrom);
        List<HourlyToolTally> tallies = all.stream()
                .filter(t -> !GroundednessRateRepository.UNASSIGNED.equals(t.toolKey()))
                .toList();

        String now = at.toString();
        String epoch = config.stateEpoch();
        ToolErrorStateRepository states = rates.states();
        Map<String, CarriedState> carried = new HashMap<>();
        int retuned = 0;
        for (CarriedState state : states.list(projectId)) {
            if (!epoch.equals(state.stateEpoch())) {
                carried.put(state.toolKey(), retune(projectId, state, epoch, now));
                retuned++;
            } else {
                carried.put(state.toolKey(), state.rebuilding());
            }
        }

        ToolErrorTrend.Sweep sweep =
                ToolErrorTrend.sweep(tallies, config.engine(), Map.of(), carried, config.schemaVersion());
        for (CarriedState advanced : sweep.advanced()) states.save(projectId, advanced, now);

        List<Spell> rising = sweep.spells().stream()
                .filter(s -> s.decision().direction() == Direction.UP)
                .toList();
        for (Spell spell : rising) persist(projectId, signal, config, spell, windowFrom, at);

        StructuredLog.info(log, Markers.OPS, "groundedness.refresh")
                .message(
                        "replayed %d hourly bucket(s) across %d call site(s) for groundedness, %d in a spell",
                        all.size(), sweep.advanced().size(), rising.size())
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("buckets", all.size())
                .field("callSites", sweep.advanced().size())
                .field("spells", rising.size())
                .field("retuned", retuned)
                .durationMs(started)
                .log();
        return rising;
    }

    /**
     * A state row built under another scorer or other tuning: reset it and re-learn, recorded with its note, and
     * stamp the new epoch so the next pass does not reset it again while it is still learning.
     */
    private CarriedState retune(String projectId, CarriedState stale, String epoch, String now) {
        ToolErrorStateRepository states = rates.states();
        states.resetAndRelearn(projectId, stale.toolKey(), null, TUNING_CHANGED, now);
        CarriedState fresh = new CarriedState(
                stale.toolKey(),
                ToolErrorDetector.State.EMPTY,
                null,
                null,
                epoch,
                stale.pendingPinBy(),
                stale.pendingPinAt(),
                now);
        states.save(projectId, fresh, now);
        return fresh;
    }

    /**
     * One rising call site: file its finding, or refresh the unruled one already open on it, and top up the
     * evidence. Nothing when a ruled finding already covers the spell's last hour.
     */
    private void persist(
            String projectId,
            ClassifierRow signal,
            GroundednessConfig config,
            Spell spell,
            Instant windowFrom,
            Instant at) {
        String callSite = spell.toolKey();
        String now = at.toString();
        String eventAt = spell.lastBucket() != null ? spell.lastBucket() : now;
        // Read once: an accessor called in the guard and again in the branch is two calls that only happen to agree.
        String onset = spell.decision().onsetAt();
        Instant since = onset != null ? parse(onset, windowFrom) : windowFrom;
        // The end of the last hour the replay folded, not now: the traces stop where the spell's counts stop.
        Instant until = spell.lastBucket() != null ? parse(spell.lastBucket(), at).plus(Duration.ofHours(1)) : at;
        String scorerVersion = config.scorerVersion();
        List<String> scored =
                rates.scoredSince(projectId, signal.id(), scorerVersion, callSite, windowFrom, since, until);
        List<FlaggedAnswer> flagged =
                rates.flaggedSince(projectId, signal.id(), scorerVersion, callSite, windowFrom, since, until);
        long flaggedTraces =
                flagged.stream().map(FlaggedAnswer::traceId).distinct().count();
        ToolErrorDetector.Decision d = counted(spell.decision(), flaggedTraces);

        FindingRepository.Recorded recorded = findings.recordRecomputedRate(
                Ids.ulid(),
                projectId,
                signal.classifierKey(),
                CauseKey.groundedness(signal.id(), callSite),
                FindingRow.Cause.GROUNDEDNESS_RATE,
                callSite,
                FindingRow.SubjectKind.CLASSIFIER,
                signal.id(),
                signal.name(),
                // The flagged traces, not every trace: what the auto-escalation floor weighs is how many
                // answers triage has to read, and the denominator is on the payload.
                d.failuresSinceOnset(),
                callSite,
                d.onsetAt(),
                GroundednessEvidence.payload(mapper, callSite, d, spell.baseline().failures(), config),
                eventAt,
                parse(eventAt, at).minus(QUIET_WINDOW).toString(),
                now);
        if (recorded == null) return; // a ruled finding already covers this spell up to its last hour

        List<FindingEvidenceRepository.Ref> members = new ArrayList<>();
        for (String trace : scored) members.add(FindingEvidenceRepository.Ref.trace(trace));
        List<FindingEvidenceRepository.Ref> witnesses = new ArrayList<>(flagged.size() * 2);
        for (FlaggedAnswer answer : flagged) {
            witnesses.add(FindingEvidenceRepository.Ref.trace(answer.traceId()));
            witnesses.add(FindingEvidenceRepository.Ref.span(answer.traceId(), answer.spanId()));
        }
        // Rows already stored are skipped by the ref's unique index, so each pass adds only what is new.
        int stored = evidence.record(projectId, recorded.findingId(), FindingEvidenceRow.Role.MEMBER, members, now)
                + evidence.record(projectId, recorded.findingId(), FindingEvidenceRow.Role.WITNESS, witnesses, now);

        StructuredLog.info(log, Markers.OPS, "groundedness.spell")
                .message(
                        "%s: %.1f%% of traces had a flagged answer since %s, against a learned %.1f%%",
                        callSite,
                        d.currentRate() * 100,
                        onset == null ? "the window's start" : onset,
                        d.baselineRate() * 100)
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("callSiteId", callSite)
                .field("finding", recorded.findingId())
                .field("opened", recorded.created())
                .field("tracesSinceOnset", d.callsSinceOnset())
                .field("flaggedSinceOnset", d.failuresSinceOnset())
                .field("criticality", d.criticality())
                .field("evidenceStored", stored)
                .log();
    }

    /**
     * {@code d} with its flagged traces counted rather than recovered. The engine derives a run's failures
     * from its accumulator ({@link ToolErrorDetector#failuresFromS}), which is exact only when every hour of the
     * run was judged against one reference. This reference keeps learning while it judges, so a run that began
     * before it froze was judged against several, and the derived count is off; the witnesses are the count.
     * The statistic, the threshold and the onset are the engine's own.
     */
    static ToolErrorDetector.Decision counted(ToolErrorDetector.Decision d, long flaggedTraces) {
        long calls = d.callsSinceOnset();
        long failures = Math.max(0, Math.min(calls, flaggedTraces));
        double current = calls == 0 ? d.baselineRate() : (double) failures / calls;
        return new ToolErrorDetector.Decision(
                d.fired(),
                d.direction(),
                d.statistic(),
                d.threshold(),
                d.criticality(),
                d.baselineRate(),
                current,
                (current - d.baselineRate()) * 100.0,
                ToolErrorDetector.cohensH(d.baselineRate(), current),
                calls,
                failures,
                d.baselineCalls(),
                d.onsetAt(),
                d.silence());
    }

    private static Instant parse(String instant, Instant fallback) {
        try {
            return Instant.parse(instant);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
