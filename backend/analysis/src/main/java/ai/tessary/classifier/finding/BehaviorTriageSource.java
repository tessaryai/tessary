// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.StepWords;
import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessDetailService;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.frustration.FrustrationDetailService;
import ai.tessary.classifier.malformed.MalformedOutputDetailService;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricControl;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
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
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared {@code finding} table's {@link TriageSource}. {@code @Order(0)} is wire-observable: it orders the findings
 * page and who claims an id first. Escalation enqueues before marking, since marking first would let a failed enqueue
 * block the cause from ever escalating.
 */
@Component
@Order(0)
public class BehaviorTriageSource implements TriageSource {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageSource.class);

    /** Pre-seam jobs carry no kind and mean this. */
    public static final String KIND = "behavior";

    private static final int DEFAULT_FINDING_LIMIT = 200;

    /** Frustration is not here: its findings are ruled when they are filed. */
    private static final List<String> BEHAVIOR_CLASSIFIERS = List.of(
            BuiltInDetector.Kind.DURATION_DRIFT,
            BuiltInDetector.Kind.COST_DRIFT,
            BuiltInDetector.Kind.TOOL_ERROR,
            BuiltInDetector.Kind.SECRET_LEAK,
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            BuiltInDetector.Kind.GROUNDEDNESS);

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ClassifierRepository signals;
    private final ClassifierService classifiers;

    private final BehaviorTriageJobRepository jobs;
    private final BehaviorTriageEngine engine;
    /** Runs inside the ruling's transaction, not on a later sweep. */
    private final CaseOpener caseOpener;

    private final MetricBaselineRepository baselines;
    private final ToolErrorReferenceRepository toolErrorReferences;
    /**
     * Absorbing a spell also clears its accumulator, or the moved reference would alarm again on the tool's next call.
     */
    private final ToolErrorStateRepository toolErrorStates;

    private final ToolErrorService toolErrors;

    private final BehaviorBaselineEventRepository events;

    private final ObjectMapper mapper;

    private final MalformedOutputDetailService malformedOutputs;

    private final SecretLeakDetailService secretLeaks;

    private final FrustrationDetailService frustrations;

    private final GroundednessRateRepository groundednessRates;

    private final GroundednessDetailService groundednessDetail;

    /** Empty in the open edition. */
    private final FindingAdapters adapters;

    /** Each finding's newest triage run, so a page can show the step a run is on and how many it took. */
    private final AgentRunRepository runs;

    public BehaviorTriageSource(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierRepository signals,
            ClassifierService classifiers,
            BehaviorTriageJobRepository jobs,
            BehaviorTriageEngine engine,
            CaseOpener caseOpener,
            MetricBaselineRepository baselines,
            ToolErrorReferenceRepository toolErrorReferences,
            ToolErrorStateRepository toolErrorStates,
            ToolErrorService toolErrors,
            BehaviorBaselineEventRepository events,
            ObjectMapper mapper,
            MalformedOutputDetailService malformedOutputs,
            SecretLeakDetailService secretLeaks,
            FrustrationDetailService frustrations,
            GroundednessRateRepository groundednessRates,
            GroundednessDetailService groundednessDetail,
            FindingAdapters adapters,
            AgentRunRepository runs) {
        this.findings = findings;
        this.evidence = evidence;
        this.signals = signals;
        this.classifiers = classifiers;
        this.jobs = jobs;
        this.engine = engine;
        this.caseOpener = caseOpener;
        this.baselines = baselines;
        this.toolErrorReferences = toolErrorReferences;
        this.toolErrorStates = toolErrorStates;
        this.toolErrors = toolErrors;
        this.events = events;
        this.mapper = mapper;
        this.malformedOutputs = malformedOutputs;
        this.secretLeaks = secretLeaks;
        this.frustrations = frustrations;
        this.groundednessRates = groundednessRates;
        this.groundednessDetail = groundednessDetail;
        this.adapters = adapters;
        this.runs = runs;
    }

    @Override
    public String kind() {
        return KIND;
    }

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
        // One evidence query per page, not one per row.
        List<String> visibleIds = visible.stream().map(FindingRow::id).toList();
        // Without this read a queued, held or dead-lettered triage looks identical to one still running.
        Map<String, BehaviorTriageJobRepository.TriageJobState> states = jobs.statesByFinding(projectId, visibleIds);
        Map<String, AgentRunRepository.RunSummary> latest =
                runs.latestBySubjects(projectId, AgentRunRepository.Lane.TRIAGE, visibleIds);
        return visible.stream()
                .map(f -> BehaviorFindingView.of(f, states.get(f.id()), latest.get(f.id())))
                .toList();
    }

    @Override
    public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
        if (findings.findById(projectId, findingId).isEmpty()) return Optional.empty();
        // A withheld classifier's finding must 404, and this source has claimed the id, so throwing is the contract.
        FindingRow finding = requireReachableFinding(projectId, findingId);
        BehaviorTriageJobRepository.TriageJobState job =
                jobs.statesByFinding(projectId, List.of(findingId)).get(findingId);
        return Optional.of(BehaviorFindingDetailView.of(
                finding,
                malformedOutputs.detail(finding),
                secretLeaks.detail(finding),
                frustrations.detail(finding),
                groundednessDetail.detail(finding),
                adapters.detail(finding).orElse(null),
                job,
                runs.latestBySubjects(projectId, AgentRunRepository.Lane.TRIAGE, List.of(findingId))
                        .get(findingId)));
    }

    @Override
    public List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit) {
        List<FindingRow> rows = findings.listAutoEscalatable(
                projectId, escalatableKeys(projectId, minObservations), minObservations, limit);
        List<Escalatable> out = new ArrayList<>();
        for (FindingRow f : rows) {
            out.add(new Escalatable(f.id()));
        }
        return List.copyOf(out);
    }

    /** With no adapter this is the built-in list and the candidate read never runs. */
    private List<String> escalatableKeys(String projectId, long minObservations) {
        if (adapters.isEmpty()) return BEHAVIOR_CLASSIFIERS;
        long start = System.nanoTime();
        List<String> keys = new ArrayList<>(BEHAVIOR_CLASSIFIERS);
        List<FindingClaim> candidates =
                findings.escalationCandidatesByKey(projectId, BEHAVIOR_CLASSIFIERS, minObservations);
        for (FindingClaim candidate : candidates) {
            if (adapters.ownerOf(candidate).isPresent()) keys.add(candidate.classifierKey());
        }
        StructuredLog.info(log, Markers.OPS, "finding.adapter.escalation")
                .field("project", projectId)
                .field("candidates", candidates.size())
                .field("owned", keys.size() - BEHAVIOR_CLASSIFIERS.size())
                .field("durationMs", (System.nanoTime() - start) / 1_000_000)
                .log();
        return List.copyOf(keys);
    }

    /** A withheld classifier's finding reads as not-owned, so a stale tab cannot hand it to Layer 2 by id. */
    @Override
    public Optional<BehaviorAnalysisView> analyze(String projectId, String findingId) {
        Optional<FindingRow> found = findings.findById(projectId, findingId);
        if (found.isEmpty()) return Optional.empty();
        FindingRow finding = found.get();
        if (!BEHAVIOR_CLASSIFIERS.contains(finding.classifierKey())
                && adapters.ownerOf(finding.claim()).isEmpty()) {
            return Optional.empty();
        }
        if (classifiers.unavailableDetectorKinds(projectId).contains(finding.classifierKey())) {
            return Optional.empty();
        }
        // The gate is the finding's own population: citing nothing, there are no rows for the agent to page.
        if (!citesEvidence(projectId, findingId, finding)) {
            throw new TessaryException(ClassifierError.FINDING_HAS_NO_EVIDENCE, findingId);
        }
        var outcome = jobs.enqueue(projectId, findingId, Instant.now().toString());
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

    @Override
    public Optional<TriageBrief> brief(BehaviorTriageJobRow job) {
        // Also empty when the finding was settled while the job waited, so the worker marks it done before any sandbox
        // spends a run.
        return findings.findById(job.projectId(), job.findingId())
                .filter(finding -> FindingRow.Status.OPEN.equals(finding.status()) && finding.triageVerdict() == null)
                .map(finding -> new TriageBrief(
                        engine.dossier(job, finding), engine.buildPrompt(job, finding), stepWords(finding)));
    }

    /** What the run's steps call the finding's evidence rows, and how many of each the finding cites. */
    static StepWords.Context stepWords(FindingRow finding) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String role : FindingEvidenceRow.Role.ALL) totals.put(role, finding.evidenceCount(role));
        Map<String, String> nouns =
                switch (finding.classifierKey()) {
                    case BuiltInDetector.Kind.GROUNDEDNESS ->
                        Map.of(FindingEvidenceRow.Role.WITNESS, "flagged answers");
                    case BuiltInDetector.Kind.FRUSTRATION -> Map.of(FindingEvidenceRow.Role.WITNESS, "flagged turns");
                    default -> Map.of();
                };
        return new StepWords.Context(nouns, totals);
    }

    /**
     * The ruling and the detector fold are one microVM run's product; a retry of a half-applied write would re-spend
     * the run.
     */
    @Override
    @Transactional
    public void recordVerdict(
            String projectId,
            String findingId,
            BehaviorTriageVerdict verdict,
            @Nullable String citationsJson,
            String now) {
        int updated =
                findings.recordTriage(projectId, findingId, verdict.verdict(), verdict.summary(), citationsJson, now);
        if (updated == 0) {
            // Another ruling won the race; the answer is moot and there is nowhere to write it, so log rather than
            // throw.
            StructuredLog.info(log, Markers.OPS, "behavior.triage.superseded")
                    .field("project", projectId)
                    .field("finding", findingId)
                    .log();
            return;
        }
        // Outside recordTriage's failure: the ruling must survive a problem writing advisory detector state.
        findings.findById(projectId, findingId).ifPresent(finding -> foldIfRuledNegative(finding, verdict));
        // In the SAME transaction as the ruling: a ruled finding leaves ux_finding_live, so no later sweep would see
        // it. A no-op on a negative.
        caseOpener.ensureCaseFor(projectId, findingId, null);
    }

    /**
     * Folds a tool-error window back into the reference on a NEGATIVE ruling only, since only that asserts the traffic
     * was ordinary. Only tool error has an accumulator that outlives a close.
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
            // Never rethrown: the ruling is written, and only the arm reset is lost.
            StructuredLog.warn(log, Markers.OPS, "toolerror.fold.failed")
                    .field("project", finding.projectId())
                    .field("finding", finding.id())
                    .field("error", e.toString())
                    .log();
        }
    }

    private static final String LEGITIMATE_SUMMARY = "A person ruled this legitimate.";

    private static final String REAL_DEVIATION_SUMMARY = "A person ruled this a real deviation.";

    /**
     * Resolve a finding: absorb the cause ({@code expected}) or mark it a real deviation that opens or joins its case
     * ({@code not_expected}). Each cause kind moves different detector state; an adapter-owned finding is absorbed
     * first so a throwing hook rolls the ruling back. The {@code OPEN_UNRULED} guard runs up front so detector state
     * never moves for a press that cannot land. The caller owns the transaction.
     */
    @Override
    public Optional<BehaviorFindingView> resolve(
            String projectId, String findingId, String action, @Nullable String userId) {
        if (findings.findById(projectId, findingId).isEmpty()) return Optional.empty();
        boolean expected = BehaviorResolutionRequest.EXPECTED.equals(action);
        FindingRow finding = requireReachableFinding(projectId, findingId);
        if (!FindingRow.Status.OPEN.equals(finding.status()) || finding.triageVerdict() != null) {
            throw new TessaryException(ClassifierError.FINDING_CLOSED, findingId);
        }

        String now = Instant.now().toString();
        // Owner first: an adapter's finding may carry any cause kind.
        Optional<FindingAdapter> owner = adapters.ownerOf(finding.claim());
        if (owner.isPresent()) {
            if (expected) absorb(owner.get(), finding, userId, now);
            writeHumanRuling(projectId, finding.id(), expected, userId, now);
            logResolved(projectId, findingId, finding.causeKind(), action);
            return Optional.of(reread(projectId, findingId));
        }
        if (FindingRow.Cause.DISTRIBUTION_SHIFT.equals(finding.causeKind())) {
            return Optional.of(resolveShift(projectId, finding, expected, action, userId, now));
        }
        if (FindingRow.Cause.RATE_SHIFT.equals(finding.causeKind())) {
            return Optional.of(resolveRateShift(projectId, finding, expected, action, userId, now));
        }
        if (FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) {
            if (expected) relearnGroundedness(projectId, finding, userId, now);
            writeHumanRuling(projectId, finding.id(), expected, userId, now);
            logResolved(projectId, findingId, finding.causeKind(), action);
            return Optional.of(reread(projectId, findingId));
        }
        // Neither has fitted detector state; the ruling write alone is the correction.
        if (FindingRow.Cause.MALFORMED_RATE.equals(finding.causeKind())
                || FindingRow.Cause.ARMED_WINDOW.equals(finding.causeKind())) {
            writeHumanRuling(projectId, finding.id(), expected, userId, now);
            logResolved(projectId, findingId, finding.causeKind(), action);
            return Optional.of(reread(projectId, findingId));
        }
        // Reached only when a new cause kind has no branch above.
        throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }

    /**
     * Public for {@code CaseService#absorb}: an absorbed case's finding already carries a positive ruling, so
     * {@link #resolve} would 409. {@code CaseLedger#absorb} closes the finding; this only moves the reference.
     */
    public void repin(String projectId, String findingId, @Nullable String userId) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        String now = Instant.now().toString();
        Optional<FindingAdapter> owner = adapters.ownerOf(finding.claim());
        if (owner.isPresent()) {
            absorb(owner.get(), finding, userId, now);
            return;
        }
        if (FindingRow.Cause.DISTRIBUTION_SHIFT.equals(finding.causeKind())) {
            repinShift(projectId, finding, userId, now);
            return;
        }
        if (FindingRow.Cause.RATE_SHIFT.equals(finding.causeKind())) {
            repinRateShift(projectId, finding, userId, now);
            return;
        }
        if (FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) {
            relearnGroundedness(projectId, finding, userId, now);
        }
    }

    /**
     * Fold every window a rate_shift case holds into the tool-error reference at once: each spell is a separate row
     * with its own counts, so absorbing only the newest would leave earlier spells unaccepted. Other cause kinds re-pin
     * off the newest finding via {@link #repin}.
     */
    public void repinCase(String projectId, List<FindingRow> caseFindings, @Nullable String userId) {
        if (caseFindings.isEmpty()) return;
        FindingRow newest = caseFindings.get(0);
        // Owner first, as resolve and repin ask.
        if (adapters.ownerOf(newest.claim()).isPresent() || !FindingRow.Cause.RATE_SHIFT.equals(newest.causeKind())) {
            repin(projectId, newest.id(), userId);
            return;
        }
        String bucketKey = null;
        long nCur = 0;
        long failuresCur = 0;
        for (FindingRow finding : caseFindings) {
            ToolErrorEvidence.Read read = ToolErrorEvidence.read(finding.payloadJson());
            if (read == null || !read.countsAreOnsetRun()) {
                // Folding a case with one unreadable spell would silently under-count what is accepted as normal.
                throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
            }
            bucketKey = read.bucketKey();
            nCur += read.nCur();
            failuresCur += read.failuresCur();
        }
        pinToolErrorReference(
                projectId,
                Objects.requireNonNull(bucketKey),
                nCur,
                failuresCur,
                userId,
                Instant.now().toString(),
                "Absorbed.");
    }

    private void absorb(FindingAdapter owner, FindingRow finding, @Nullable String userId, String now) {
        long start = System.nanoTime();
        owner.absorbed(finding, userId, now);
        StructuredLog.info(log, Markers.OPS, "finding.adapter.absorbed")
                .field("project", finding.projectId())
                .field("finding", finding.id())
                .field("adapter", owner.getClass().getSimpleName())
                .field("durationMs", (System.nanoTime() - start) / 1_000_000)
                .log();
    }

    /** The caller's clock, so this and the detector-state write share a timestamp. */
    private void writeHumanRuling(
            String projectId, String findingId, boolean expected, @Nullable String userId, String now) {
        findings.recordHumanRuling(
                projectId,
                findingId,
                expected ? FindingRow.TriageVerdict.NEGATIVE : FindingRow.TriageVerdict.POSITIVE,
                expected ? LEGITIMATE_SUMMARY : REAL_DEVIATION_SUMMARY,
                now);
        if (!expected) {
            caseOpener.ensureCaseFor(projectId, findingId, userId);
        }
    }

    /**
     * Absorbing pins the tool's running counts as its new reference and resumes the replay from now; closing the
     * finding alone would re-alarm within minutes, since the replay re-learns the same reference. A pinned 8% still
     * alarms at 30%. The reference must not move on {@code not_expected}, for the reason {@link #resolveShift} gives.
     */
    private BehaviorFindingView resolveRateShift(
            String projectId,
            FindingRow finding,
            boolean expected,
            String action,
            @Nullable String userId,
            String now) {
        if (expected) repinRateShift(projectId, finding, userId, now, "Absorbed: " + action);
        writeHumanRuling(projectId, finding.id(), expected, userId, now);
        logResolved(projectId, finding.id(), finding.causeKind(), action);
        return reread(projectId, finding.id());
    }

    /**
     * Groundedness learns its reference rather than pinning one, so absorbing resets it; the reset fence keeps the
     * absorbed hours out of the next replay.
     */
    private void relearnGroundedness(String projectId, FindingRow finding, @Nullable String userId, String now) {
        String callSite = finding.callSiteId() != null ? finding.callSiteId() : finding.nativeCauseKey();
        groundednessRates.states().resetAndRelearn(projectId, callSite, userId, "Absorbed.", now);
    }

    private void repinRateShift(String projectId, FindingRow finding, @Nullable String userId, String now) {
        repinRateShift(projectId, finding, userId, now, "Absorbed.");
    }

    private void repinRateShift(
            String projectId, FindingRow finding, @Nullable String userId, String now, String resetReason) {
        ToolErrorEvidence.Read read = ToolErrorEvidence.read(finding.payloadJson());
        if (read == null || !read.countsAreOnsetRun()) {
            // Without a readable blob this refuses rather than guessing. countsAreOnsetRun also refuses an older
            // payload carrying a tool's whole history, which would absorb a lifetime average.
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
        }
        pinToolErrorReference(projectId, read.bucketKey(), read.nCur(), read.failuresCur(), userId, now, resetReason);
    }

    private void pinToolErrorReference(
            String projectId,
            String bucketKey,
            long nCur,
            long failuresCur,
            @Nullable String userId,
            String now,
            String resetReason) {
        ToolErrorConfig config = toolErrorConfig(projectId);
        String epoch = CarriedState.epochOf(config, ToolErrorTrend.STATE_SCHEMA_VERSION);
        if (nCur >= config.minBaselineCalls()) {
            toolErrorReferences.pin(
                    projectId,
                    bucketKey,
                    nCur,
                    failuresCur,
                    userId,
                    // Dated by the human decision, and the replay resumes from here, so no counted bucket may sit after
                    // it.
                    now);
            toolErrorStates.clearPendingPin(projectId, bucketKey, now);
            // Accepted evidence must stop counting, or the tool alarms again on its next call.
            toolErrorStates.reset(projectId, bucketKey, userId, resetReason, now);
        } else {
            // Too early to measure a new normal: a dozen calls at 83% would become an 83% baseline. The decision
            // installs itself once the run is thick enough.
            toolErrorStates.markPendingPin(projectId, bucketKey, userId, now, epoch);
        }
    }

    /**
     * Absorbing moves the reference onto the current level, logged to the baseline changelog; a real deviation does NOT
     * move it, or the next window would compare a broken system to itself and read as a recovery. Only a human reaches
     * this: no machine ruling is authority to move a baseline.
     */
    private BehaviorFindingView resolveShift(
            String projectId,
            FindingRow finding,
            boolean expected,
            String action,
            @Nullable String userId,
            String now) {
        if (expected) repinShift(projectId, finding, userId, now);
        // No annotation: a distribution shift makes no per-trace claim, and the correction is the BASELINE_REPINNED row
        // above.
        writeHumanRuling(projectId, finding.id(), expected, userId, now);
        logResolved(projectId, finding.id(), finding.causeKind(), action);
        return reread(projectId, finding.id());
    }

    private void repinShift(String projectId, FindingRow finding, @Nullable String userId, String now) {
        // Guaranteed by behavior_finding_scope_check, checked for the nullness checker and so that a
        // hand-written row with the wrong scope fails at the API rather than half-way through a write.
        String baselineId = finding.baselineId();
        if (baselineId == null) {
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
        }
        MetricBaselineRow baseline = baselines
                .findById(projectId, baselineId)
                .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id()));
        // Not `current_sketch_json`: that window is still filling, and pinning it would install a reference below
        // min_sample. The newest day of the rolling control is the newest complete summary; `current` is only the
        // fallback.
        MetricControl.Day recent =
                MetricControl.fromJson(baseline.controlJson()).newest();
        String absorbed = recent != null ? recent.sketchJson() : baseline.currentSketchJson();
        // Both sidecars come from the same window as the sketch, never mixed.
        String absorbedWorkload = recent != null ? recent.workloadJson() : baseline.currentWorkloadJson();
        String absorbedTokens = recent != null ? recent.tokensJson() : baseline.currentTokensJson();
        baselines.repin(
                baselineId,
                absorbed,
                absorbedWorkload,
                absorbedTokens,
                // No refs: the absorbed sketch is a merged day of the ring, which keeps histograms, not rows.
                null,
                // Dated by the human decision, not by the traffic it summarizes.
                now,
                finding.sinceVersionId(),
                now);
        // Every absorption a person chose becomes a durable, readable changelog row.
        events.insert(BehaviorBaselineEventRow.forBaseline(
                Ids.ulid(),
                baselineId,
                projectId,
                BehaviorBaselineEventRow.Event.BASELINE_REPINNED,
                finding.nativeCauseKey(),
                now,
                finding.payloadJson()));
        if (userId != null) {
            StructuredLog.info(log, Markers.OPS, "metric.baseline.repinned")
                    .field("project", projectId)
                    .field("baseline", baselineId)
                    .field("cause", finding.nativeCauseKey())
                    .field("by", userId)
                    .field("repinned", true)
                    .log();
        }
    }

    /** Read here so absorption uses the same {@code minBaselineCalls} the sweep does. */
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

    private BehaviorFindingView reread(String projectId, String findingId) {
        return BehaviorFindingView.of(findings.findById(projectId, findingId)
                .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId)));
    }

    /**
     * {@code evidence_counts} is what the claim rested on at finding-open; the evidence table is re-read only when it
     * is absent.
     */
    private boolean citesEvidence(String projectId, String findingId, FindingRow finding) {
        for (String role : FindingEvidenceRow.Role.ALL) {
            if (finding.evidenceCount(role) > 0) return true;
        }
        // countsByRole seeds every role to zero, so test values, not emptiness.
        return evidence.countsByRole(projectId, findingId).values().stream().anyMatch(n -> n > 0);
    }
}
