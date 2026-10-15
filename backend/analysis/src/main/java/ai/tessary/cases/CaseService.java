// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseEventView;
import ai.tessary.cases.CaseDtos.CaseFindingView;
import ai.tessary.cases.CaseDtos.CaseRulingView;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.cases.CaseDtos.CitationView;
import ai.tessary.cases.CaseDtos.TriageView;
import ai.tessary.cases.CaseDtos.WatchingView;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.detector.groundedness.GroundednessAnswerClearer;
import ai.tessary.classifier.detector.groundedness.GroundednessDetailService;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.finding.BehaviorTriageSource;
import ai.tessary.classifier.finding.BehaviorTriageVerdict;
import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.frustration.FrustrationDetailService;
import ai.tessary.classifier.frustration.FrustrationRateRepository;
import ai.tessary.classifier.frustration.FrustrationSessionClearer;
import ai.tessary.classifier.malformed.MalformedOutputDetailService;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.open.errors.CaseError;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRepository.CaseLead;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.rca.RcaReportService;
import ai.tessary.rca.RcaTriggerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseService {

    private static final Duration HISTORY_WINDOW = Duration.ofDays(7);

    private static final String CLOSED_NOTE = "Closed.";

    private static final Duration COVERAGE_WINDOW = Duration.ofDays(1);

    private static final ObjectReader CITATIONS =
            new ObjectMapper().readerFor(new TypeReference<List<BehaviorTriageVerdict.Citation>>() {});

    private final CaseRepository cases;
    private final CaseLedger ledger;
    private final CaseEventRepository events;
    private final CaseSubstrateRepository substrate;
    private final CaseExemplars exemplars;
    private final RcaReportRepository rcaReports;
    private final RcaReportService rcaReportViews;
    /** Takes a finding id and nothing else: the case surface is where a person decides. */
    private final RcaTriggerService rcaTrigger;

    private final FindingRepository findings;
    private final FindingEvidenceRepository findingEvidence;
    private final BehaviorTriageSource behaviorTriage;

    private final ClassifierService classifiers;
    private final ToolErrorStateRepository toolErrorStates;
    /** Shares tool_error's CUSUM engine, so it needs the same accumulator reset when a human closes the case. */
    private final MalformedOutputRateRepository malformedOutputRates;
    private final FrustrationRateRepository frustrationRates;
    private final FrustrationSessionClearer frustrationSessions;
    private final GroundednessRateRepository groundednessRates;
    private final GroundednessAnswerClearer groundednessAnswers;
    private final MalformedOutputDetailService malformedOutputDetail;
    private final SecretLeakDetailService secretLeakDetail;

    private final FrustrationDetailService frustrationDetail;

    private final GroundednessDetailService groundednessDetail;

    private final FindingAdapters adapters;

    public CaseService(
            CaseRepository cases,
            CaseLedger ledger,
            CaseEventRepository events,
            CaseSubstrateRepository substrate,
            CaseExemplars exemplars,
            RcaReportRepository rcaReports,
            RcaReportService rcaReportViews,
            RcaTriggerService rcaTrigger,
            FindingRepository findings,
            FindingEvidenceRepository findingEvidence,
            BehaviorTriageSource behaviorTriage,
            ClassifierService classifiers,
            ToolErrorStateRepository toolErrorStates,
            MalformedOutputRateRepository malformedOutputRates,
            FrustrationRateRepository frustrationRates,
            FrustrationSessionClearer frustrationSessions,
            GroundednessRateRepository groundednessRates,
            GroundednessAnswerClearer groundednessAnswers,
            MalformedOutputDetailService malformedOutputDetail,
            SecretLeakDetailService secretLeakDetail,
            FrustrationDetailService frustrationDetail,
            GroundednessDetailService groundednessDetail,
            FindingAdapters adapters) {
        this.cases = cases;
        this.ledger = ledger;
        this.events = events;
        this.substrate = substrate;
        this.exemplars = exemplars;
        this.rcaReports = rcaReports;
        this.rcaReportViews = rcaReportViews;
        this.rcaTrigger = rcaTrigger;
        this.findings = findings;
        this.findingEvidence = findingEvidence;
        this.behaviorTriage = behaviorTriage;
        this.classifiers = classifiers;
        this.toolErrorStates = toolErrorStates;
        this.malformedOutputRates = malformedOutputRates;
        this.frustrationRates = frustrationRates;
        this.frustrationSessions = frustrationSessions;
        this.groundednessRates = groundednessRates;
        this.groundednessAnswers = groundednessAnswers;
        this.malformedOutputDetail = malformedOutputDetail;
        this.secretLeakDetail = secretLeakDetail;
        this.frustrationDetail = frustrationDetail;
        this.groundednessDetail = groundednessDetail;
        this.adapters = adapters;
    }

    /** A table read: the detectors ran on a worker, so the first screen never replays history. */
    public TriageView triage(String projectId) {
        List<CaseRow> live = cases.listLive(projectId);
        List<CaseRow> closed = cases.listResolvedSince(projectId, Instant.now().minus(HISTORY_WINDOW));
        // Both buckets in one lookup: a closure that cannot say what it turned out to be is the least useful row.
        Map<String, CaseLead> leads = leadsFor(projectId, live, closed);

        List<CaseView> open =
                live.stream().map(row -> CaseView.of(row, lead(leads, row))).toList();
        List<CaseView> resolved =
                closed.stream().map(row -> CaseView.of(row, lead(leads, row))).toList();
        // Rendered only when the queue is empty, so a project with open cases never pays for them.
        return new TriageView(open, resolved, watching(projectId, open.isEmpty()));
    }

    /** Keyed by case id: a case reads over all of its findings. */
    @SafeVarargs
    private Map<String, CaseLead> leadsFor(String projectId, List<CaseRow>... batches) {
        Set<String> caseIds =
                Arrays.stream(batches).flatMap(List::stream).map(CaseRow::id).collect(Collectors.toSet());
        return rcaReports.leadsByCase(projectId, caseIds);
    }

    private static @Nullable CaseLead lead(Map<String, CaseLead> leads, CaseRow row) {
        return leads.get(row.id());
    }

    /**
     * The flat, filterable read beside {@link #triage}'s fixed buckets, which cannot be paged because a half bucket is
     * not an all-clear. Over-fetches one row to detect a next page. A bad cursor restarts at page one; see
     * {@link CasePageCodec}.
     */
    public CasesPage page(
            String projectId,
            @Nullable String state,
            @Nullable String detector,
            @Nullable String callSiteId,
            int pageSize,
            @Nullable String cursor) {
        CaseRepository.PageOrder order = CaseRepository.orderFor(state);
        List<CaseRow> rows =
                cases.page(projectId, state, detector, callSiteId, pageSize + 1, CasePageCodec.decode(cursor, order));
        CasePageCodec.Page page = CasePageCodec.trim(rows, pageSize, order);
        Map<String, CaseLead> leads = leadsFor(projectId, page.rows());
        return new CasesPage(
                page.rows().stream()
                        .map(row -> CaseView.of(row, lead(leads, row)))
                        .toList(),
                page.nextCursor());
    }

    /**
     * What the all-clear state cites as proof the silence is coverage, shared by Triage and MCP {@code get_project} so
     * they cannot disagree. Uses {@link ClassifierService#list}, so a withheld classifier is not counted as watching.
     */
    public WatchingView watching(String projectId) {
        return watching(projectId, true);
    }

    /**
     * {@code includeEmptyStateCounts} gates the two counts only the empty Triage screen reads; the all-time trace count
     * grows with the project. False leaves them null, which callers must not render as zero.
     */
    public WatchingView watching(String projectId, boolean includeEmptyStateCounts) {
        String since = Instant.now().minus(COVERAGE_WINDOW).toString();
        long enabled = classifiers.list(projectId).stream()
                .filter(ClassifierRow::enabled)
                .count();
        return new WatchingView(
                enabled,
                substrate.countCallSitesSince(projectId, since),
                substrate.countTracesSince(projectId, since),
                includeEmptyStateCounts ? substrate.countTraces(projectId) : null,
                includeEmptyStateCounts ? findings.countOpen(projectId) : null);
    }

    public CaseDetailView detail(String projectId, String id) {
        CaseRow row = require(projectId, id);
        FindingRow finding = findingBehind(projectId, row);
        boolean detectorAvailable = detectorAvailable(projectId, row);
        RcaReportRow report = latestRcaReport(projectId, finding);
        RcaReportView rca = inlinedRcaReport(report);
        List<FindingRow> held = findings.listByCase(projectId, row.id());
        FindingRow worst = worstWindow(held, finding);
        ShiftDetail metric = shiftDetail(worst);
        RateDetail toolError = rateDetail(worst);
        return new CaseDetailView(
                // Captioned off the report in hand, so the header cannot disagree with the analysis below it.
                CaseView.of(row, leadOf(rca)),
                events.listByCase(projectId, row.id()).stream()
                        .map(CaseEventView::of)
                        .toList(),
                finding == null ? null : finding.id(),
                caseFindings(held),
                (metric != null || toolError != null) && worst != null ? worst.id() : null,
                ruling(finding),
                exemplars.forCase(
                        projectId,
                        finding == null ? List.of() : findingEvidence.listByFinding(projectId, finding.id())),
                report == null ? null : report.id(),
                rca,
                metric,
                toolError,
                finding == null ? null : malformedOutputDetail.detail(finding),
                finding == null ? null : secretLeakDetail.detail(secretLeakFindings(projectId, row, finding)),
                finding == null ? null : frustrationDetail.detail(finding),
                finding == null ? null : groundednessDetail.detail(finding),
                finding == null ? null : adapters.detail(finding).orElse(null),
                finding != null && detectorAvailable,
                finding != null && row.isLive() && detectorAvailable && absorbable(row),
                detectorAvailable);
    }

    /** A drift finding's window is the detector's own; any other is its onset to its last sighting. */
    private static List<CaseFindingView> caseFindings(List<FindingRow> newestFirst) {
        List<FindingRow> all = new ArrayList<>(newestFirst);
        Collections.reverse(all);
        return all.stream()
                .map(f -> {
                    ShiftDetail shift = shiftDetail(f);
                    String opened = shift == null ? null : shift.windowOpenedAt();
                    String closed = shift == null ? null : shift.windowClosedAt();
                    if (opened == null || closed == null) {
                        opened = f.onsetAt();
                        closed = f.lastSeenAt();
                    }
                    return new CaseFindingView(f.id(), FindingTitle.of(f), opened, closed, f.createdAt());
                })
                .toList();
    }

    /** The window that moved furthest from its reference, taken whole so the figure is one that actually happened. */
    static @Nullable FindingRow worstWindow(List<FindingRow> held, @Nullable FindingRow newest) {
        FindingRow worst = newest;
        double worstSize = magnitude(newest);
        for (FindingRow f : held) {
            double size = magnitude(f);
            if (size > worstSize) {
                worst = f;
                worstSize = size;
            }
        }
        return worst;
    }

    private static double magnitude(@Nullable FindingRow f) {
        ShiftDetail shift = shiftDetail(f);
        if (shift != null && shift.ratio() > 0) return Math.abs(Math.log(shift.ratio()));
        RateDetail rate = rateDetail(f);
        if (rate != null) return Math.abs(rate.deltaPp());
        return -1;
    }

    /** All of a secret-leak case's findings: a credential leaked in an earlier window is still exposed. */
    private List<FindingRow> secretLeakFindings(String projectId, CaseRow row, FindingRow newest) {
        if (!CaseRow.Detector.SECRET_LEAK.equals(row.detector())) return List.of(newest);
        List<FindingRow> all = new ArrayList<>();
        all.add(newest);
        for (FindingRow f : findings.listByCase(projectId, row.id())) {
            if (!f.id().equals(newest.id())) all.add(f);
        }
        return all;
    }

    /**
     * Gated on {@code causeKind}, as {@code BehaviorFindingDetailView#of} is: asking the wrong reader returns a null
     * that looks like "no figure". A malformed blob yields null and the page still renders.
     */
    private static @Nullable ShiftDetail shiftDetail(@Nullable FindingRow finding) {
        if (finding == null || !FindingRow.Cause.DISTRIBUTION_SHIFT.equals(finding.causeKind())) return null;
        return MetricFindingEvidence.detail(finding.payloadJson());
    }

    private static @Nullable RateDetail rateDetail(@Nullable FindingRow finding) {
        if (finding == null || !FindingRow.Cause.RATE_SHIFT.equals(finding.causeKind())) return null;
        return ToolErrorEvidence.detail(finding.payloadJson());
    }

    /**
     * {@code finding.case_id} is {@code ON DELETE SET NULL}, so a missing finding here is a race with a delete; not
     * worth failing the render.
     */
    private @Nullable FindingRow findingBehind(String projectId, CaseRow row) {
        String ref = row.latestFindingId();
        if (ref == null || ref.isBlank()) return null;
        return findings.findById(projectId, ref).orElse(null);
    }

    /**
     * No absorb where it would move nothing (secret leak, malformed output, frustration), since the next sweep would
     * refile the same finding. Groundedness keeps it: absorbing re-learns its reference.
     */
    private static boolean absorbable(CaseRow row) {
        return !CaseRow.Detector.SECRET_LEAK.equals(row.detector())
                && !CaseRow.Detector.MALFORMED_OUTPUT.equals(row.detector())
                && !CaseRow.Detector.FRUSTRATION.equals(row.detector());
    }

    /**
     * Read off the finding, never recomputed. A human Real-deviation ruling ({@code blocked}) outranks any machine
     * verdict and carries no citations.
     */
    private static @Nullable CaseRulingView ruling(@Nullable FindingRow finding) {
        if (finding == null) return null;
        if (finding.humanVerdictAt() != null) {
            return new CaseRulingView(
                    finding.id(),
                    "Human",
                    "A person ruled this a real deviation.",
                    null,
                    null,
                    null,
                    List.of(),
                    finding.humanVerdictAt(),
                    true);
        }
        if (finding.triagedAt() == null) return null;
        return new CaseRulingView(
                finding.id(),
                "Triage",
                "A triage run audited this finding's claim and found it sound.",
                finding.triageVerdict(),
                finding.triageAction(),
                finding.triageSummary(),
                citations(finding.triageCitationsJson()),
                finding.triagedAt(),
                false);
    }

    private static List<CitationView> citations(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<CitationView> out = new ArrayList<>();
            for (BehaviorTriageVerdict.Citation c : CITATIONS.<List<BehaviorTriageVerdict.Citation>>readValue(json)) {
                out.add(new CitationView(c.path(), c.reason()));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * A withdrawn classifier's cases stay open (closing them would claim a recovery), but every action on them is
     * withheld and the page names the missing classifier.
     */
    private boolean detectorAvailable(String projectId, CaseRow row) {
        return !classifiers.unavailableDetectorKinds(projectId).contains(row.detector());
    }

    /**
     * {@code disposition} is only for frustration or groundedness cases; null restarts the call site without clearing
     * anything.
     */
    @Transactional
    public CaseView resolve(
            String projectId,
            String id,
            @Nullable String reason,
            @Nullable String actor,
            @Nullable String disposition) {
        CaseRow row = require(projectId, id);
        if (!row.isLive()) throw new TessaryException(CaseError.ALREADY_RESOLVED, row.reference());
        String given = reason == null || reason.isBlank() ? null : reason.strip();
        String note = given == null ? CLOSED_NOTE : given;
        boolean frustration = CaseRow.Detector.FRUSTRATION.equals(row.detector());
        boolean groundedness = CaseRow.Detector.GROUNDEDNESS.equals(row.detector());
        if (disposition != null && !frustration && !groundedness) {
            throw new TessaryException(CaseError.DISPOSITION_NOT_APPLICABLE, row.reference());
        }

        Instant now = Instant.now();
        cases.resolve(projectId, row.id(), CaseRow.Resolution.HUMAN, given, actor, disposition, now);

        // Frustration restarts its call site and re-learns its reference on either disposition; a false alarm also
        // clears the cited conversations. Both land before the findings close, in this transaction.
        String detail = null;
        if (frustration) {
            frustrationRates.states().resetAndRelearn(projectId, row.subjectId(), actor, note, now.toString());
            int cleared = CaseRow.Disposition.FALSE_ALARM.equals(disposition)
                    ? frustrationSessions.clear(projectId, row.id(), now.toString())
                    : 0;
            detail = disposition == null
                    ? null
                    : "{\"disposition\":\"" + disposition + "\",\"sessions_cleared\":" + cleared + "}";
        }
        // Groundedness the same way; a false alarm clears the flag on every cited answer.
        if (groundedness) {
            groundednessRates.states().resetAndRelearn(projectId, row.subjectId(), actor, note, now.toString());
            int cleared = CaseRow.Disposition.FALSE_ALARM.equals(disposition)
                    ? groundednessAnswers.clear(projectId, row.id(), now.toString())
                    : 0;
            detail = disposition == null
                    ? null
                    : "{\"disposition\":\"" + disposition + "\",\"answers_cleared\":" + cleared + "}";
        }
        events.append(projectId, row.id(), CaseEventRow.Kind.RESOLVED, actor, note, detail, now);

        // The uncapped accumulator would take millions of healthy calls to drain after an outage, reopening the case
        // for weeks. Clearing it claims "this is over": if not, a NEW case opens with an honest onset.
        if (CaseRow.Detector.TOOL_ERROR.equals(row.detector())) {
            toolErrorStates.reset(projectId, row.subjectId(), actor, note, now.toString());
        }
        // Malformed output replays through tool_error's engine, so it needs the identical reset.
        if (CaseRow.Detector.MALFORMED_OUTPUT.equals(row.detector())) {
            malformedOutputRates.states().reset(projectId, row.subjectId(), actor, note, now.toString());
        }
        // The only thing that closes a secret-leak finding; for other detectors it is usually a no-op.
        findings.closeByCase(projectId, row.id(), now.toString());
        return CaseView.of(require(projectId, id));
    }

    /**
     * Legitimate, absorb: re-pin what the detector compares against so the new level becomes the baseline, which
     * resolving does not do. Delegated to {@code repinCase}, since the findings already carry a positive ruling and the
     * correction loop would 409. The re-pin commits first: if the close then fails the case stays open, never closed
     * against a bar that never moved.
     */
    public CaseView absorb(String projectId, String id, @Nullable String actor) {
        CaseRow row = require(projectId, id);
        if (!row.isLive()) throw new TessaryException(CaseError.ALREADY_RESOLVED, row.reference());
        if (row.latestFindingId() == null || !absorbable(row)) {
            throw new TessaryException(CaseError.NOT_ABSORBABLE, row.reference());
        }
        if (!detectorAvailable(projectId, row)) {
            throw new TessaryException(CaseError.DETECTOR_UNAVAILABLE, row.detector());
        }
        // Every window, or an earlier spell's evidence would keep alarming on traffic just accepted as fine.
        behaviorTriage.repinCase(projectId, findings.listByCase(projectId, row.id()), actor);
        // Through the ledger: a @Transactional method invoked through `this` gets no proxy.
        ledger.absorb(projectId, row.id(), actor, Instant.now());
        return CaseView.of(require(projectId, id));
    }

    /**
     * Lock the case, record the press and enqueue RCA on its newest finding, committed together. A locked case takes no
     * new findings, so the agent's subject stays what the person read. Closed cases can still be analysed; re-pressing
     * coalesces onto the one report.
     */
    @Transactional
    public RcaReportView runRca(String projectId, String id, String actor) {
        CaseRow row = require(projectId, id);
        List<FindingRow> caseFindings = findings.listByCase(projectId, row.id());
        if (caseFindings.isEmpty()) {
            throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, row.reference());
        }
        String newestFindingId = caseFindings.get(0).id();

        Instant now = Instant.now();
        if (row.lockedAt() == null) {
            cases.lock(projectId, row.id(), now);
            events.append(
                    projectId,
                    row.id(),
                    CaseEventRow.Kind.RCA_REQUESTED,
                    actor,
                    "Requested — locks the case to its findings; the cause's next positive opens a new case.",
                    null,
                    now);
        }
        return rcaTrigger.trigger(projectId, newestFindingId, actor, null);
    }

    /** From the VIEW, so the header cannot disagree with the body. */
    private static @Nullable CaseLead leadOf(@Nullable RcaReportView rca) {
        if (rca == null || !JobRow.Status.DONE.equals(rca.status())) return null;
        return new CaseLead(rca.verdict(), rca.summary());
    }

    private @Nullable RcaReportRow latestRcaReport(String projectId, @Nullable FindingRow finding) {
        if (finding == null) return null;
        return rcaReports.listByFinding(projectId, finding.id(), 1).stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Null while running: a pending report is an empty shell that would read as "concluded nothing". Terminal reports
     * are inlined, failed ones included.
     */
    private @Nullable RcaReportView inlinedRcaReport(@Nullable RcaReportRow report) {
        if (report == null
                || JobRow.Status.PENDING.equals(report.status())
                || JobRow.Status.CLAIMED.equals(report.status())) {
            return null;
        }
        return rcaReportViews.view(report);
    }

    private CaseRow require(String projectId, String id) {
        Optional<CaseRow> row = cases.findById(projectId, id);
        if (row.isPresent()) return row.get();
        // Slack and ⌘K hand back the human reference, so C-118 resolves as well as the ulid.
        Long seq = sequenceOf(id);
        if (seq != null) {
            Optional<CaseRow> bySeq = cases.findBySeq(projectId, seq);
            if (bySeq.isPresent()) return bySeq.get();
        }
        throw new TessaryException(CaseError.NOT_FOUND, id);
    }

    private static @Nullable Long sequenceOf(String reference) {
        String digits = reference.startsWith("C-") || reference.startsWith("c-") ? reference.substring(2) : reference;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
