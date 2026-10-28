// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.frustration.FrustrationRateRepository.Trial;
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
import org.springframework.transaction.support.TransactionOperations;

/**
 * Frustration's rate test on tool_error's engine: a conversation on a call site, in each UTC hour it has a scored turn,
 * is a Bernoulli trial that fails when a turn in that hour was flagged and the flag stands. Rebuilt every pass, because
 * a {@code false_alarm} resolve changes an earlier hour. A spell files a finding already ruled positive and opens its case in the same transaction;
 * its evidence is uncapped, since a cap is a sample with an undeclared selection rule.
 */
@Service
public class FrustrationRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(FrustrationRateService.class);

    /** How far back a replay reads, from the newest scored turn: tool_error's window. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /** How long a spell may go unrefreshed before the next firing is a new one: tool_error's window. */
    static final Duration QUIET_WINDOW = Duration.ofHours(6);

    private final FrustrationRateRepository rates;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final CaseOpener caseOpener;
    private final TransactionOperations tx;
    private final ObjectMapper mapper;

    public FrustrationRateService(
            FrustrationRateRepository rates,
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            CaseOpener caseOpener,
            TransactionOperations tx,
            ObjectMapper mapper) {
        this.rates = rates;
        this.findings = findings;
        this.evidence = evidence;
        this.caseOpener = caseOpener;
        this.tx = tx;
        this.mapper = mapper;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.FRUSTRATION);
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
        FrustrationConfig config = FrustrationConfig.of(mapper, signal.configJson());
        String scorerVersion = config.scorerVersion();
        Optional<Instant> newest = rates.newestTurnAt(projectId, signal.id(), scorerVersion);
        if (newest.isEmpty()) return List.of();

        Instant windowFrom = newest.get().minus(REPLAY_WINDOW);
        List<HourlyToolTally> all = rates.hourlyTallies(projectId, signal.id(), scorerVersion, windowFrom);
        List<HourlyToolTally> tallies = all.stream()
                .filter(t -> !FrustrationRateRepository.UNASSIGNED.equals(t.toolKey()))
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
        for (Spell spell : rising) {
            tx.executeWithoutResult(status -> persist(projectId, signal, config, spell, windowFrom, at));
        }

        StructuredLog.info(log, Markers.OPS, "frustration.refresh")
                .message(
                        "replayed %d hourly bucket(s) across %d call site(s) for frustration, %d in a spell",
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
     * One rising call site: the same spell refreshes the finding it already filed, a new spell files one, rules
     * it positive and opens or joins its case. Evidence is kept to one turn per conversation-hour either way.
     */
    private void persist(
            String projectId,
            ClassifierRow signal,
            FrustrationConfig config,
            Spell spell,
            Instant windowFrom,
            Instant at) {
        String callSite = spell.toolKey();
        String now = at.toString();
        String eventAt = spell.lastBucket() != null ? spell.lastBucket() : now;
        String onset = spell.decision().onsetAt();
        Instant since = onset != null ? Instant.parse(onset) : windowFrom;
        // The end of the last hour the replay folded, not now: the trials stop where the spell's counts stop.
        Instant until =
                spell.lastBucket() != null ? Instant.parse(spell.lastBucket()).plus(Duration.ofHours(1)) : at;
        List<Trial> trials = rates.trialsSince(projectId, signal.id(), config.scorerVersion(), callSite, since, until);
        int frustrated = (int) trials.stream().filter(Trial::flagged).count();
        ToolErrorDetector.Decision d = ToolErrorDetector.counted(spell.decision(), frustrated);
        String causeKey = CauseKey.frustration(signal.id(), callSite);
        String payload = FrustrationEvidence.payload(
                mapper, callSite, d, spell.baseline().failures(), config);

        Optional<FindingRow> open = findings.findOpenByCause(projectId, signal.classifierKey(), causeKey);
        String findingId;
        boolean filed = false;
        if (open.isPresent() && sameInstant(open.get().onsetAt(), d.onsetAt())) {
            findingId = open.get().id();
            findings.refreshRuledObservation(projectId, findingId, d.callsSinceOnset(), payload, eventAt, now);
        } else {
            FindingRepository.Recorded recorded = findings.recordRecomputedRate(
                    Ids.ulid(),
                    projectId,
                    signal.classifierKey(),
                    causeKey,
                    FindingRow.Cause.FRUSTRATION_RATE,
                    callSite,
                    FindingRow.SubjectKind.CLASSIFIER,
                    signal.id(),
                    signal.name(),
                    null,
                    d.callsSinceOnset(),
                    callSite,
                    d.onsetAt(),
                    payload,
                    eventAt,
                    Instant.parse(eventAt).minus(QUIET_WINDOW).toString(),
                    now);
            if (recorded == null) {
                // A ruled finding already covers eventAt: a rebuild moved this spell's onset without new
                // hours. The finding still open on the cause is this spell; refresh it rather than fork one.
                if (open.isEmpty()) return;
                findingId = open.get().id();
                findings.refreshRuledObservation(projectId, findingId, d.callsSinceOnset(), payload, eventAt, now);
            } else {
                findingId = recorded.findingId();
                filed = findings.recordTriage(
                                projectId,
                                findingId,
                                FindingRow.TriageVerdict.POSITIVE,
                                FrustrationEvidence.SUMMARY,
                                null,
                                now)
                        == 1;
                caseOpener.ensureCaseFor(projectId, findingId, null);
            }
        }

        // A later turn of an hour, or an earlier flag, moves the turn that stands for that conversation-hour: the
        // rows of the turn it replaced go, or the hour counts twice and on both sides.
        Map<String, List<FindingEvidenceRepository.Ref>> superseded = new HashMap<>();
        for (FrustrationRateRepository.Superseded s :
                rates.superseded(projectId, signal.id(), config.scorerVersion(), callSite, findingId, since, until)) {
            superseded
                    .computeIfAbsent(s.role(), r -> new ArrayList<>())
                    .add(FindingEvidenceRepository.Ref.turn(s.sessionId(), s.traceId()));
        }
        int removed = 0;
        for (Map.Entry<String, List<FindingEvidenceRepository.Ref>> e : superseded.entrySet()) {
            removed += evidence.remove(projectId, findingId, e.getKey(), e.getValue());
        }

        // Each row names its session: the clearer, the retention pin and RCA's session citations read it off the
        // trace row.
        List<FindingEvidenceRepository.Ref> members = new ArrayList<>(trials.size());
        List<FindingEvidenceRepository.Ref> witnesses = new ArrayList<>(frustrated);
        for (Trial t : trials) {
            FindingEvidenceRepository.Ref turn = FindingEvidenceRepository.Ref.turn(t.conversationId(), t.traceId());
            members.add(turn);
            if (t.flagged()) witnesses.add(turn);
        }
        // Rows already stored are skipped by the ref's unique index, so each pass adds only the turns that now stand.
        int stored = evidence.record(projectId, findingId, FindingEvidenceRow.Role.MEMBER, members, now)
                + evidence.record(projectId, findingId, FindingEvidenceRow.Role.WITNESS, witnesses, now);

        StructuredLog.info(log, Markers.OPS, "frustration.spell")
                .message(
                        "%s: %.1f%% of conversation-hours frustrated since %s, against a learned %.1f%%",
                        callSite,
                        d.currentRate() * 100,
                        onset == null ? "the window's start" : onset,
                        d.baselineRate() * 100)
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("callSiteId", callSite)
                .field("finding", findingId)
                .field("opened", filed)
                .field("trialsSinceOnset", d.callsSinceOnset())
                .field("frustratedSinceOnset", d.failuresSinceOnset())
                .field("criticality", d.criticality())
                .field("evidenceStored", stored)
                .field("evidenceRemoved", removed)
                .log();
    }

    /** Onsets compare as instants: {@code Instant#toString} drops a zero fraction, so strings may differ. */
    private static boolean sameInstant(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return a == null && b == null;
        return Instant.parse(a).equals(Instant.parse(b));
    }
}
