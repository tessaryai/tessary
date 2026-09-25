// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorRepository.RawFailure;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Recomputes a project's tool-error findings and writes them down. Design contract:
 * {@code devdocs/concepts/tool-error.md} §5 and §6.
 *
 * <p>One pass is: read the hourly aggregate, replay it, and upsert one finding per tool currently in a
 * spell. There is no cursor, no watermark and no transaction spanning passes, because nothing carries
 * over between them, the numbers are re-derived from source every time, so running this twice on
 * unchanged traffic leaves the same rows it left the first time.
 *
 * <p><b>The write is the only place this design can still go wrong</b>, and §5.1 names how: assign
 * counts rather than adding them, refresh observations but never judgements, and write the onset once.
 * The first two are properties of
 * {@link FindingRepository#recordRecomputedCause}; the third is why this passes the spell's own
 * onset rather than {@code now}.
 */
@Service
public class ToolErrorService {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorService.class);

    /**
     * How far back a replay reads. Long enough to hold a reference plus a spell, short enough that the
     * aggregate stays a cheap read; the same 28 days {@code TrendService} replays for grader pass rate.
     *
     * <p>tool-error.md §5.3 records the limit this imposes. Anchored to the project's newest tool-call
     * event ({@link ToolErrorRepository#newestEventAt}), not to wall-clock now: a backfill whose traffic
     * is all months old still gets a window that contains it, where {@code now - 28d} would read nothing
     * but the empty months since.
     */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /**
     * How long a confirmed shift may go unrefreshed, in event time, before the next firing starts a new
     * spell rather than continuing this one — the gap {@link FindingRepository#recordRecomputedCause}
     * reads against {@code last_seen_at} to decide.
     *
     * <p>Short, and it can be, unlike metric drift, which must wait for a bucket's next window to close
     * and so derives its horizon from {@code window_max_hours}: this detector re-evaluates every tool on
     * every pass, so a tool that has recovered stops being refreshed as soon as the sweep next runs.
     *
     * <p>Measured from the spell's own last folded hour, never from wall clock: a backfill's gap between
     * two spells is a gap in the traffic, not in when somebody happened to run a sweep.
     */
    public static final Duration QUIET_WINDOW = Duration.ofHours(6);

    /** Failing calls read per alarming tool for signature grouping. Bounded; the tail folds. */
    static final int SIGNATURE_SAMPLE = 2000;

    /**
     * Failing traces named on a finding, beyond the one that becomes its exemplar. Enough for a reader
     * (or Layer 2) to open several instances of the failure and see whether they are the same thing;
     * bounded because the evidence blob is rendered whole.
     */
    static final int WITNESS_TRACES = 5;

    private final ToolErrorRepository repo;
    private final ToolErrorReferenceRepository references;
    private final ToolErrorStateRepository states;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidenceRefs;
    private final ClassifierRepository classifiers;
    private final ObjectMapper mapper;

    public ToolErrorService(
            ToolErrorRepository repo,
            ToolErrorReferenceRepository references,
            ToolErrorStateRepository states,
            FindingRepository findings,
            FindingEvidenceRepository evidenceRefs,
            ClassifierRepository classifiers,
            ObjectMapper mapper) {
        this.repo = repo;
        this.references = references;
        this.states = states;
        this.findings = findings;
        this.evidenceRefs = evidenceRefs;
        this.classifiers = classifiers;
        this.mapper = mapper;
    }

    /** The project's {@code tool_error} definition, or empty when it is not seeded or not enabled. */
    Optional<ClassifierRow> definition(String projectId) {
        return classifiers.listByProject(projectId).stream()
                .filter(c -> BuiltInDetector.Kind.TOOL_ERROR.equals(c.detector()))
                .filter(ClassifierRow::enabled)
                .findFirst();
    }

    /**
     * Recompute every tool's spell and persist the findings, returning how many are live.
     *
     * <p>Idempotent by construction. The one thing it must not do is write when the classifier is off:
     * a disabled classifier that kept refreshing findings would keep its cases alive.
     */
    public int refresh(String projectId) {
        Optional<ClassifierRow> signal = definition(projectId);
        if (signal.isEmpty()) return 0;
        ToolErrorConfig config = ToolErrorConfig.of(mapper, signal.get().configJson());

        // The replay anchors to the project's own traffic, not to wall-clock now — see REPLAY_WINDOW.
        // Empty means no tool-call traffic has ever reached this project, the same "nothing to do" the
        // old empty-tallies check caught, just before paying for the tallies query.
        Optional<Instant> anchor = repo.newestEventAt(projectId);
        if (anchor.isEmpty()) return 0;
        Instant from = anchor.get().minus(REPLAY_WINDOW);
        List<HourlyToolTally> tallies = repo.hourlyTallies(projectId, from);
        if (tallies.isEmpty()) return 0;
        // Read beside the tallies, not derived from a spell's bucket key: the key is a normalization of the
        // raw name and cannot be turned back into one. See ToolErrorRepository#namesByToolKey.
        Map<String, List<String>> namesByToolKey = repo.namesByToolKey(projectId, from);

        Map<String, CarriedState> carriedByTool = states.byTool(projectId);
        // Tools a human has already absorbed, whose reference is only waiting for enough of the new rate
        // to measure itself from. Read BEFORE the sweep advances anything, because that is the state the
        // ruling was made against.
        Set<String> awaitingPin = carriedByTool.values().stream()
                .filter(CarriedState::hasPendingPin)
                .map(CarriedState::toolKey)
                .collect(Collectors.toCollection(HashSet::new));

        ToolErrorTrend.Sweep sweep = ToolErrorTrend.sweep(tallies, config, references.byTool(projectId), carriedByTool);
        // Wall clock, for bookkeeping only: state saves, pins and the finding's own created_at/updated_at.
        // last_seen_at and the quiet test are the spell's event clock instead — see persist().
        String now = Instant.now().toString();

        // State first, findings second. If this call dies between the two, the next pass re-derives the
        // same findings from the same state and writes them; the other order would advance the watermark
        // past evidence whose finding was never recorded, and that evidence is not read again.
        Set<String> absorbed = new HashSet<>();
        for (CarriedState advanced : sweep.advanced()) {
            states.save(projectId, advanced, now);
            if (installPendingPin(projectId, advanced, config, now)) absorbed.add(advanced.toolKey());
        }
        // A tool whose absorb has been ruled on is not written, whether the pin landed on this pass or is
        // still waiting for the run to thicken. Both are the same fact, a human has already accepted this
        // rate, and writing the spell hands them back the finding they accepted.
        //
        // The pending arm is not belt-and-braces. A person's absorb closes the finding, and
        // `ux_finding_live` only covers an unruled `open` row, so the ON CONFLICT in
        // `recordRecomputedCause` does not see the closed row: every pass through the deferral window
        // INSERTS A SECOND, FRESH, OPEN finding for a cause the human has settled. Nothing closes it, and
        // the absorb reads as having done nothing. That window used to be the width of one Triage page
        // load, because this was a read path that recomputed; it is now however long the tool takes to
        // reach `minBaselineCalls`, with `ToolErrorSweep` running the whole time.
        List<Spell> spells = sweep.spells().stream()
                .filter(s -> !absorbed.contains(s.toolKey()) && !awaitingPin.contains(s.toolKey()))
                .toList();
        for (Spell spell : spells) {
            persist(projectId, spell, config, from, now, namesByToolKey.getOrDefault(spell.toolKey(), List.of()));
        }

        StructuredLog.info(log, Markers.OPS, "toolerror.refresh")
                .field("project", projectId)
                .field("buckets", tallies.size())
                .field("spells", spells.size())
                // Calls that carried no start time and so could not be placed on the event clock. Zero on
                // healthy ingest; a number that climbs is a producer that stopped stamping its spans, and
                // the only symptom otherwise would be detection getting slower for no visible reason.
                .field("skippedNoStartedAt", repo.skippedNoStartedAt(projectId, from))
                .log();
        return spells.size();
    }

    /**
     * Fold a negatively-ruled finding's window into the tool's reference, and clear the arm it fired on.
     *
     * <p><b>What a negative means.</b> Triage ruled the claim does not hold: the rows do not carry what
     * the detector asserted, so the traffic it fired on was ordinary. Closing the finding does not by
     * itself fix that: the accumulator still sits above its own threshold, so the very next sweep opens a
     * FRESH finding for a cause a human just dismissed, off nothing new. Folding the window into the
     * reference is what actually resets the arm.
     *
     * <p><b>Why the window folds into the reference rather than replacing it.</b> Absorb REPLACES: a
     * human pressing "legitimate" is saying this run is the normal, and the run is the whole of it. A
     * negative is a weaker statement, nobody said the old normal was wrong, only that this stretch was
     * not the departure from it the detector claimed, so its counts are ADDED to what was already
     * there. On the websearch finding that prompted this, 503 calls / 0 failures plus a judged 336 / 5
     * gives 839 / 5, moving the expected rate from 0.10% to 0.66%. The same burst then reads as
     * ordinary; a worse one still fires.
     *
     * <p><b>Pinning is also what stops the re-read.</b> An accepted reference makes the replay resume
     * after {@code acceptedAt} (see {@code ToolErrorTrend}), so the judged window is not folded a second
     * time on the next sweep. That is the same mechanism absorb relies on, and it is why this writes a
     * reference rather than editing the state's own baseline columns.
     *
     * <p><b>Only on a negative, and this is the whole of the gate.</b> A {@code positive} opens a case:
     * the regression is real, and moving the bar to accommodate it would be the platform quietly
     * agreeing to a rate a human is about to be asked about.
     *
     * <p>Never throws. A ruling that is already recorded must not be undone by the state write that
     * follows it, so every failure here is a log line and a return.
     */
    public void foldRuledNegative(
            String projectId, String toolKey, String findingId, @Nullable String payloadJson, String now) {
        ToolErrorEvidence.Read read = ToolErrorEvidence.read(payloadJson);
        if (read == null) {
            StructuredLog.warn(log, Markers.OPS, "toolerror.fold.unreadable-payload")
                    .field("project", projectId)
                    .field("tool", toolKey)
                    .field("finding", findingId)
                    .log();
            return;
        }
        CarriedState carried = states.byTool(projectId).get(toolKey);
        if (carried == null) {
            StructuredLog.warn(log, Markers.OPS, "toolerror.fold.no-state")
                    .field("project", projectId)
                    .field("tool", toolKey)
                    .field("finding", findingId)
                    .log();
            return;
        }
        ToolErrorRate baseline = carried.baseline();

        // A blob written before the onset rework counts nCur over the tool's WHOLE history, baseline
        // included, so adding it to the baseline counts that history twice and moves the bar to a number
        // nothing measured. The arm still clears, the ruling stands either way, but the reference is
        // left alone, which is the same refusal absorption makes on these blobs.
        if (!read.countsAreOnsetRun() || baseline == null) {
            states.reset(projectId, toolKey, null, foldNote(findingId, "arm cleared; counts not fold-safe"), now);
            StructuredLog.info(log, Markers.OPS, "toolerror.fold.arm-only")
                    .field("project", projectId)
                    .field("tool", toolKey)
                    .field("finding", findingId)
                    .field("reason", baseline == null ? "no baseline" : "pre-onset counts")
                    .log();
            return;
        }

        long calls = baseline.calls() + read.nCur();
        long failures = baseline.failures() + read.failuresCur();
        references.pin(projectId, toolKey, calls, failures, "triage:" + findingId, now);
        states.reset(projectId, toolKey, null, foldNote(findingId, "window folded into the reference"), now);
        StructuredLog.info(log, Markers.OPS, "toolerror.fold.closed")
                .field("project", projectId)
                .field("tool", toolKey)
                .field("finding", findingId)
                .field("calls", calls)
                .field("failures", failures)
                .log();
    }

    /** The sentence somebody reads six weeks later when this tool alarms again. */
    private static String foldNote(String findingId, String what) {
        return "Triage closed finding " + findingId + " — " + what;
    }

    /**
     * Honour an absorb that arrived before there was enough of the new rate to measure.
     *
     * <p>A human pressed "legitimate, absorb" while the run since onset was a dozen calls long. Pinning
     * then would have made a burst the tool's normal, so the decision waited here. The moment the run
     * holds {@code minBaselineCalls}, those calls ARE the new normal and the reference installs itself.
     *
     * <p>Pins from the up arm's run only. Down-arm spells are improvements, and a human absorbing one is
     * saying the lower rate is the new normal, which is the same arithmetic, but the run to measure is
     * still the one that alarmed, and the up arm is what the finding was written about.
     */
    private boolean installPendingPin(String projectId, CarriedState carried, ToolErrorConfig config, String now) {
        if (!carried.hasPendingPin()) return false;
        long calls = carried.state().callsSinceOnsetUp();
        if (calls < config.minBaselineCalls()) return false;

        ToolErrorRate baseline = carried.baseline();
        if (baseline == null) return false; // cannot describe the run without the reference it was scored against
        double p0 = ToolErrorDetector.baselineRate(baseline);
        long failures = ToolErrorDetector.failuresFromS(
                carried.state().sUp(), calls, p0, ToolErrorDetector.shiftedUp(p0, config));

        references.pin(projectId, carried.toolKey(), calls, failures, carried.pendingPinBy(), now);
        states.clearPendingPin(projectId, carried.toolKey(), now);
        states.reset(
                projectId,
                carried.toolKey(),
                carried.pendingPinBy(),
                "Absorbed once the run reached " + calls + " calls",
                now);
        StructuredLog.info(log, Markers.OPS, "toolerror.pin.deferred")
                .field("project", projectId)
                .field("tool", carried.toolKey())
                .field("calls", calls)
                .field("failures", failures)
                .log();
        return true;
    }

    private void persist(
            String projectId, Spell spell, ToolErrorConfig config, Instant from, String now, List<String> toolNames) {
        // The finding's own event clock: the hour the detector last folded into this spell, not the
        // moment this sweep happened to run. last_seen_at, the quiet test and the evidence window all
        // measure from here, so a backfill replays on the traffic's timeline instead of the sweep's. A
        // firing spell always carries one — see Spell#lastBucket.
        String eventAt = Objects.requireNonNull(spell.lastBucket());
        Instant until = Instant.parse(eventAt).plus(Duration.ofHours(1));
        String quietBefore = Instant.parse(eventAt).minus(QUIET_WINDOW).toString();

        Failures failing = patternsFor(projectId, spell, config, until, toolNames);
        String evidence = ToolErrorEvidence.toJson(
                spell.toolKey(),
                spell.decision(),
                failing.patterns(),
                failing.patterns().size() >= config.maxPatterns(),
                failing.traceIds(),
                from.toString(),
                until.toString());
        var recorded = findings.recordRecomputedCause(
                Ids.ulid(),
                projectId,
                ToolErrorEvidence.causeKey(spell.toolKey(), spell.decision()),
                spell.decision().callsSinceOnset(),
                ai.tessary.classifier.substrate.BehaviorSubstrateRepository.UNATTRIBUTED,
                spell.onsetBucket(),
                evidence,
                eventAt,
                quietBefore,
                now);
        if (recorded == null) return; // a ruled finding already covers this spell up to eventAt
        recordPopulation(projectId, recorded.findingId(), spell, toolNames, until, now);
    }

    /**
     * Enumerate the spell as evidence refs: every call since onset as {@code member}, and the failing
     * subset of them as {@code witness}.
     *
     * <p><b>The two are the rate's denominator and numerator.</b> A reader handed only the failures
     * cannot check what they were a fraction OF, and one handed only the population cannot tell which
     * of it failed, {@code finding_evidence} carries no outcome column, so the role is what says so.
     * Both are enumerated in full: a cap here would be a sample with an undeclared selection rule.
     *
     * <p><b>No baseline.</b> A CUSUM has one reference and it is a fitted rate, not a window of rows
     * (tool-error.md §4.6). Enumerating the traffic before onset would assert a two-window comparison this
     * detector never made.
     *
     * <p><b>No exemplar.</b> Naming one trace as the entry point biases the run that reads it; the
     * whole failing population is enumerated here and Layer 2 chooses its own way in.
     *
     * <p>Written on every recompute rather than only at open, because the spell's window grows with it
     * and a denominator frozen at open would stop matching the numerator. The unique index makes the
     * overlap a no-op.
     *
     * @param until the end of the last hour the detector folded — the spell's own event clock, not the
     *     moment this method runs. Bounding the population there, rather than at wall-clock now, is what
     *     keeps {@code member}/{@code witness} matching {@code toolError.nCur}: both stop at the same
     *     hour instead of one reading a few calls later than the other.
     */
    private void recordPopulation(
            String projectId, String findingId, Spell spell, List<String> toolNames, Instant until, String now) {
        // An alarm implies an arm above zero, which implies a bracketed onset.
        Instant onset = Instant.parse(Objects.requireNonNull(spell.onsetBucket()));
        record(
                projectId,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                repo.callRefsFor(projectId, toolNames, onset, until),
                now);
        record(
                projectId,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                repo.failingCallRefsFor(projectId, toolNames, onset, until),
                now);
    }

    private void record(
            String projectId, String findingId, String role, List<ToolErrorRepository.SpanRef> calls, String now) {
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>(calls.size());
        for (var call : calls) {
            refs.add(FindingEvidenceRepository.Ref.span(call.traceId(), call.spanId()));
        }
        evidenceRefs.record(projectId, findingId, role, refs, now);
    }

    /**
     * What one alarming tool's failing calls were: the ranked patterns, and the traces they happened in.
     *
     * @param traceIds up to {@link #WITNESS_TRACES} distinct failing traces, for the dossier blob's
     *     reading aid. The finding's own evidence enumerates the failing calls in full and at span
     *     grain, this is a preview, not the claim
     */
    private record Failures(List<ToolErrorRate.Pattern> patterns, List<String> traceIds) {}

    /**
     * The failure patterns behind one alarming tool, largest first, and the traces to point at.
     *
     * <p>Read only for a tool that has already alarmed, that is what makes inspecting the result payload
     * affordable at all, and only over the observed side, since the reference side's raw rows are not
     * what a reader is asking about when a rate has moved.
     *
     * <p>{@code toolNames} are the bucket's RAW names, carried down from the same read that produced the
     * tallies. They are not recoverable from {@code spell.toolKey()}, which is a lossy normalization of
     * them, and a caller that tries anyway gets an empty result rather than an error.
     *
     * @param until the end of the last hour the detector folded, the same bound {@link #recordPopulation}
     *     enumerates evidence to — never wall-clock now, or a backfilled replay would read patterns from
     *     traffic outside the spell it is describing.
     */
    private Failures patternsFor(
            String projectId, Spell spell, ToolErrorConfig config, Instant until, List<String> toolNames) {
        Instant since = Instant.parse(Objects.requireNonNull(spell.onsetBucket()));
        ToolErrorRate observed = new ToolErrorRate();
        List<String> traces = new ArrayList<>();
        for (RawFailure f : repo.failuresFor(projectId, toolNames, since, until, SIGNATURE_SAMPLE)) {
            ToolFailure.Recognized r = ToolFailure.recognize(
                    f.errorType(), f.isError(), f.errorTypeAttr(), f.exceptionAttr(), parseJson(f.resultJson()));
            // A row the predicate selected that no rule recognizes changed under the read. Dropped rather
            // than counted, because a failure this cannot describe is one the breakdown cannot explain.
            if (r == null) continue;
            observed.add(r);
            String traceId = f.traceId();
            if (traceId != null && !traceId.isBlank() && traces.size() < WITNESS_TRACES && !traces.contains(traceId)) {
                traces.add(traceId);
            }
        }
        return new Failures(ToolErrorRate.ranked(observed, config.maxPatterns()), List.copyOf(traces));
    }

    private @Nullable JsonNode parseJson(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
