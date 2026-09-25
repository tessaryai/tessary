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
import ai.tessary.classifier.frustration.FrustrationRateRepository.FrustratedConversation;
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
 * Frustration's rate test: per call site, whether the share of conversations frustrated with the agent has risen
 * above the rate that call site learned as its normal.
 *
 * <p><b>tool_error's engine, not a copy of it</b>, as Malformed Output uses it: a conversation is a Bernoulli
 * trial on the call site of its first scored turn, and it fails when it carries an uncleared frustration flag.
 * The hourly tallies ({@link FrustrationRateRepository}) are replayed through {@link ToolErrorTrend} on
 * {@link FrustrationConfig#engine()}. The reference is learned from the first {@code min_baseline_conversations}
 * and frozen; a call site that is frustrated from its first day learns that as its normal and is flagged only
 * for getting worse.
 *
 * <p><b>Rebuilt every pass</b> ({@link CarriedState#rebuilding}), because a conversation flagged on a later turn
 * becomes a failure in its original hour, and one cleared by a {@code false_alarm} resolve stops being one. The
 * reset fence keeps a resolve durable; a change to the scorer or the tuning is handled as a reset too, with the
 * note {@value #TUNING_CHANGED}, because a reference learned under other weights is not comparable.
 *
 * <p><b>A spell opens a finding and a case without triage.</b> Each rising call site files one finding per spell,
 * ruled positive at filing with {@link FrustrationEvidence#SUMMARY}, and opens or joins its case in the same
 * transaction, the path a high-confidence secret leak takes. A later pass over the same spell (same onset)
 * refreshes that finding's numbers and its evidence instead of filing again.
 *
 * <p><b>The evidence is the rate's two sides, enumerated as tool_error enumerates its calls</b>: every session
 * scored on the call site since onset as {@code member}, and every frustrated one as {@code witness}, a session
 * row followed by a trace row for the turn that fired. Neither is capped, since a cap is a sample with an
 * undeclared selection rule, and both are written on every pass up to the end of the spell's last hour, so they
 * grow with the spell and match its counts. A false-alarm resolve clears the witnesses, and RCA reads them.
 *
 * <p>Only a rise is reported. Runs when the TURN sweep reaches the head of the stream, never mid-page, so the
 * assessment table is complete up to the cursor whenever this reads it. It reads tables, not the provider, so a
 * paused classifier still replays.
 */
@Service
public class FrustrationRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(FrustrationRateService.class);

    /** How far back a replay reads, from the newest scored turn: tool_error's window. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /** The reset note written when the scorer or the tuning changed under a call site's state. */
    static final String TUNING_CHANGED = "tuning changed";

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
     * Replay every call site's tallies over the window, save each call site's state, file or refresh a finding
     * for each call site whose frustrated-conversation rate has risen and is alarming now, and return those.
     *
     * <p>State first, findings second, as tool_error orders it: if this dies between the two, the next pass
     * re-derives the same findings from the same state.
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
     * One rising call site: the same spell refreshes the finding it already filed, a new spell files one, rules
     * it positive and opens or joins its case. Evidence is topped up either way.
     */
    private void persist(
            String projectId,
            ClassifierRow signal,
            FrustrationConfig config,
            Spell spell,
            Instant windowFrom,
            Instant at) {
        ToolErrorDetector.Decision d = spell.decision();
        String callSite = spell.toolKey();
        String now = at.toString();
        String eventAt = spell.lastBucket() != null ? spell.lastBucket() : now;
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

        String onset = d.onsetAt();
        Instant since = onset != null ? Instant.parse(onset) : windowFrom;
        // The end of the last hour the replay folded, not now: the sessions stop where the spell's counts stop.
        Instant until =
                spell.lastBucket() != null ? Instant.parse(spell.lastBucket()).plus(Duration.ofHours(1)) : at;
        List<FindingEvidenceRepository.Ref> members = new ArrayList<>();
        for (String session :
                rates.scoredSince(projectId, signal.id(), config.scorerVersion(), callSite, windowFrom, since, until)) {
            members.add(FindingEvidenceRepository.Ref.session(session));
        }
        List<FrustratedConversation> frustrated = rates.frustratedSince(
                projectId, signal.id(), config.scorerVersion(), callSite, windowFrom, since, until);
        List<FindingEvidenceRepository.Ref> witnesses = new ArrayList<>(frustrated.size() * 2);
        for (FrustratedConversation c : frustrated) {
            witnesses.add(FindingEvidenceRepository.Ref.session(c.conversationId()));
            witnesses.add(FindingEvidenceRepository.Ref.trace(c.flaggedTraceId()));
        }
        // Rows already stored are skipped by the ref's unique index, so each pass adds only what is new.
        int stored = evidence.record(projectId, findingId, FindingEvidenceRow.Role.MEMBER, members, now)
                + evidence.record(projectId, findingId, FindingEvidenceRow.Role.WITNESS, witnesses, now);

        StructuredLog.info(log, Markers.OPS, "frustration.spell")
                .message(
                        "%s: %.1f%% of sessions frustrated since %s, against a learned %.1f%%",
                        callSite,
                        d.currentRate() * 100,
                        onset == null ? "the window's start" : onset,
                        d.baselineRate() * 100)
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("callSiteId", callSite)
                .field("finding", findingId)
                .field("opened", filed)
                .field("sessionsSinceOnset", d.callsSinceOnset())
                .field("frustratedSinceOnset", d.failuresSinceOnset())
                .field("criticality", d.criticality())
                .field("evidenceStored", stored)
                .log();
    }

    /** Onsets compare as instants: {@code Instant#toString} drops a zero fraction, so strings may differ. */
    private static boolean sameInstant(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return a == null && b == null;
        return Instant.parse(a).equals(Instant.parse(b));
    }
}
