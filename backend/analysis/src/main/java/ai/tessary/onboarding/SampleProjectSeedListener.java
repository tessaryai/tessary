// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import ai.tessary.cases.CaseRow;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.obs.Markers;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.rca.RcaDtos.Hypothesis;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.storage.MediaRefRepository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectCreatedEvent;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Seeds the sample project's demo data (#1227, showcase #1230) — enough for {@link
 * OnboardingController#progress} to compute {@link OnboardingStage#CASE} without the real detection
 * pipeline ever running, so a project a user reaches by clicking "Start with a sample project" looks
 * like a project that has been live for weeks, not an empty shell with a banner on it.
 *
 * <p>Fires on the same {@link ProjectCreatedEvent} every other per-project seeder does ({@code
 * ClassifierSeedListener}, {@code CaseAlertSeedListener}), and re-reads the project row to check
 * {@link Project#isSample()} before doing anything — the event carries only a project id, and this
 * is the one listener in the catalog that must do NOTHING for the other 99% of projects it fires
 * for. Idempotent the same way the rest of the catalog is: {@code AFTER_COMMIT}, failures logged and
 * swallowed (the project already exists either way; a half-seeded demo project is a worse launch
 * screen than an empty one, never a reason to fail the create).
 *
 * <h2>Deviation from the plan this issue shipped against</h2>
 *
 * <p>The plan located this class at {@code backend/tenancy/.../tenant/SampleProjectSeeder.java}.
 * That is not buildable: every table this class writes to beyond {@code project} itself —
 * {@code classifier}, {@code metric_baseline}, {@code span}/{@code span_payload}/{@code trace},
 * {@code finding}, {@code eval_case}, {@code rca_report} — is owned by {@code substrate}, {@code
 * product}, or {@code analysis}, none of which {@code tenancy} may depend on (it sits BELOW all
 * three in the reactor: {@code shared, contract, core, test-support, tenancy, substrate, product,
 * llm-runtime, analysis, ...}). This class lives in {@code analysis} instead, the same module (and
 * the same event-listener SPI seam — {@link ProjectCreatedEvent}) {@code ClassifierSeedListener} and
 * {@code CaseAlertSeedListener} already use to provision a new project's starting state without
 * {@code tenant/} having to know any of them exist.
 *
 * <p>A second deviation: {@code job}/{@code finding}/{@code eval_case}/{@code rca_report} rows are
 * written with raw SQL ({@link SampleDataRepository}) rather than through {@code
 * FindingRepository#recordFiring}/{@code CaseRepository#open}/{@code RcaReportRepository#complete}.
 * Those methods model the LIVE detection
 * state machine — severity computed from a real deviation, evidence assembled from real spans,
 * a case opened by a real classifier run. Replaying that machinery to fabricate a finished, static
 * end state for a demo is the wrong tool: it would mean constructing the same domain objects
 * (a real {@code CaseDetection}, a real evidence-refs blob) the live pipeline builds from actual
 * measurements, only to feed them synthetic numbers anyway. A direct insert matching the schema's
 * own NOT NULL/CHECK constraints says plainly "this row is fabricated," which a call through the
 * business-logic layer would not.
 *
 * <h2>The showcase dataset (#1230)</h2>
 *
 * <p>{@link SampleShowcase} generates ~500+ "AI customer-support agent" traces spread over a 14-day
 * window and threaded through seven call sites; six of them carry a real cost/duration/error-rate
 * drift baked into the generated span numbers themselves (visible when aggregated by day and call
 * site), and three of those six are elevated into an open {@code eval_case} — one ({@code
 * classify_intent} cost drift) with a full agentic RCA report citing this same generated data. The
 * substrate volume is inserted as batched multi-row SQL ({@link SampleDataRepository#insertTraces}
 * and friends) rather than through the substrate repositories' single-row upsert methods, which
 * exist for live ingest's replay semantics this one-shot seed does not need.
 *
 * <h2>Every seeded row has to be one the LIVE pipeline would have written</h2>
 *
 * <p>Not a style preference — a correctness constraint, and the one the first cut of this class got
 * wrong. {@code CaseWorker} sweeps this project like any other within minutes of the seed, and
 * {@code CaseLedger#apply} reconciles the case table against what the detectors currently believe:
 * a seeded case whose {@link ai.tessary.cases.CaseKey} does not match the key
 * {@code MetricDriftSource}/{@code ToolErrorCaseSource} cut from the SAME finding is not recognised,
 * so it is resolved as {@code RECOVERED} and a duplicate opens beside it. The key is
 * {@code (detector, subject_kind, subject_id, metric)} and {@code subject_id} is the finding's
 * {@link FindingRow#nativeCauseKey()} — never the {@code metric_baseline} id, which is only the
 * uniqueness scope prefixed onto {@code cause_key}. {@link #seedCase} derives both halves from the
 * finding it is paired with for exactly this reason.
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
    private final ObjectMapper mapper = new ObjectMapper();

    public SampleProjectSeedListener(
            ProjectRepository projects,
            PipelineService pipeline,
            ClassifierService classifiers,
            MetricBaselineRepository baselines,
            SampleDataRepository sampleData,
            MediaStore mediaStore,
            MediaRefRepository mediaRefs) {
        this.projects = projects;
        this.pipeline = pipeline;
        this.classifiers = classifiers;
        this.baselines = baselines;
        this.sampleData = sampleData;
        this.mediaStore = mediaStore;
        this.mediaRefs = mediaRefs;
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

    // ---- showcase (#1230) ------------------------------------------------------------------

    private void seedShowcase(String projectId) {
        for (String callSite : SampleShowcase.CALL_SITES) {
            pipeline.ensureCallSite(projectId, callSite);
        }

        // The built-in catalog is idempotent (insert-if-missing), so calling it here is safe
        // regardless of whether ClassifierSeedListener has already run for this same event — and it
        // has to run BEFORE the lookup below, which is what hangs the findings off a real classifier.
        classifiers.seedBuiltIns(projectId);

        Map<String, String> classifierIds = classifiers.list(projectId).stream()
                .collect(Collectors.toMap(ClassifierRow::classifierKey, ClassifierRow::id, (a, b) -> a));
        String costDriftId = classifierIds.get("cost_drift");
        String durationDriftId = classifierIds.get("duration_drift");

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

        sampleData.insertTraces(dataset.traces());
        sampleData.insertSpans(dataset.spans());
        sampleData.insertSpanPayloads(dataset.payloads());
        for (SampleShowcase.MediaAttach attachment : dataset.mediaAttachments()) {
            mediaRefs.insertAll(projectId, attachment.traceId(), attachment.spanId(), List.of(attachment.mediaId()));
        }

        List<SampleShowcase.DriftStat> drift = dataset.driftStats();
        // Order matches the design spec's drift array exactly: [0] classify_intent cost (Case A),
        // [1] generate_response cost, [2] kb_search duration, [3] refund_api duration (Case B),
        // [4] refund_api tool error, [5] ticket_escalation tool error (Case C).
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

        // Case numbers are allocated MAX(seq)+1 by the live opener, so the seed takes 1..3 and the
        // first case a real detector opens on this project continues from 4.
        if (classifyCostFinding != null) {
            seedCaseA(projectId, drift.get(0), classifyCostFinding);
        }
        if (refundDurationFinding != null) {
            seedCase(projectId, 2L, CaseRow.Detector.METRIC_DRIFT, drift.get(3), refundDurationFinding);
        }
        seedCase(projectId, 3L, CaseRow.Detector.TOOL_ERROR, drift.get(5), escalationErrorFinding);
    }

    /**
     * A seeded finding's id together with the two halves of the {@link ai.tessary.cases.CaseKey}
     * the LIVE case source would cut from it — see this class's header for why that identity has to
     * match exactly.
     *
     * <p>{@code caseSubjectId} is deliberately NOT the finding's own {@code subject_id}. For metric
     * drift the finding's subject is the {@code metric_baseline} row (the uniqueness scope), while the
     * case's subject is the classifier's own cause key, which is what {@code MetricDriftSource} reads
     * back through {@link FindingRow#nativeCauseKey()}. For tool error the two coincide — the bucket
     * key {@code tool:<name>} — and the field is filled with the same string for both so a reader
     * never has to know which case they are looking at.
     */
    private record FindingRef(String id, String caseSubjectId, String caseSubjectKind, String caseMetric) {}

    /**
     * A metric-baseline-backed finding (cost_drift or duration_drift): mints the {@code
     * metric_baseline} row the finding's {@code subject_id} points at, then the finding itself —
     * triaged and case-linked when {@code openCase}, open and untriaged otherwise.
     */
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
     * The evidence rows behind a seeded finding, in the roles {@code RcaAnalysisService.sides} reads.
     *
     * <p>This is what makes a seeded finding analysable rather than merely displayable. RCA resolves
     * a finding's evidence into a baseline and a flagged side and refuses the run when both are
     * empty, so before this every "Run RCA" press in the sample project failed — and failed under
     * {@code SUBJECT_NOT_FOUND}, which reads as "no such finding" when the finding was there all
     * along and its evidence was not.
     *
     * <p>{@code witness} is written only where the classifier draws one: {@code tool_error} claims a
     * fraction, so its members are every call in the window and the witnesses are the failing subset.
     * A metric drift makes no such split and its members are themselves the flagged side.
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
                // The subject LABEL is what the triage list reads out, and it stays the call site — the
                // subject ID above is a cause key nobody should ever be shown.
                stat.subjectLabel(),
                stat.callSiteId(),
                finding.caseMetric(),
                title,
                basis,
                caseSeverity(stat, isTool),
                stat.onsetAt(),
                stat.meanPost(),
                stat.meanPre(),
                // Tool-error rates are carried as PERCENTAGE POINTS on a case (`delta_pp` in the
                // evidence blob, which is what the live source passes), while a metric drift's delta
                // is in the measure's own raw unit. Writing the tool delta as a fraction here made the
                // seeded row disagree with the sweep's own refresh of it by a factor of 100.
                isTool ? (stat.meanPost() - stat.meanPre()) * 100 : stat.meanPost() - stat.meanPre(),
                now,
                finding.id()));
    }

    /**
     * The severity the live case source would compute from this finding's evidence, not the finding's
     * own. The two are different scales on purpose — a finding's severity grades the claim, a case's
     * only orders the triage list — and seeding the finding's value here would make every seeded case
     * jump in the ranking the first time {@code CaseWorker} refreshed it.
     */
    private static double caseSeverity(SampleShowcase.DriftStat stat, boolean isTool) {
        if (isTool) {
            // ToolErrorEvidence.severityOf, fed the criticality this seeder writes into the payload.
            return ToolErrorEvidence.severityOf(criticalityOf(stat));
        }
        // MetricDriftSource.severity: |W1 on logs| against a ln(3) saturation point.
        return Math.min(1, Math.abs(Math.log(Math.max(1.01, stat.ratio()))) / Math.log(3));
    }

    /** Case A: {@code classify_intent} cost drift, with a full agentic RCA report tracing the shift
     *  back to {@code assemble_ticket_context}'s own generated data. */
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
                RuledOutCheck.assessed(
                        "model_change",
                        RuledOutCheck.Assessment.RULED_OUT,
                        "classify_intent ran gpt-4o-mini for the whole window, priced against the same"
                                + " price-book version throughout — no model or rate change lines up with the"
                                + " " + onsetDate + " step.",
                        "model=gpt-4o-mini, unchanged"),
                RuledOutCheck.assessed(
                        "traffic_shift",
                        RuledOutCheck.Assessment.RULED_OUT,
                        "Call volume grew smoothly across the whole 14-day window (more traffic on recent"
                                + " days, as expected for a live project) with no discontinuity at " + onsetDate
                                + " — the cost step has no matching jump in call count.",
                        "no volume discontinuity at onset"),
                RuledOutCheck.assessed(
                        "definition_change",
                        RuledOutCheck.Assessment.RULED_OUT,
                        "classify_intent's own prompt and configuration did not change. What did move: its"
                                + " input tokens, from roughly " + Math.round(preInputTokens) + " to roughly "
                                + Math.round(postInputTokens) + " per call, starting exactly at the same"
                                + " point the cost did — a clue pointing upstream rather than at this call"
                                + " site itself.",
                        "input tokens ~" + Math.round(preInputTokens) + " -> ~" + Math.round(postInputTokens)));

        List<Hypothesis> hypotheses = List.of(new Hypothesis(
                "Upstream context-assembly drift in assemble_ticket_context",
                "high",
                "assemble_ticket_context is the immediately preceding span in every one of these traces."
                        + " Its own output started including the full prior ticket-thread history — several"
                        + " prior messages restated verbatim — instead of just the current message, right at"
                        + " " + onsetDate + ". classify_intent reads that assembled context as its input,"
                        + " so its own token count roughly doubled without its prompt or model changing at"
                        + " all.",
                stat.sampleTraceIdsPost()));

        String detailedReport = caseADetailedReport(stat, onsetDate, preInputTokens, postInputTokens, ratio);

        try {
            sampleData.insertCompletedRcaReport(new SampleDataRepository.SampleRcaReport(
                    Ids.ulid(),
                    projectId,
                    rcaJobId,
                    finding.caseSubjectKind(),
                    finding.caseSubjectId(),
                    stat.subjectLabel(),
                    stat.callSiteId(),
                    finding.caseMetric(),
                    Instant.now()
                            .minus(SampleShowcase.WINDOW_DAYS, ChronoUnit.DAYS)
                            .toString(),
                    stat.onsetAt(),
                    now,
                    stat.meanPost(),
                    stat.meanPre(),
                    stat.meanPost() - stat.meanPre(),
                    RcaReportRow.Verdict.BEHAVIOR_CHANGE,
                    String.format(
                            Locale.ROOT,
                            "classify_intent's cost per call rose %.2f× on %s, driven by input tokens roughly"
                                    + " doubling while output stayed flat. Traced upstream: assemble_ticket_context"
                                    + " started forwarding the full ticket-thread history instead of only the"
                                    + " current message, which is what classify_intent actually pays to read.",
                            ratio,
                            onsetDate),
                    mapper.writeValueAsString(ruledOut),
                    mapper.writeValueAsString(hypotheses),
                    detailedReport,
                    RcaReportRow.Engine.AGENTIC,
                    Instant.now().minusSeconds(600).toString(),
                    now,
                    finding.id()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("case A RCA report serialization failed", e);
        }
    }

    private String caseADetailedReport(
            SampleShowcase.DriftStat stat,
            String onsetDate,
            double preInputTokens,
            double postInputTokens,
            double ratio) {
        String traceCitations = stat.sampleTraceIdsPost().isEmpty()
                ? "no traces sampled"
                : String.join(
                        ", ",
                        stat.sampleTraceIdsPost()
                                .subList(
                                        0, Math.min(3, stat.sampleTraceIdsPost().size())));
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
                + "**Behavior change.** Fix belongs in `assemble_ticket_context`'s context-assembly logic —"
                + " trim to the current message (or a bounded recent window) rather than the full thread"
                + " history — not in `classify_intent`, which never changed.";
    }

    /**
     * The criticality this seeder writes into a tool-error finding's evidence, chosen so that
     * {@link ToolErrorEvidence#severityOf} inverts it back to {@link #severityFor}'s answer — the
     * finding and the case it opens then agree instead of ranking the same event two ways.
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

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sample showcase payload serialization failed", e);
        }
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
