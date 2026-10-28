// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import ai.tessary.cases.CaseOpener;
import ai.tessary.cases.CaseRow;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessConfig;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository;
import ai.tessary.classifier.frustration.FrustrationConfig;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.frustration.JevFrustrationQuestion;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.obs.Markers;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.storage.MediaRefRepository;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectCreatedEvent;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Seeds the sample project so it looks weeks old without the detection pipeline running. Fires on every
 * {@link ProjectCreatedEvent} and does nothing unless {@link Project#isSample()}; failures are logged and swallowed.
 * Lives in {@code analysis} because {@code tenancy} sits below every module whose tables it writes. Most rows are raw
 * SQL ({@link SampleDataRepository}) that plainly says it is fabricated; groundedness and frustration go through the
 * live writers. Each case key must equal what the live case source would cut from the same finding, or a real firing
 * opens a silent duplicate; {@link #seedCase} derives it from the finding.
 */
@Component
public class SampleProjectSeedListener {

    private static final Logger log = LoggerFactory.getLogger(SampleProjectSeedListener.class);

    private static final DateTimeFormatter REPORT_DATE =
            DateTimeFormatter.ofPattern("MMMM d", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final ProjectRepository projects;
    private final PipelineService pipeline;
    private final ClassifierService classifiers;
    private final MetricBaselineRepository baselines;
    private final SampleDataRepository sampleData;
    private final MediaStore mediaStore;
    private final MediaRefRepository mediaRefs;
    private final SessionRepository sessions;
    private final RetrievedDocRepository retrievedDocs;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ClassifierDetectionWriteRepository detections;
    private final GroundednessAssessmentRepository groundednessAssessments;
    private final FrustrationAssessmentRepository frustrationAssessments;
    private final CaseOpener caseOpener;
    private final ObjectMapper mapper = new ObjectMapper();

    public SampleProjectSeedListener(
            ProjectRepository projects,
            PipelineService pipeline,
            ClassifierService classifiers,
            MetricBaselineRepository baselines,
            SampleDataRepository sampleData,
            MediaStore mediaStore,
            MediaRefRepository mediaRefs,
            SessionRepository sessions,
            RetrievedDocRepository retrievedDocs,
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierDetectionWriteRepository detections,
            GroundednessAssessmentRepository groundednessAssessments,
            FrustrationAssessmentRepository frustrationAssessments,
            CaseOpener caseOpener) {
        this.projects = projects;
        this.pipeline = pipeline;
        this.classifiers = classifiers;
        this.baselines = baselines;
        this.sampleData = sampleData;
        this.mediaStore = mediaStore;
        this.mediaRefs = mediaRefs;
        this.sessions = sessions;
        this.retrievedDocs = retrievedDocs;
        this.findings = findings;
        this.evidence = evidence;
        this.detections = detections;
        this.groundednessAssessments = groundednessAssessments;
        this.frustrationAssessments = frustrationAssessments;
        this.caseOpener = caseOpener;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProjectCreated(ProjectCreatedEvent event) {
        try {
            Project project = projects.findById(event.projectId()).orElse(null);
            if (project == null || !project.isSample()) return;
            seedShowcase(project.id());
        } catch (RuntimeException e) {
            log.warn(
                    Markers.OPS,
                    "sample project seed failed project={} (belt-and-braces only, "
                            + "ConnectGate's own isSample() short-circuit does not depend on this data existing)",
                    event.projectId(),
                    e);
        }
    }

    private void seedShowcase(String projectId) {
        for (String callSite : SampleShowcase.CALL_SITES) {
            pipeline.ensureCallSite(projectId, callSite);
        }

        // Idempotent, and must run BEFORE the lookup below that hangs findings off a real classifier.
        classifiers.seedBuiltIns(projectId);

        Map<String, ClassifierRow> classifierRows = classifiers.list(projectId).stream()
                .collect(Collectors.toMap(ClassifierRow::classifierKey, Function.identity()));
        String costDriftId = idOf(classifierRows.get("cost_drift"));
        String durationDriftId = idOf(classifierRows.get("duration_drift"));

        Instant now = Instant.now();
        long seed = projectId.hashCode();
        String exportPdfMediaId = mediaStore
                .put(projectId, SampleShowcase.minimalExportPdf(), "application/pdf")
                .id();
        String screenshotPngMediaId = mediaStore
                .put(projectId, SampleShowcase.minimalScreenshotPng(), "image/png")
                .id();

        SampleShowcase.Dataset dataset =
                SampleShowcase.generate(projectId, seed, now, exportPdfMediaId, screenshotPngMediaId);

        sessions.getOrCreateAll(dataset.sessions());
        sampleData.insertTraces(dataset.traces());
        sampleData.insertSpans(dataset.spans());
        sampleData.insertSpanPayloads(dataset.payloads());
        for (SampleShowcase.MediaAttach attachment : dataset.mediaAttachments()) {
            mediaRefs.insertAll(projectId, attachment.traceId(), attachment.spanId(), List.of(attachment.mediaId()));
        }
        retrievedDocs.insertAll(dataset.retrievedDocs());

        List<SampleShowcase.DriftStat> drift = dataset.driftStats();
        // Matches the design spec's drift array order.
        FindingRef classifyCostFinding =
                costDriftId == null ? null : seedMetricFinding(projectId, costDriftId, drift.get(0), true);
        if (costDriftId != null) {
            seedMetricFinding(projectId, costDriftId, drift.get(1), false);
        }
        if (durationDriftId != null) {
            seedMetricFinding(projectId, durationDriftId, drift.get(2), false);
        }
        FindingRef refundDurationFinding =
                durationDriftId == null ? null : seedMetricFinding(projectId, durationDriftId, drift.get(3), true);
        seedToolErrorFinding(projectId, drift.get(4), false);
        FindingRef escalationErrorFinding = seedToolErrorFinding(projectId, drift.get(5), true);

        // The live opener allocates MAX(seq)+1, so the seed takes 1..3 and real cases continue from 4.
        if (classifyCostFinding != null) {
            seedCaseA(projectId, drift.get(0), classifyCostFinding);
        }
        if (refundDurationFinding != null) {
            seedCase(projectId, 2L, CaseRow.Detector.METRIC_DRIFT, drift.get(3), refundDurationFinding);
        }
        seedCase(projectId, 3L, CaseRow.Detector.TOOL_ERROR, drift.get(5), escalationErrorFinding);

        // After the three cases above, so the live opener numbers these 4 and 5.
        ClassifierRow groundedness = classifierRows.get(BuiltInDetector.Kind.GROUNDEDNESS);
        if (groundedness != null) seedGroundedness(projectId, groundedness, dataset);
        ClassifierRow frustration = classifierRows.get(BuiltInDetector.Kind.FRUSTRATION);
        if (frustration != null) seedFrustration(projectId, frustration, dataset);
    }

    private static @Nullable String idOf(@Nullable ClassifierRow row) {
        return row == null ? null : row.id();
    }

    /**
     * {@code caseSubjectId} is NOT the finding's {@code subject_id}: for metric drift that is the
     * {@code metric_baseline} row, while the case subject is the cause key {@code MetricDriftSource} reads via
     * {@link FindingRow#nativeCauseKey()}.
     */
    private record FindingRef(String id, String caseSubjectId, String caseSubjectKind, String caseMetric) {}

    private FindingRef seedMetricFinding(
            String projectId, String classifierId, SampleShowcase.DriftStat stat, boolean openCase) {
        String now = Instant.now().toString();
        String baselineId = Ids.ulid();
        baselines.ensure(new MetricBaselineRow(
                baselineId,
                projectId,
                classifierId,
                stat.measure(),
                MetricBaselineRow.BucketKind.CALL_SITE,
                stat.callSiteId(),
                "armed",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                stat.onsetAt(),
                stat.nPost(),
                null,
                null,
                stat.lastSeenAt(),
                now,
                now));

        Direction direction = "up".equals(stat.direction()) ? Direction.UP : Direction.DOWN;
        String directionWord = MetricFindingEvidence.directionWord(stat.measure(), direction);
        String nativeCauseKey = stat.measure() + ":" + stat.callSiteId() + ":" + directionWord + ":" + stat.reference();
        String causeKey = baselineId + ":" + nativeCauseKey;
        double ratio = stat.ratio();
        String title = MetricFindingEvidence.title(stat.measure(), stat.callSiteId(), ratio, direction);
        String basis = metricBasis(stat, ratio);
        String payload = metricPayload(stat, ratio, direction, nativeCauseKey);

        String findingId = Ids.ulid();
        sampleData.insertFinding(new SampleDataRepository.Finding(
                findingId,
                projectId,
                stat.classifierKey(),
                causeKey,
                "metric_baseline",
                baselineId,
                stat.callSiteId(),
                stat.callSiteId(),
                stat.onsetAt(),
                stat.lastSeenAt(),
                title,
                basis,
                severityFor(ratio),
                stat.nPost(),
                payload,
                now,
                openCase ? FindingRow.TriageVerdict.POSITIVE : null,
                openCase ? "opened_case" : null,
                openCase ? "Traced against this call site's own recent history; the shift is real." : null,
                openCase ? now : null));

        seedFindingEvidence(projectId, findingId, stat, now);

        return new FindingRef(findingId, nativeCauseKey, CaseRow.SubjectKind.METRIC_BASELINE, stat.measure());
    }

    /**
     * Makes a seeded finding analysable: RCA refuses a run with no evidence, which failed every sample Run RCA under
     * {@code SUBJECT_NOT_FOUND}. Only tool_error writes {@code witness}.
     */
    private void seedFindingEvidence(String projectId, String findingId, SampleShowcase.DriftStat stat, String now) {
        sampleData.insertFindingEvidence(
                projectId, findingId, FindingEvidenceRow.Role.BASELINE, stat.baselineTraceIds(), now);
        sampleData.insertFindingEvidence(
                projectId, findingId, FindingEvidenceRow.Role.MEMBER, stat.memberTraceIds(), now);
        sampleData.insertFindingEvidence(
                projectId, findingId, FindingEvidenceRow.Role.WITNESS, stat.witnessTraceIds(), now);
    }

    private FindingRef seedToolErrorFinding(String projectId, SampleShowcase.DriftStat stat, boolean openCase) {
        String now = Instant.now().toString();
        Direction direction = "up".equals(stat.direction()) ? Direction.UP : Direction.DOWN;
        String movement = direction == Direction.UP ? "elevated" : "reduced";
        String title = ToolErrorEvidence.shortName(stat.subjectId()) + " showing " + movement + " error rates";
        String causeKey = "tool_error_rate:" + stat.subjectId() + ":" + stat.direction();
        String basis = String.format(
                Locale.ROOT,
                "Sustained change against this tool's own in-control rate: %s to %s over %,d calls since"
                        + " onset (%+.2fpp).",
                FindingTitle.pct(stat.meanPre()),
                FindingTitle.pct(stat.meanPost()),
                stat.nPost(),
                (stat.meanPost() - stat.meanPre()) * 100);
        String payload = toolErrorPayload(stat);

        String findingId = Ids.ulid();
        sampleData.insertFinding(new SampleDataRepository.Finding(
                findingId,
                projectId,
                "tool_error",
                causeKey,
                "tool",
                stat.subjectId(),
                stat.callSiteId(),
                stat.callSiteId(),
                stat.onsetAt(),
                stat.lastSeenAt(),
                title,
                basis,
                severityFor(1.0 + (stat.meanPost() - stat.meanPre()) * 10),
                stat.nPost(),
                payload,
                now,
                openCase ? FindingRow.TriageVerdict.POSITIVE : null,
                openCase ? "opened_case" : null,
                openCase ? "Traced against this tool's own in-control failure rate; the shift is real." : null,
                openCase ? now : null));

        seedFindingEvidence(projectId, findingId, stat, now);

        return new FindingRef(findingId, stat.subjectId(), CaseRow.SubjectKind.TOOL, ToolErrorEvidence.MEASURE);
    }

    private void seedCase(
            String projectId, long seq, String detector, SampleShowcase.DriftStat stat, FindingRef finding) {
        String now = Instant.now().toString();
        double ratio = stat.ratio();
        boolean isTool = CaseRow.SubjectKind.TOOL.equals(finding.caseSubjectKind());
        String title = isTool
                ? ToolErrorEvidence.shortName(finding.caseSubjectId()) + " showing elevated error rates"
                : MetricFindingEvidence.title(
                        stat.measure(),
                        stat.callSiteId(),
                        ratio,
                        "up".equals(stat.direction()) ? Direction.UP : Direction.DOWN);
        String basis = isTool ? toolErrorBasisShort(stat) : metricBasis(stat, ratio);
        sampleData.insertCase(new SampleDataRepository.SampleCase(
                Ids.ulid(),
                projectId,
                seq,
                detector,
                finding.caseSubjectKind(),
                finding.caseSubjectId(),
                // The label stays the call site; the subject id is a cause key nobody should be shown.
                stat.subjectLabel(),
                stat.callSiteId(),
                finding.caseMetric(),
                title,
                basis,
                caseSeverity(stat, isTool),
                stat.onsetAt(),
                stat.meanPost(),
                stat.meanPre(),
                // Tool-error deltas are PERCENTAGE POINTS on a case, as the live source writes them.
                isTool ? (stat.meanPost() - stat.meanPre()) * 100 : stat.meanPost() - stat.meanPre(),
                now,
                finding.id()));
    }

    /**
     * The live case source's severity, not the finding's: different scales, and seeding the finding's would make the
     * case jump when a real finding joins it.
     */
    private static double caseSeverity(SampleShowcase.DriftStat stat, boolean isTool) {
        if (isTool) {
            return ToolErrorEvidence.severityOf(criticalityOf(stat));
        }
        return Math.min(1, Math.abs(Math.log(Math.max(1.01, stat.ratio()))) / Math.log(3));
    }

    private void seedCaseA(String projectId, SampleShowcase.DriftStat stat, FindingRef finding) {
        String now = Instant.now().toString();
        double ratio = stat.ratio();
        String title = MetricFindingEvidence.title(stat.measure(), stat.callSiteId(), ratio, Direction.UP);
        String basis = metricBasis(stat, ratio);

        sampleData.insertCase(new SampleDataRepository.SampleCase(
                Ids.ulid(),
                projectId,
                1L,
                CaseRow.Detector.METRIC_DRIFT,
                finding.caseSubjectKind(),
                finding.caseSubjectId(),
                stat.subjectLabel(),
                stat.callSiteId(),
                finding.caseMetric(),
                title,
                basis,
                caseSeverity(stat, false),
                stat.onsetAt(),
                stat.meanPost(),
                stat.meanPre(),
                stat.meanPost() - stat.meanPre(),
                now,
                finding.id()));

        String rcaJobId = sampleData.insertDoneRcaJob(Ids.ulid(), projectId, now);

        String onsetDate = REPORT_DATE.format(Instant.parse(stat.onsetAt()));
        double preInputTokens = SampleShowcase.CLASSIFY_BASE_INPUT_TOKENS;
        double postInputTokens = preInputTokens * 1.95;

        List<RuledOutCheck> ruledOut = List.of(
                RuledOutCheck.ruledOut(
                        1,
                        "The model and its price did not change: classify_intent ran gpt-4o-mini at the same rate"
                                + " for the whole window."),
                RuledOutCheck.ruledOut(
                        2, "The traffic did not change: call volume grew smoothly, with no jump at " + onsetDate + "."),
                RuledOutCheck.ruledOut(
                        3,
                        "classify_intent's own prompt and configuration did not change; only its input grew, from"
                                + " about " + Math.round(preInputTokens) + " to about " + Math.round(postInputTokens)
                                + " tokens per call."));

        List<Cause> causes = List.of(new Cause(
                "The step before classify_intent started sending it the whole ticket thread",
                Cause.HIGH,
                "change",
                "code",
                "On " + onsetDate + ", assemble_ticket_context began including every earlier message in the"
                        + " ticket thread, not just the customer's latest one.",
                "classify_intent reads that context as its input, so it now pays for about twice as many input"
                        + " tokens per call. Its own prompt and model did not change.",
                "Send classify_intent only the latest message, or cap the thread history"
                        + " assemble_ticket_context forwards.",
                null,
                stat.sampleTraceIdsPost(),
                List.of(),
                stat.sampleTraceIdsPost().size(),
                null));

        String detailedReport = caseADetailedReport(stat, onsetDate, preInputTokens, postInputTokens, ratio);

        sampleData.insertCompletedRcaReport(new SampleDataRepository.SampleRcaReport(
                Ids.ulid(),
                projectId,
                rcaJobId,
                finding.caseSubjectKind(),
                finding.caseSubjectId(),
                stat.subjectLabel(),
                stat.callSiteId(),
                finding.caseMetric(),
                Instant.now().minus(SampleShowcase.WINDOW_DAYS, ChronoUnit.DAYS).toString(),
                stat.onsetAt(),
                now,
                stat.meanPost(),
                stat.meanPre(),
                stat.meanPost() - stat.meanPre(),
                RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                String.format(
                        Locale.ROOT,
                        "classify_intent costs %.2f× more per call since %s because the step before it started"
                                + " sending it the whole ticket thread.",
                        ratio,
                        onsetDate),
                writeJson(ruledOut),
                writeJson(causes),
                detailedReport,
                RcaReportRow.Engine.AGENTIC,
                Instant.now().minusSeconds(600).toString(),
                now,
                finding.id()));
    }

    private String caseADetailedReport(
            SampleShowcase.DriftStat stat,
            String onsetDate,
            double preInputTokens,
            double postInputTokens,
            double ratio) {
        String traceCitations = String.join(
                ", ",
                stat.sampleTraceIdsPost()
                        .subList(0, Math.min(3, stat.sampleTraceIdsPost().size())));
        return "## Cost drift on `classify_intent`\n\n"
                + String.format(
                        Locale.ROOT,
                        "Across the %,d calls before %s, `classify_intent` averaged $%.6f per call. Across"
                                + " the %,d calls from %s onward it averaged $%.6f — a %.2f× increase that has"
                                + " held for the rest of the window rather than reverting.\n\n",
                        stat.nPre(),
                        onsetDate,
                        stat.meanPre(),
                        stat.nPost(),
                        onsetDate,
                        stat.meanPost(),
                        ratio)
                + "### What was ruled out\n\n"
                + "The model and price-book version behind `classify_intent` did not change across the"
                + " window — every call before and after " + onsetDate + " priced against `gpt-4o-mini` at"
                + " the same rate, so a repricing or a silent model swap cannot explain the jump. Traffic"
                + " volume was checked next: the project's overall call count does grow across the 14-day"
                + " window (expected — recent days carry more traffic than older ones), but that growth is"
                + " smooth day over day, with no discontinuity coinciding with " + onsetDate + ". A volume"
                + " spike would explain more calls, not a higher cost per call, and there is no matching"
                + " spike here regardless.\n\n"
                + "Finally, `classify_intent`'s own prompt and configuration were diffed against its history"
                + " and found unchanged — no new instructions, no added few-shot examples, nothing in the"
                + " call site's own definition moved.\n\n"
                + "### What did move\n\n"
                + String.format(
                        Locale.ROOT,
                        "`classify_intent`'s own input token count roughly doubled at the same moment: from"
                                + " roughly %d tokens per call to roughly %d, while its output stayed flat"
                                + " (it still emits one short intent label). A prompt that grows without the"
                                + " model or the prompt template itself changing means the call site is"
                                + " receiving more — the growth is upstream of it.\n\n",
                        Math.round(preInputTokens),
                        Math.round(postInputTokens))
                + "### Root cause\n\n"
                + "`assemble_ticket_context` is the span immediately preceding `classify_intent` in every one"
                + " of these traces, and it is the thing that builds `classify_intent`'s input. Reading its"
                + " own output across the same window shows the change directly: before " + onsetDate
                + " it assembled the current message plus a single prior note; from " + onsetDate + " onward"
                + " it started including several prior thread messages verbatim on the same tickets. That is"
                + " a genuine behavior change in context assembly, not a pricing change, not a traffic"
                + " change, and not an edit to `classify_intent` itself — `classify_intent` is simply paying"
                + " to read what it was handed, and what it was handed got bigger.\n\n"
                + "Representative traces from after the shift: " + traceCitations + ".\n\n"
                + "### Verdict\n\n"
                + "**Cause identified, high confidence.** The fix belongs in `assemble_ticket_context`'s"
                + " context assembly: trim to the current message, or a bounded recent window, rather than the"
                + " full thread history. `classify_intent` never changed.";
    }

    /**
     * Chosen so {@link ToolErrorEvidence#severityOf} inverts it to {@link #severityFor}'s answer, keeping finding and
     * case in agreement.
     */
    private static double criticalityOf(SampleShowcase.DriftStat stat) {
        double severity = severityFor(1.0 + (stat.meanPost() - stat.meanPre()) * 10);
        return 60.0 * severity / (1.0 - severity);
    }

    private static double severityFor(double ratio) {
        double magnitude = Math.abs(ratio - 1.0);
        return Math.max(0.35, Math.min(0.85, 0.35 + magnitude * 0.35));
    }

    private static String metricBasis(SampleShowcase.DriftStat stat, double ratio) {
        boolean isCost = "cost".equals(stat.measure());
        String formattedPre = isCost
                ? String.format(Locale.ROOT, "$%.6f", stat.meanPre())
                : String.format(Locale.ROOT, "%.0fms", stat.meanPre());
        String formattedPost = isCost
                ? String.format(Locale.ROOT, "$%.6f", stat.meanPost())
                : String.format(Locale.ROOT, "%.0fms", stat.meanPost());
        return String.format(
                Locale.ROOT,
                "%s went from %s to %s versus its own recent window (%s reference), sampled over %,d calls"
                        + " since onset (%.2f× %s).",
                isCost ? "Cost per call" : "Turn duration",
                formattedPre,
                formattedPost,
                stat.reference(),
                stat.nPost(),
                ratio >= 1 ? ratio : 1 / ratio,
                "up".equals(stat.direction())
                        ? (isCost ? "more expensive" : "slower")
                        : (isCost ? "cheaper" : "faster"));
    }

    private static String toolErrorBasisShort(SampleShowcase.DriftStat stat) {
        return String.format(
                Locale.ROOT,
                "Failure rate moved from %s to %s versus this tool's own in-control level (%+.2fpp).",
                FindingTitle.pct(stat.meanPre()),
                FindingTitle.pct(stat.meanPost()),
                (stat.meanPost() - stat.meanPre()) * 100);
    }

    private String metricPayload(
            SampleShowcase.DriftStat stat, double ratio, Direction direction, String nativeCauseKey) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("kind", "call_site");
        bucket.put("key", stat.callSiteId());
        Map<String, Object> quantiles = new LinkedHashMap<>();
        quantiles.put("p50", List.of(round(stat.meanPre()), round(stat.meanPost())));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("opened_at", stat.onsetAt());
        window.put("closed_at", stat.lastSeenAt());
        window.put("kind", "count");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("measure", stat.measure());
        root.put("bucket", bucket);
        root.put("reference", stat.reference());
        root.put("w1_log", round(Math.log(Math.max(1.01, ratio))));
        root.put("ratio", round(ratio));
        root.put("direction", direction.wire());
        root.put("n_ref", stat.nPre());
        root.put("n_cur", stat.nPost());
        root.put("floor", 0.139);
        root.put("quantiles", quantiles);
        root.put("window", window);
        root.put("cause_kind", FindingRow.Cause.DISTRIBUTION_SHIFT);
        root.put("native_cause_key", nativeCauseKey);
        return writeJson(root);
    }

    private String toolErrorPayload(SampleShowcase.DriftStat stat) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("kind", "tool");
        bucket.put("key", stat.subjectId());
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("ref", round(stat.meanPre()));
        rate.put("cur", round(stat.meanPost()));
        Map<String, Object> failures = new LinkedHashMap<>();
        failures.put("cur", Math.round(stat.nPost() * stat.meanPost()));
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("opened_at", stat.onsetAt());
        window.put("closed_at", stat.lastSeenAt());
        window.put("kind", "recomputed");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("measure", "tool_error_rate");
        root.put("bucket", bucket);
        root.put("direction", stat.direction());
        root.put("statistic", 6.4);
        root.put("threshold", 6.0);
        root.put("criticality", round(criticalityOf(stat)));
        root.put("effect_size", round(stat.meanPost() - stat.meanPre()));
        root.put("delta_pp", round((stat.meanPost() - stat.meanPre()) * 100));
        root.put("counts_basis", "onset");
        root.put("rate", rate);
        root.put("n_ref", stat.nPre());
        root.put("n_cur", stat.nPost());
        root.put("failures", failures);
        root.put("onset_at", stat.onsetAt());
        root.put("window", window);
        root.put("cause_kind", FindingRow.Cause.RATE_SHIFT);
        return writeJson(root);
    }

    /**
     * Through the same writers the live rate service and triage use. The classifier stays disabled as seeded, so no
     * sweep rescores this data.
     */
    private void seedGroundedness(String projectId, ClassifierRow classifier, SampleShowcase.Dataset dataset) {
        sampleData.setCallSiteShape(projectId, SampleShowcase.GENERATE, "rag_answer");
        GroundednessConfig config = GroundednessConfig.of(mapper, classifier.configJson());
        String scorerVersion = config.scorerVersion();
        for (SampleShowcase.ScoredAnswer answer : dataset.answers()) {
            groundednessAssessments.insert(new GroundednessAssessmentRepository.Assessment(
                    Ids.ulid(),
                    projectId,
                    classifier.id(),
                    answer.sessionId(),
                    answer.traceId(),
                    answer.spanId(),
                    SampleShowcase.GENERATE,
                    answer.unsupported(),
                    answer.flagged() != null,
                    scorerVersion,
                    answer.startedAt().toString()));
            if (answer.flagged() == null) continue;
            detections.insert(
                    Ids.ulid(),
                    classifier.detector(),
                    projectId,
                    classifier.id(),
                    classifier.classifierKey(),
                    null,
                    answer.sessionId(),
                    answer.traceId(),
                    answer.spanId(),
                    Detection.Severity.WARN,
                    Detection.Confidence.HIGH,
                    groundednessDetectionEvidence(answer));
        }

        SampleShowcase.RateStat stat = dataset.groundedness();
        ToolErrorDetector.Decision d = decision(stat, config.engine());
        String callSite = stat.callSiteId();
        String findingId = seedRateFinding(
                projectId,
                classifier,
                stat,
                d,
                CauseKey.groundedness(classifier.id(), callSite),
                FindingRow.Cause.GROUNDEDNESS_RATE,
                GroundednessEvidence.payload(mapper, callSite, d, stat.baselineFailures(), config),
                "Read the flagged answers against the articles they were written from. From "
                        + REPORT_DATE.format(stat.onset())
                        + " the replies add promises no article makes, like refunds within 24 hours and"
                        + " exports with no row limit. The rise is real.");
        if (findingId != null) caseOpener.ensureCaseFor(projectId, findingId, null);
    }

    /** Scored with no model call; the assessment rows carry the request as it would have been sent. */
    private void seedFrustration(String projectId, ClassifierRow classifier, SampleShowcase.Dataset dataset) {
        FrustrationConfig config = FrustrationConfig.of(mapper, classifier.configJson());
        String scorerVersion = config.scorerVersion();
        String questions = writeJson(JevFrustrationQuestion.questions());
        for (SampleShowcase.ScoredTurn turn : dataset.turns()) {
            frustrationAssessments.insert(new FrustrationAssessmentRepository.Assessment(
                    Ids.ulid(),
                    projectId,
                    classifier.id(),
                    turn.traceId(),
                    turn.rootSpanId(),
                    turn.conversationId(),
                    SampleShowcase.ASSEMBLE,
                    turn.startedAt(),
                    turn.frustrated(),
                    scorerVersion,
                    SAMPLE_DECISION_PROVIDER,
                    SAMPLE_DECISION_MODEL,
                    "{\"state\":" + writeJson(turn.state()) + ",\"questions\":" + questions + "}",
                    frustrationResponse(turn.score()),
                    420 + turn.state().currentUserMessage().length() / 4,
                    new BigDecimal("0.00042000"),
                    640 + (int) (turn.score() * 300)));
            if (!turn.frustrated()) continue;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("model", SAMPLE_DECISION_MODEL);
            ev.put("provider", SAMPLE_DECISION_PROVIDER);
            ev.put("score", Math.round(turn.score() * 1000) / 1000.0);
            ev.put("threshold", config.threshold());
            ev.put("scorer_version", scorerVersion);
            ev.put("k_turn", 3);
            ev.put("call_site_id", SampleShowcase.ASSEMBLE);
            detections.insert(
                    Ids.ulid(),
                    classifier.detector(),
                    projectId,
                    classifier.id(),
                    classifier.classifierKey(),
                    null,
                    turn.conversationId(),
                    turn.traceId(),
                    turn.rootSpanId(),
                    Detection.Severity.WARN,
                    Detection.Confidence.HIGH,
                    writeJson(ev));
        }

        SampleShowcase.RateStat stat = dataset.frustration();
        ToolErrorDetector.Decision d = decision(stat, config.engine());
        String callSite = stat.callSiteId();
        String findingId = seedRateFinding(
                projectId,
                classifier,
                stat,
                d,
                CauseKey.frustration(classifier.id(), callSite),
                FindingRow.Cause.FRUSTRATION_RATE,
                FrustrationEvidence.payload(mapper, callSite, d, stat.baselineFailures(), config),
                FrustrationEvidence.SUMMARY);
        if (findingId != null) caseOpener.ensureCaseFor(projectId, findingId, null);
    }

    private static final String SAMPLE_DECISION_PROVIDER = "TYPESAFE";

    private static final String SAMPLE_DECISION_MODEL = "jev-latest";

    /** Null when a ruled finding on the same cause already covered it. */
    private @Nullable String seedRateFinding(
            String projectId,
            ClassifierRow classifier,
            SampleShowcase.RateStat stat,
            ToolErrorDetector.Decision d,
            String causeKey,
            String causeKind,
            String payload,
            String summary) {
        String now = Instant.now().toString();
        String eventAt = stat.lastBucket().toString();
        FindingRepository.Recorded recorded = findings.recordRecomputedRate(
                Ids.ulid(),
                projectId,
                classifier.classifierKey(),
                causeKey,
                causeKind,
                stat.callSiteId(),
                FindingRow.SubjectKind.CLASSIFIER,
                classifier.id(),
                classifier.name(),
                null,
                d.failuresSinceOnset(),
                stat.callSiteId(),
                d.onsetAt(),
                payload,
                eventAt,
                stat.lastBucket().minus(Duration.ofHours(6)).toString(),
                now);
        if (recorded == null) return null;
        evidence.record(projectId, recorded.findingId(), FindingEvidenceRow.Role.MEMBER, stat.members(), now);
        evidence.record(projectId, recorded.findingId(), FindingEvidenceRow.Role.WITNESS, stat.witnesses(), now);
        findings.recordTriage(projectId, recorded.findingId(), FindingRow.TriageVerdict.POSITIVE, summary, null, now);
        return recorded.findingId();
    }

    private static ToolErrorDetector.Decision decision(SampleShowcase.RateStat stat, ToolErrorConfig engine) {
        double p0 = (stat.baselineFailures() + 0.5) / (stat.baselineTrials() + 1.0);
        double p1 = ToolErrorDetector.shiftedUp(p0, engine);
        long n = stat.trialsSinceOnset();
        long f = stat.failuresSinceOnset();
        double s = Math.max(0, f * Math.log(p1 / p0) + (n - f) * Math.log((1 - p1) / (1 - p0)));
        double current = n == 0 ? p0 : (double) f / n;
        return new ToolErrorDetector.Decision(
                true,
                ToolErrorDetector.Direction.UP,
                s,
                engine.decisionIntervalFor(p0),
                ToolErrorDetector.criticality(s),
                p0,
                current,
                (current - p0) * 100.0,
                ToolErrorDetector.cohensH(p0, current),
                n,
                f,
                stat.baselineTrials(),
                stat.onset().toString(),
                null);
    }

    private String groundednessDetectionEvidence(SampleShowcase.ScoredAnswer answer) {
        SampleShowcase.FlaggedSentence flagged = Objects.requireNonNull(answer.flagged());
        Map<String, Object> sentence = new LinkedHashMap<>();
        sentence.put("start", flagged.start());
        sentence.put("end", flagged.end());
        sentence.put("unsupported", Math.round(answer.unsupported() * 1000) / 1000.0);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("head", "groundedness");
        ev.put("unsupported", Math.round(answer.unsupported() * 1000) / 1000.0);
        ev.put("conflict", Math.round(answer.conflict() * 1000) / 1000.0);
        ev.put("premise_had_evidence", true);
        ev.put("flagged_sentences", List.of(sentence));
        ev.put("claim", flagged.text());
        return writeJson(ev);
    }

    private String frustrationResponse(double score) {
        double other = (1 - score) * 0.2;
        Map<String, Object> probabilities = new LinkedHashMap<>();
        probabilities.put(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT, round(score));
        probabilities.put(JevFrustrationQuestion.UNHAPPY_OTHER_CAUSE, round(other));
        probabilities.put(JevFrustrationQuestion.NEUTRAL_OR_POSITIVE, round(1 - score - other));
        return writeJson(
                Map.of("answers", Map.of(JevFrustrationQuestion.NAME, Map.of("probabilities", probabilities))));
    }

    private String writeJson(Object value) {
        return mapper.valueToTree(value).toString();
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
