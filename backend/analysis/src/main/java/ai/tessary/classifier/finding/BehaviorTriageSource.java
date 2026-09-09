// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricControl;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorService;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.AnnotationRepository;
import ai.tessary.storage.AnnotationRow;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The shared {@code finding} table's {@link TriageSource}: the list, the detail, the correction loop
 * and the Layer-2 escalation for the three classifiers that write it, behaviour drift, metric drift
 * (both measures) and tool error.
 *
 * <p>{@code @Order(0)} is wire-observable: Spring sorts the injected {@code List<TriageSource>} by it,
 * and that order is the order rows appear on the findings page and the order sources are asked to
 * claim an id. Do not rely on declaration, bean-name or classpath order, and do not renumber without
 * deciding that the page's row order should change.
 *
 * <p>Escalation happens once per cause: {@code markEscalated} is conditional on
 * {@code escalated_at IS NULL} and {@code enqueue} dedupes on {@code (project, finding)}, so a second
 * press lands on the existing job instead of a second microVM. Enqueue first, mark second: marking
 * first would let a failed enqueue leave {@code escalated_at} set with no job behind it, and the
 * cause could never be escalated again.
 *
 * <p>{@link #detail} disclaims an SOP-keyed row (only conformance's own projection carries the
 * {@code kind} and baseline block that page renders), while {@link #resolve} claims any row present in
 * the shared table, SOP-keyed included, which makes conformance's resolve arm unreachable in practice:
 * conformance rows live in {@code finding}, so the shared read never comes back empty for one, and
 * control reaches the {@code profileId == null} guard and 404s. That asymmetry is preserved
 * deliberately rather than tidied, since fixing it would change wire behaviour.
 */
@Component
@Order(0)
public class BehaviorTriageSource implements TriageSource {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageSource.class);

    /** The payload kind of a shared-table escalation; pre-seam jobs carry no kind and mean this. */
    public static final String KIND = "behavior";

    /** Page ceiling for one source's contribution to the findings list. */
    private static final int DEFAULT_FINDING_LIMIT = 200;

    /**
     * The classifiers this source escalates for. Naming them explicitly keeps a conformance finding,
     * which has its own source and its own dossier, out of this lane's queue.
     */
    private static final List<String> BEHAVIOR_CLASSIFIERS = List.of(
            BuiltInDetector.Kind.BEHAVIOR_DRIFT, BuiltInDetector.Kind.DURATION_DRIFT,
            BuiltInDetector.Kind.COST_DRIFT, BuiltInDetector.Kind.TOOL_ERROR);

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ClassifierRepository signals;
    /** The flag layer, asked whether this org still has the classifier a finding came from. */
    private final ClassifierService classifiers;

    private final BehaviorTriageJobRepository jobs;
    private final BehaviorTriageEngine engine;

    /** Read + re-pinned by the metric-drift branch of {@link #resolve} only; drift never touches it. */
    private final MetricBaselineRepository baselines;
    /** Pinned by the tool-error branch of {@link #resolve} only. */
    private final ToolErrorReferenceRepository toolErrorReferences;
    /**
     * The tool-error accumulator. Touched here so that absorbing a spell also clears the evidence
     * behind it: a reference that moved while the accumulator stayed where the outage left it would
     * alarm again on the tool's next call.
     */
    private final ToolErrorStateRepository toolErrorStates;
    /** The same accumulator, folded back after a NEGATIVE ruling rather than after a human verb. */
    private final ToolErrorService toolErrors;

    private final BehaviorBaselineEventRepository events;
    private final AnnotationRepository annotations;
    private final BehaviorSubstrateRepository substrate;
    /**
     * The classifier-specific half of a correction, for the causes that have fitted state to move. May
     * be empty, in which case the resolution still records the human's judgement; see
     * {@link CauseResolver}.
     */
    private final List<CauseResolver> causeResolvers;

    private final ObjectMapper mapper;

    public BehaviorTriageSource(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierRepository signals,
            ClassifierService classifiers,
            BehaviorTriageJobRepository jobs,
            BehaviorTriageEngine engine,
            MetricBaselineRepository baselines,
            ToolErrorReferenceRepository toolErrorReferences,
            ToolErrorStateRepository toolErrorStates,
            ToolErrorService toolErrors,
            BehaviorBaselineEventRepository events,
            AnnotationRepository annotations,
            BehaviorSubstrateRepository substrate,
            ObjectProvider<CauseResolver> causeResolvers,
            ObjectMapper mapper) {
        this.findings = findings;
        this.evidence = evidence;
        this.signals = signals;
        this.classifiers = classifiers;
        this.jobs = jobs;
        this.engine = engine;
        this.baselines = baselines;
        this.toolErrorReferences = toolErrorReferences;
        this.toolErrorStates = toolErrorStates;
        this.toolErrors = toolErrors;
        this.events = events;
        this.annotations = annotations;
        this.substrate = substrate;
        // ObjectProvider, not List<T>: a required constructor List<T> parameter with no candidate bean
        // is an unsatisfied dependency in Spring, not an empty list, and would fail startup instead of
        // degrading to what a classifier with no data shows, which is what this field's javadoc
        // promises. Held by AbsentAdapterContextTest.
        this.causeResolvers = causeResolvers.orderedStream().toList();
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return KIND;
    }

    // ---- the findings page ----------------------------------------------------------------------

    @Override
    public List<BehaviorFindingView> list(
            String projectId,
            @Nullable String status,
            @Nullable String callSiteId,
            @Nullable String detector,
            boolean confirmedOnly) {
        Set<String> unavailable = classifiers.unavailableDetectorKinds(projectId);
        List<FindingRow> visible = FindingFilters.visible(
                findings.listByProject(projectId, status, callSiteId, detector, confirmedOnly, DEFAULT_FINDING_LIMIT),
                unavailable);
        // One evidence query for the whole page, not one per row: an N+1 here would be paid on every
        // render of the busiest surface the classifiers have.
        List<String> visibleIds = visible.stream().map(FindingRow::id).toList();
        // Second page-wide read, same reason: a finding whose triage dead-lettered looks identical to
        // one still running from the finding row alone otherwise.
        Map<String, BehaviorTriageJobRepository.FailedTriage> failed = jobs.failedByFinding(projectId, visibleIds);
        return visible.stream()
                .map(f -> BehaviorFindingView.of(f, failed.get(f.id())))
                .toList();
    }

    @Override
    public long countWithheld(String projectId, @Nullable String callSiteId, boolean confirmedOnly) {
        return confirmedOnly ? findings.countWithheld(projectId, callSiteId) : 0;
    }

    /**
     * The shared projection, for any row in this table that is not SOP-keyed. Routed on
     * {@code classifier_key} rather than on "the shared read came back empty", since conformance rows
     * live in this table too.
     */
    @Override
    public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
        Optional<FindingRow> shared = findings.findById(projectId, findingId);
        if (shared.isEmpty()
                || BuiltInDetector.Kind.SOP_CONFORMANCE.equals(shared.get().classifierKey())) {
            return Optional.empty();
        }
        // Reachability is re-asserted rather than assumed: a withheld classifier's finding must 404,
        // and this source has now claimed the id, so throwing is the contract.
        return Optional.of(BehaviorFindingDetailView.of(requireReachableFinding(projectId, findingId)));
    }

    // ---- escalation -----------------------------------------------------------------------------

    @Override
    public List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit) {
        List<FindingRow> rows = findings.listAutoEscalatable(projectId, BEHAVIOR_CLASSIFIERS, minObservations, limit);
        List<Escalatable> out = new java.util.ArrayList<>();
        for (FindingRow f : rows) {
            // No per-row re-read: eligibility is listAutoEscalatable's own predicate, and the population
            // is behind MCP, so nothing downstream needs a trace looked up per row.
            out.add(new Escalatable(f.id(), classifierKeyOf(projectId, f)));
        }
        return List.copyOf(out);
    }

    /**
     * A finding whose classifier the org does not have reads as not-owned, the same 404 the rest of
     * the surface gives it, so a stale tab cannot hand a withheld finding to Layer 2 by id.
     */
    @Override
    public Optional<BehaviorAnalysisView> analyze(String projectId, String findingId) {
        Optional<FindingRow> found = findings.findById(projectId, findingId);
        if (found.isEmpty()) return Optional.empty();
        FindingRow finding = found.get();
        // Conformance shares the table and not this lane: its dossier is the SOP premise, its
        // escalation carries a different payload kind, and it has its own source ordered after this
        // one, so the classifier has to say explicitly whether it belongs here.
        if (!BEHAVIOR_CLASSIFIERS.contains(finding.classifierKey())) return Optional.empty();
        if (classifiers.unavailableDetectorKinds(projectId).contains(finding.classifierKey())) {
            return Optional.empty();
        }
        // The gate is the finding's own population, not a trace to point at: a finding citing nothing
        // has no rows for the agent to page and would boot a microVM to rule on the claim read back to
        // us, while a finding citing thousands of spans is triageable whether or not any single one of
        // them is distinguished.
        if (!citesEvidence(projectId, findingId, finding)) {
            throw new TessaryException(ClassifierError.FINDING_HAS_NO_EVIDENCE, findingId);
        }
        var outcome = jobs.enqueue(
                projectId,
                findingId,
                finding.exemplarVerdictId(),
                classifierKeyOf(projectId, finding),
                // Which look this is: the one already scheduled, or the next one after a re-open.
                jobs.lookFor(projectId, findingId, finding.escalatedAt()),
                Instant.now().toString());
        boolean alreadyEscalated = finding.escalatedAt() != null;
        if (!alreadyEscalated) {
            findings.markEscalated(projectId, findingId, Instant.now().toString());
        }
        log.info(
                Markers.OPS,
                "finding analysis requested project={} finding={} cause={} lane={} job={} status={}",
                projectId,
                findingId,
                finding.causeKey(),
                TriageLane.EVIDENCE_ONLY.wire(),
                outcome.jobId(),
                outcome.jobStatus());
        return Optional.of(new BehaviorAnalysisView(
                outcome.jobId(), outcome.jobStatus(), alreadyEscalated, TriageLane.EVIDENCE_ONLY.wire()));
    }

    // ---- the Layer-2 run ------------------------------------------------------------------------

    /**
     * Routed on the job's own kind, not on which store holds the id, since conformance rows live in
     * this table too and "the id is in {@code finding}" would claim a conformance job.
     */
    @Override
    public Optional<TriageBrief> brief(BehaviorTriageJobRow job) {
        if (job.isConformance()) return Optional.empty();
        // Empty also when the finding was resolved or its epoch closed while the job waited: nothing to
        // rule on, nowhere to write the answer, so the worker marks it done rather than failed.
        return findings.findById(job.projectId(), job.findingId())
                .map(finding -> new TriageBrief(
                        engine.dossier(job, finding), engine.buildPrompt(job, finding), finding.payloadJson()));
    }

    @Override
    public void recordVerdict(
            String projectId,
            String findingId,
            BehaviorTriageVerdict verdict,
            @Nullable String citationsJson,
            String now) {
        findings.recordTriage(projectId, findingId, verdict.verdict(), verdict.summary(), citationsJson, now);
        // Ordered after recordTriage and outside its failure: the ruling is the product of a microVM
        // run and must survive a problem writing detector state, which is advisory by comparison.
        findings.findById(projectId, findingId).ifPresent(finding -> foldIfRuledNegative(finding, verdict));
    }

    /**
     * Hand a tool-error window back to the detector once a ruling has established it was normal, so the
     * arm it fired on starts again from a reference that now contains it.
     *
     * <p>Folds on {@code negative} only, not every close: folding asserts that the traffic in the
     * window was ordinary and belongs in the rate the detector compares against, and only a negative
     * ruling asserts that. {@code unclear} closes the finding without establishing anything, on
     * purpose, since recurrence is the recovery.
     *
     * <p>Only tool error folds, because it is the only classifier here holding an accumulator that a
     * ruling can leave standing. Metric drift closes its own window every pass and behaviour drift
     * refits, so neither has a value that survives a close the way tool error's does.
     */
    private void foldIfRuledNegative(FindingRow finding, BehaviorTriageVerdict verdict) {
        if (!BuiltInDetector.Kind.TOOL_ERROR.equals(finding.classifierKey())) return;
        if (!FindingRow.TriageVerdict.movesDetectorState(verdict.verdict())) return;
        try {
            toolErrors.foldRuledNegative(
                    finding.projectId(),
                    finding.subjectId(),
                    finding.id(),
                    finding.payloadJson(),
                    Instant.now().toString());
        } catch (RuntimeException e) {
            // Never rethrown: the job is done and the ruling is written, and a failure here costs only
            // the arm reset, which the next human disposition or a later ruling can still perform.
            StructuredLog.warn(log, Markers.OPS, "toolerror.fold.failed")
                    .field("project", finding.projectId())
                    .field("finding", finding.id())
                    .field("error", e.toString())
                    .log();
        }
    }

    // ---- the correction loop --------------------------------------------------------------------

    /**
     * Resolve a finding: allowlist the cause permanently ({@code expected}) or pin it so it never
     * graduates and keeps firing ({@code not_expected}).
     *
     * <p>Branches on {@code cause_kind}, because the two verbs mean different writes for the three
     * classifiers sharing this table. Metric drift corrects a reference (see {@link #resolveShift});
     * tool error corrects a rate (see {@link #resolveRateShift}); everything else hangs off a fitted
     * profile and goes through {@link CauseResolver}.
     *
     * <p>The transaction is the caller's: {@code FindingService.resolve} is the annotated entry point,
     * deliberately, because a half-applied correction would read as resolved on the finding while the
     * detector keeps firing on it.
     */
    @Override
    public Optional<BehaviorFindingView> resolve(
            String projectId, String findingId, String action, @Nullable String userId) {
        // Presence in the shared table is what claims the id, SOP-keyed rows included: see the class
        // javadoc for why that is preserved rather than tidied.
        if (findings.findById(projectId, findingId).isEmpty()) return Optional.empty();
        boolean expected = BehaviorResolutionRequest.EXPECTED.equals(action);
        FindingRow finding = requireReachableFinding(projectId, findingId);

        String now = Instant.now().toString();
        if (FindingRow.Cause.DISTRIBUTION_SHIFT.equals(finding.causeKind())) {
            return Optional.of(resolveShift(projectId, finding, expected, action, userId, now));
        }
        if (FindingRow.Cause.RATE_SHIFT.equals(finding.causeKind())) {
            return Optional.of(resolveRateShift(projectId, finding, expected, action, userId, now));
        }
        // Every cause that reaches here hangs off a fitted profile. This is no longer guaranteed by a
        // schema check: a finding can have neither a profile nor a baseline when its cause is
        // recomputed, so the branches above must claim every scope-less cause kind before control gets
        // this far. A new cause kind that hangs off nothing needs its own branch; this throw is what it
        // looks like when one is forgotten.
        if (finding.profileId() == null) {
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId);
        }
        String annotationKey = causeResolvers.stream()
                .filter(r -> r.owns(finding.causeKind()))
                .findFirst()
                .map(r -> r.apply(projectId, finding, expected, userId, now))
                .orElse(null);
        // The caller's clock, so the finding's human_verdict_at and the baseline_event announcing the
        // same judgement carry one timestamp rather than two.
        findings.setStatus(
                projectId, findingId, expected ? FindingRow.Status.ALLOWLISTED : FindingRow.Status.BLOCKED, now);
        recordAnnotation(projectId, finding, annotationKey, expected, userId);
        events.insert(BehaviorBaselineEventRow.forProfile(
                Ids.ulid(),
                finding.profileId(),
                projectId,
                expected
                        ? BehaviorBaselineEventRow.Event.GRAM_ALLOWLISTED
                        : BehaviorBaselineEventRow.Event.GRAM_BLOCKED,
                finding.workflowKey(),
                finding.nativeCauseKey(),
                now,
                null));
        logResolved(projectId, findingId, finding.causeKind(), action);
        return Optional.of(reread(projectId, findingId));
    }

    /**
     * The tool-error branch of the correction loop. Same two verbs again, and a third set of writes,
     * because what a human is correcting here is a rate, and a rate has no fitted state to move.
     *
     * <table>
     *   <tr><th>Verb</th><th>What happens</th></tr>
     *   <tr><td><em>Legitimate (absorb)</em> ({@code expected})</td>
     *       <td>the counts the tool has been running at become its accepted reference, and the replay
     *           resumes from now, so the detector compares against the new rate and stays quiet until it
     *           moves again</td></tr>
     *   <tr><td><em>Real deviation</em> ({@code not_expected})</td>
     *       <td>no reference is pinned; the finding is marked as a human-confirmed regression, which is
     *           what the case gate reads</td></tr>
     * </table>
     *
     * <p>Absorbing has to write something, since the replay is recomputed from an hourly aggregate on
     * every read: closing the finding alone accomplishes nothing, because the next pass re-learns the
     * same reference off the same leading buckets and re-alarms within minutes. The row this pins is a
     * reference, not a suppression: a tool accepted at 8% still alarms at 30%.
     *
     * <p>No allowlist row: an allowlist entry is keyed on a profile and means "this gram is fine
     * forever", which is not a statement anyone can make about a rate whose reference moves.
     *
     * <p>The reference must not move on {@code not_expected}, for the reason {@link #resolveShift}
     * gives in full: a reference that absorbed a confirmed regression would compare a broken tool
     * against its broken self, report no shift, and close the case as a recovery.
     */
    private BehaviorFindingView resolveRateShift(
            String projectId,
            FindingRow finding,
            boolean expected,
            String action,
            @Nullable String userId,
            String now) {
        if (expected) {
            ToolErrorEvidence.Read read = ToolErrorEvidence.read(finding.payloadJson());
            if (read == null || !read.countsAreOnsetRun()) {
                // The counts to accept live in the evidence blob and nowhere else, so absorbing without
                // a blob this can describe refuses rather than guessing: the finding stays open and
                // "real deviation" still works. countsAreOnsetRun refuses a subtler case too, where an
                // older payload carries a tool's whole history under the same keys, which would absorb a
                // lifetime average as the new normal for a tool that is on fire.
                throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
            }
            ToolErrorConfig config = toolErrorConfig(projectId);
            String epoch = CarriedState.epochOf(config, ToolErrorTrend.STATE_SCHEMA_VERSION);
            if (read.nCur() >= config.minBaselineCalls()) {
                toolErrorReferences.pin(
                        projectId,
                        read.bucketKey(),
                        read.nCur(),
                        read.failuresCur(),
                        userId,
                        // The caller's clock: this reference is dated by the human decision that
                        // installed it, and the replay resumes from here, so it must be a time no
                        // already-counted bucket sits after.
                        now);
                toolErrorStates.clearPendingPin(projectId, read.bucketKey(), now);
                // The evidence behind the absorbed spell has been accepted, so it must stop counting
                // against the reference that replaced it, or the accumulator stays where the outage
                // left it and the tool alarms again on its next call.
                toolErrorStates.reset(projectId, read.bucketKey(), userId, "Absorbed: " + action, now);
            } else {
                // Too early to measure a new normal from: a burst alarms in about a dozen calls, and a
                // dozen calls at 83% would become an 83% baseline. The decision is kept and installs
                // itself once the run is thick enough.
                toolErrorStates.markPendingPin(projectId, read.bucketKey(), userId, now, epoch);
            }
        }
        findings.setStatus(
                projectId, finding.id(), expected ? FindingRow.Status.ALLOWLISTED : FindingRow.Status.BLOCKED, now);
        logResolved(projectId, finding.id(), finding.causeKind(), action);
        return reread(projectId, finding.id());
    }

    /**
     * The metric-drift branch of the correction loop. Same two verbs, same two action strings, entirely
     * different writes, because what a human is correcting here is not a gram, it is the reference a
     * whole distribution is compared against.
     *
     * <table>
     *   <tr><th>Verb</th><th>What happens</th></tr>
     *   <tr><td><em>Legitimate (absorb)</em> ({@code expected})</td>
     *       <td>the reference moves onto the current level, stamped with when and with which deploy, and
     *           the move is appended to the baseline changelog</td></tr>
     *   <tr><td><em>Real deviation</em> ({@code not_expected})</td>
     *       <td>the reference does <b>not</b> move; the finding is marked as a human-confirmed
     *           regression</td></tr>
     * </table>
     *
     * <p>The reference must not move on {@code not_expected}: a reference that absorbed a confirmed
     * regression would make the very next window compare a broken system against its broken self,
     * report no shift, and close the case, so the detector would go silent through exactly the event it
     * exists to catch and it would look like a recovery on every surface in the product.
     *
     * <p>Only a human reaches this. Triage rules on whether a claim holds, never on whether the shift
     * behind it is welcome, since "legitimate" is a judgement about intent it has no evidence for, so
     * no machine ruling is authority to mutate the baseline.
     *
     * <p>No allowlist row and no gram state: both are keyed on a profile and say "this symbol is fine
     * forever", which has no meaning for a distribution whose reference moves. The re-pin is the
     * correction.
     */
    private BehaviorFindingView resolveShift(
            String projectId,
            FindingRow finding,
            boolean expected,
            String action,
            @Nullable String userId,
            String now) {
        // Guaranteed by behavior_finding_scope_check, checked for the nullness checker and so that a
        // hand-written row with the wrong scope fails at the API rather than half-way through a write.
        String baselineId = finding.baselineId();
        if (baselineId == null) {
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
        }
        if (expected) {
            MetricBaselineRow baseline = baselines
                    .findById(projectId, baselineId)
                    .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id()));
            // PROGRAM.md §9 writes this as `pinned_sketch <- current`, and the column literally named
            // `current_sketch_json` is the wrong one to read: it is the window still being FILLED, so
            // pinning it would install a reference below min_sample that the detector then silences with
            // BELOW_MIN_SAMPLE until something else replaces it. The newest COMPLETE summary of where the
            // bucket now sits is the newest day of the rolling control, which holds every window that
            // closed that day, the window the finding fired on among them, and now more traffic than
            // one window's worth. `current` is the fallback only for a bucket that has somehow closed none.
            MetricControl.Day recent =
                    MetricControl.fromJson(baseline.controlJson()).newest();
            String absorbed = recent != null ? recent.sketchJson() : baseline.currentSketchJson();
            // Both sidecars come from whichever window the sketch did, never mixed: a pinned cost
            // sketch explained by a different window's token decomposition would say the dollars were
            // made of something they were not.
            String absorbedWorkload = recent != null ? recent.workloadJson() : baseline.currentWorkloadJson();
            String absorbedTokens = recent != null ? recent.tokensJson() : baseline.currentTokensJson();
            baselines.repin(
                    baselineId,
                    absorbed,
                    absorbedWorkload,
                    absorbedTokens,
                    // No refs with it: the absorbed sketch is a day of the rolling control, every window
                    // that closed that day merged, and the ring keeps histograms, not the rows they were
                    // folded from. A finding fired against a human-absorbed reference therefore carries
                    // no baseline evidence until the sweep's own bootstrap pin replaces it.
                    null,
                    // The caller's clock here, unlike the sweep's bootstrap pin: this reference is dated
                    // by the human decision that installed it, not by the traffic it summarizes.
                    now,
                    finding.sinceVersionId(),
                    now);
            // The changelog row, carrying the finding's own evidence as its detail: an online baseline
            // cannot be stopped from absorbing drift, but every absorption can be made a durable,
            // readable row, and this is the one absorption a person chose.
            events.insert(BehaviorBaselineEventRow.forBaseline(
                    Ids.ulid(),
                    baselineId,
                    projectId,
                    BehaviorBaselineEventRow.Event.BASELINE_REPINNED,
                    finding.nativeCauseKey(),
                    now,
                    finding.payloadJson()));
        }
        // BLOCKED is what "marks for escalation" means concretely: it stamps human_verdict_at, so
        // recurrences_since_verdict starts counting windows that shifted after a person confirmed the
        // regression. ALLOWLISTED closes the absorbed cause so it stops recurring; the re-pinned
        // reference is what makes it stay closed.
        findings.setStatus(
                projectId, finding.id(), expected ? FindingRow.Status.ALLOWLISTED : FindingRow.Status.BLOCKED, now);
        // No annotation, deliberately: a distribution shift makes no per-trace claim, so there is
        // nothing per-trace for a human correction to be about. The correction the person made here is
        // to the reference itself, recorded as the BASELINE_REPINNED row above.
        logResolved(projectId, finding.id(), finding.causeKind(), action);
        if (userId != null) {
            StructuredLog.info(log, Markers.OPS, "metric.baseline.repinned")
                    .field("project", projectId)
                    .field("baseline", baselineId)
                    .field("cause", finding.nativeCauseKey())
                    .field("by", userId)
                    .field("repinned", expected)
                    .log();
        }
        return reread(projectId, finding.id());
    }

    // ---- shared helpers -------------------------------------------------------------------------

    /**
     * The project's tool-error tuning, or the shipped defaults when the classifier is not configured.
     * Read here rather than passed in, since absorption needs exactly one number from it,
     * {@code minBaselineCalls}, and that number must be the same one the sweep uses.
     */
    private ToolErrorConfig toolErrorConfig(String projectId) {
        return signals.listByProject(projectId).stream()
                .filter(c -> BuiltInDetector.Kind.TOOL_ERROR.equals(c.detector()))
                .findFirst()
                .map(c -> ToolErrorConfig.of(mapper, c.configJson()))
                .orElseGet(ToolErrorConfig::defaults);
    }

    private FindingRow requireReachableFinding(String projectId, String findingId) {
        FindingRow finding = findings.findById(projectId, findingId)
                .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId));
        if (classifiers.unavailableDetectorKinds(projectId).contains(finding.classifierKey())) {
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId);
        }
        return finding;
    }

    private void logResolved(String projectId, String findingId, String causeKind, String action) {
        StructuredLog.info(log, Markers.OPS, "behavior.finding.resolved")
                .field("project", projectId)
                .field("finding", findingId)
                .field("cause", causeKind)
                .field("action", action)
                .log();
    }

    /** The finding as it now stands, so the caller renders the write rather than what preceded it. */
    private BehaviorFindingView reread(String projectId, String findingId) {
        return BehaviorFindingView.of(findings.findById(projectId, findingId)
                .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId)));
    }

    /**
     * The correction as an {@code annotation} over the exemplar trace. {@code agrees} is a judgement
     * about the detection, not about the behaviour: marking a finding "Expected" says the detection was
     * not a real problem ({@code agrees=false}); "Not expected" confirms it ({@code agrees=true}).
     * Skipped only when the finding has no exemplar trace, since there is then no subject to annotate.
     */
    private void recordAnnotation(
            String projectId,
            FindingRow finding,
            @Nullable String classifierKey,
            boolean expected,
            @Nullable String userId) {
        String traceId = evidence.exemplarTraceId(projectId, finding.id()).orElse(null);
        if (traceId == null) return;
        // The trace's producer session, or the trace itself when the producer sent none.
        String contextId = substrate.traceSessionId(projectId, traceId).orElse(traceId);
        annotations.upsert(new AnnotationRow(
                Ids.ulid(),
                projectId,
                AnnotationRow.SubjectKind.TRACE,
                contextId,
                traceId,
                null,
                // The resolver names the classifier row whose fitted state was just corrected; the
                // fallback is the detector kind.
                classifierKey == null ? BuiltInDetector.Kind.BEHAVIOR_DRIFT : classifierKey,
                userId,
                AnnotationRow.AnnotatorKind.HUMAN,
                "boolean",
                /* passed */ null,
                /* score */ null,
                /* label */ null,
                /* textValue */ null,
                // The anchor: outlives the detection the correction was about.
                finding.id(),
                !expected,
                /* comment */ null,
                Instant.now().toString(),
                /* attributes */ null));
    }

    /**
     * Whether this finding cites a population at all. The finding's own {@code evidence_counts} is the
     * cheap answer and the honest one: it is what the classifier wrote at finding-open, so it says what
     * the claim rests on rather than what has survived retention since. It is only re-read from the
     * evidence table when the counter is absent.
     */
    private boolean citesEvidence(String projectId, String findingId, FindingRow finding) {
        for (String role : FindingEvidenceRow.Role.ALL) {
            if (finding.evidenceCount(role) > 0) return true;
        }
        // Values, not emptiness: countsByRole seeds EVERY role to zero and so is never empty.
        return evidence.countsByRole(projectId, findingId).values().stream().anyMatch(n -> n > 0);
    }

    /**
     * The classifier this finding belongs to, for the job payload's attribution. Falls back to the
     * cause kind when the project has no row for that detector, since the payload field is
     * informational and refusing an analysis over a missing definition row would be the wrong trade.
     */
    private String classifierKeyOf(String projectId, FindingRow finding) {
        String detector = finding.classifierKey();
        if (detector == null) return finding.causeKind();
        return signals.listByProject(projectId).stream()
                .filter(s -> detector.equals(s.detector()))
                .map(ClassifierRow::classifierKey)
                .findFirst()
                .orElse(detector);
    }
}
