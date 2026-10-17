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
 * Groundedness's rate test on tool_error's engine: a trace is a Bernoulli trial on each call site it had an answer
 * scored on. The model's false-alarm rate depends on the domain, so a learned per-call-site reference absorbs it. A
 * spell files a finding for triage; no case is opened here. Evidence is uncapped.
 */
@Service
public class GroundednessRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(GroundednessRateService.class);

    /** How far back a replay reads, from the newest scored answer: tool_error's window. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

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
     * State first, findings second: if this dies between the two, the next pass re-derives the same findings from the
     * same state.
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
                carried.put(state.toolKey(), ToolErrorStateRepository.retune(states, projectId, state, epoch, now));
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

    /** Nothing when a ruled finding already covers the spell's last hour. */
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
        Instant since = onset != null ? Instant.parse(onset) : windowFrom;
        // The end of the last hour the replay folded, not now: the traces stop where the spell's counts stop.
        Instant until =
                spell.lastBucket() != null ? Instant.parse(spell.lastBucket()).plus(Duration.ofHours(1)) : at;
        String scorerVersion = config.scorerVersion();
        List<String> scored =
                rates.scoredSince(projectId, signal.id(), scorerVersion, callSite, windowFrom, since, until);
        List<FlaggedAnswer> flagged =
                rates.flaggedSince(projectId, signal.id(), scorerVersion, callSite, windowFrom, since, until);
        long flaggedTraces =
                flagged.stream().map(FlaggedAnswer::traceId).distinct().count();
        ToolErrorDetector.Decision d = ToolErrorDetector.counted(spell.decision(), flaggedTraces);

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
                null,
                // The flagged traces, not every trace: what the auto-escalation floor weighs is how many
                // answers triage has to read, and the denominator is on the payload.
                d.failuresSinceOnset(),
                callSite,
                d.onsetAt(),
                GroundednessEvidence.payload(
                        mapper, callSite, d, spell.baseline().failures(), config),
                eventAt,
                Instant.parse(eventAt).minus(QUIET_WINDOW).toString(),
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
}
