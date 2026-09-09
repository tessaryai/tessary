// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseEventView;
import ai.tessary.cases.CaseDtos.CaseRulingView;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.cases.CaseDtos.CitationView;
import ai.tessary.cases.CaseDtos.TriageView;
import ai.tessary.cases.CaseDtos.WatchingView;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.finding.BehaviorTriageVerdict;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read and lifecycle for cases: Triage's list, the case page's assembly, and resolve / mute. */
@Service
public class CaseService {

    /** How far back Triage's quiet history line reaches. */
    private static final Duration HISTORY_WINDOW = Duration.ofDays(7);

    /** How far back Triage's all-clear line looks to prove that traffic is arriving at all. */
    private static final Duration COVERAGE_WINDOW = Duration.ofDays(1);

    /** The finding's {@code triage_citations} blob, read back into the shape the case page renders. */
    private static final ObjectReader CITATIONS =
            new ObjectMapper().readerFor(new TypeReference<List<BehaviorTriageVerdict.Citation>>() {});

    private final CaseRepository cases;
    private final CaseLedger ledger;
    private final CaseEventRepository events;
    private final CaseSubstrateRepository substrate;
    private final CaseExemplars exemplars;
    private final RcaReportRepository rcaReports;
    /** The report itself, for the case page's inlined {@code rca} — one owner of the RCA wire shape. */
    private final RcaReportService rcaReportViews;
    /** The press. Takes a finding id and nothing else — the case surface is where a person decides. */
    private final RcaTriggerService rcaTrigger;

    private final FindingRepository findings;
    private final FindingEvidenceRepository findingEvidence;
    private final FindingService drift;
    private final ClassifierService classifiers;
    /** Cleared when a tool-error case is closed by hand — see {@link #resolve}. */
    private final ToolErrorStateRepository toolErrorStates;

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
            FindingService drift,
            ClassifierService classifiers,
            ToolErrorStateRepository toolErrorStates) {
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
        this.drift = drift;
        this.classifiers = classifiers;
        this.toolErrorStates = toolErrorStates;
    }

    // ---- reads -------------------------------------------------------------------------------

    /** Triage. A table read — the detectors ran on a worker, so the app's first screen never replays
     *  28 days of history to find out whether anything is wrong. */
    public TriageView triage(String projectId) {
        List<CaseRow> live = cases.listLive(projectId);
        List<CaseRow> closed = cases.listResolvedSince(projectId, Instant.now().minus(HISTORY_WINDOW));
        // ONE lookup for the whole screen — see leadsByFinding. Both buckets are keyed at once because a
        // resolved case is the one most likely to have been analysed, and a closure that cannot say what it
        // turned out to be is the least useful row in the history.
        Map<String, CaseLead> leads = leadsFor(projectId, live, closed);

        List<CaseView> open = new ArrayList<>();
        List<CaseView> muted = new ArrayList<>();
        for (CaseRow row : live) {
            (CaseRow.State.MUTED.equals(row.state()) ? muted : open).add(CaseView.of(row, lead(leads, row)));
        }
        List<CaseView> resolved =
                closed.stream().map(row -> CaseView.of(row, lead(leads, row))).toList();
        // The empty-state counts are only rendered when there is nothing in the queue, so a project
        // with open cases never pays for them.
        return new TriageView(open, muted, resolved, watching(projectId, open.isEmpty()));
    }

    /** The finished-RCA verdicts behind every case in {@code batches}, in one query. */
    @SafeVarargs
    private Map<String, CaseLead> leadsFor(String projectId, List<CaseRow>... batches) {
        Set<String> findingIds = Arrays.stream(batches)
                .flatMap(List::stream)
                .map(CaseRow::findingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return rcaReports.leadsByFinding(projectId, findingIds);
    }

    private static @Nullable CaseLead lead(Map<String, CaseLead> leads, CaseRow row) {
        return row.findingId() == null ? null : leads.get(row.findingId());
    }

    /**
     * One filtered, keyset-paged page of cases — the flat read, beside {@link #triage}'s fixed buckets.
     *
     * <p>Both exist because they answer different questions. Triage renders a screen: everything live at
     * once, worst first, with the closures underneath and the coverage line beside them, and it cannot be
     * paged because a bucket that stops halfway is not an all-clear. This one is for a caller that names a
     * filter and walks the result — an agent asking "the open cost_drift cases on this call site", which under
     * the bucketed shape meant fetching every live case and filtering client-side.
     *
     * @param pageSize rows the caller wants, already clamped to the calling surface's own maximum. This
     *     method over-fetches one past it to detect a next page without a second {@code COUNT}.
     * @param cursor a previous page's {@code next_cursor}. An unreadable, stale, or wrong-order token silently
     *     restarts at page one — see {@link CasePageCodec}.
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
     * What the all-clear state cites as proof the silence is coverage (launch requirement E5).
     *
     * <p><b>Public because two surfaces render it.</b> It rides along with {@link #triage} for the screen, and
     * the MCP {@code get_project} read answers it for an agent — the flat {@code list_cases} page cannot carry
     * it, and a coverage block that nothing renders is a launch requirement quietly dropped. Same call behind
     * both, so the two cannot disagree about whether anything is watching.
     *
     * <p>Counted from the classifiers this org actually has and the traffic that actually arrived, not
     * from the pipeline. The old answer — graders and call sites off {@code Pipeline} — was honest when a
     * grader set was the only thing that ever noticed anything, and was wrong the moment it stopped
     * being: a partner with no pipeline at all would have been told nothing was watching while three
     * classifiers swept their traffic all week. Grading has since left the platform entirely.
     *
     * <p>{@link ClassifierService#list} rather than the raw table, so a classifier the flag layer is
     * withholding is not counted as watching — because it is not.
     */
    public WatchingView watching(String projectId) {
        return watching(projectId, true);
    }

    /**
     * @param includeEmptyStateCounts whether to count all-time traces and live findings. Both are read
     *     ONLY by the empty Triage screen, which renders only when the queue is empty, and the all-time
     *     trace count is the one number here whose cost grows with the project rather than with a
     *     window. Passing {@code false} leaves them null — "not counted", which callers must not render
     *     as zero — so the busiest projects, the ones with a full queue, pay nothing for a screen they
     *     will not see.
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
                includeEmptyStateCounts ? findings.countLive(projectId) : null);
    }

    public CaseDetailView detail(String projectId, String id) {
        CaseRow row = require(projectId, id);
        FindingRow finding = findingBehind(projectId, row);
        boolean detectorAvailable = detectorAvailable(projectId, row);
        RcaReportRow report = latestRcaReport(projectId, finding);
        RcaReportView rca = inlinedRcaReport(projectId, report);
        return new CaseDetailView(
                // The report is already in hand, so the caption comes off it directly — no second lookup,
                // and the header cannot disagree with the analysis rendered below it.
                CaseView.of(row, leadOf(rca)),
                events.listByCase(projectId, row.id()).stream()
                        .map(CaseEventView::of)
                        .toList(),
                finding == null ? null : finding.id(),
                ruling(row, finding),
                exemplars.forCase(
                        projectId,
                        finding == null ? List.of() : findingEvidence.listByFinding(projectId, finding.id())),
                report == null ? null : report.id(),
                rca,
                shiftDetail(finding),
                rateDetail(finding),
                finding != null && detectorAvailable,
                finding != null && row.isLive() && detectorAvailable && absorbable(row),
                detectorAvailable);
    }

    /**
     * The measured shift behind this case, for the surface that DRAWS it.
     *
     * <p>Gated on {@code causeKind} rather than on which parse happens to succeed, exactly as
     * {@code BehaviorFindingDetailView#of} gates it: the payload column is one blob per cause kind, and
     * asking the wrong reader for it returns null for a reason that reads identically to "this finding
     * has no figure". One of those is a fact about the detector and the other would be a bug.
     *
     * <p>A malformed blob still yields null and the page still renders. A case that vanished because one
     * column would not parse would be a worse failure than a case with no chart on it — the same call
     * {@code BehaviorDtos} made for the finding page.
     */
    private static @Nullable ShiftDetail shiftDetail(@Nullable FindingRow finding) {
        if (finding == null || !FindingRow.Cause.DISTRIBUTION_SHIFT.equals(finding.causeKind())) return null;
        return MetricFindingEvidence.detail(finding.payloadJson());
    }

    /** The rate shift behind this case. Same contract as {@link #shiftDetail}. */
    private static @Nullable RateDetail rateDetail(@Nullable FindingRow finding) {
        if (finding == null || !FindingRow.Cause.RATE_SHIFT.equals(finding.causeKind())) return null;
        return ToolErrorEvidence.detail(finding.payloadJson());
    }

    /**
     * The finding a case came from, or null when it did not come from one.
     *
     * <p>One lookup, one table. It used to branch on the detector — SOP conformance in
     * {@code conformance_finding}, everything else in {@code behavior_finding} — and the branch was
     * load-bearing rather than tidy: resolving a conformance id against the behaviour table returned
     * nothing silently, and the page rendered a case with no ruling and sampled filler where its
     * recorded violations should have been. With one {@code finding} table there is no wrong table to
     * ask, so the whole class of bug is gone rather than guarded.
     *
     * <p>A {@code finding_id} that no longer resolves reads the same as none. The FK is
     * {@code ON DELETE RESTRICT}, so this can only happen to a row written before the constraint
     * existed — and a case is not worth failing to render over.
     */
    private @Nullable FindingRow findingBehind(String projectId, CaseRow row) {
        String ref = row.findingId();
        if (ref == null || ref.isBlank()) return null;
        return findings.findById(projectId, ref).orElse(null);
    }

    /** No absorb for an SOP rule: the SOP is the fixed reference, and re-authoring it is a repo edit
     *  rather than a button — there is nothing here for "move the bar" to move. */
    private static boolean absorbable(CaseRow row) {
        return !CaseRow.Detector.SOP_CONFORMANCE.equals(row.detector());
    }

    /**
     * Who ruled this detection real, and what they said (launch requirements E1 and E6).
     *
     * <p>Read straight off the finding, never recomputed, so the case and the finding cannot disagree
     * about what was ruled. A finding whose status is {@code blocked} was ruled by a human pressing
     * <em>Real deviation</em> — that outranks any machine verdict on the same row, which is why the
     * human branch is checked first, and it carries no citations because a person looking at a
     * regression is not a classifier with a calibration.
     *
     * <p>A machine ruling only reaches a case one way now: triage found the claim {@code positive}, so
     * every non-human ruling here says the same verdict. What varies is the summary and the citations,
     * which is what a reader deciding whether to page someone actually reads.
     */
    private static @Nullable CaseRulingView ruling(CaseRow row, @Nullable FindingRow finding) {
        if (finding == null) return null;
        if (FindingRow.Status.BLOCKED.equals(finding.status())) {
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
        if (finding.triagedAt() == null) return null; // nothing has ruled; the case came in some other way
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

    /** A malformed citations blob degrades to none rather than failing the page — same as the finding row. */
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
     * Whether the classifier that opened this case is one the org still has (launch requirement E4).
     *
     * <p>Segment D gates the classifier list, its findings and its alert rules on the flag layer, and
     * deliberately does NOT close the cases a withdrawn classifier already opened — history is a record
     * of something that happened, and closing them as recovered would tell someone a regression fixed
     * itself. The residual it left open is this one: such a case can sit in Triage offering buttons that
     * reach a classifier the org cannot reach. So the case still renders, and every action on it is
     * withheld, with the page saying which classifier went away rather than 404ing on the press.
     *
     */
    private boolean detectorAvailable(String projectId, CaseRow row) {
        return !classifiers.unavailableDetectorKinds(projectId).contains(row.detector());
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    /**
     * Close a case with the human's one-line reason. The reason is required by the wire contract and
     * by the table; it is the only thing that makes a closed case worth reading later.
     */
    @Transactional
    public CaseView resolve(String projectId, String id, String reason, @Nullable String actor) {
        CaseRow row = require(projectId, id);
        if (!row.isLive()) throw new TessaryException(CaseError.ALREADY_RESOLVED, row.reference());
        if (reason.isBlank()) throw new TessaryException(CaseError.REASON_REQUIRED);

        Instant now = Instant.now();
        cases.resolve(projectId, row.id(), CaseRow.Resolution.HUMAN, reason, actor, now);
        events.append(projectId, row.id(), CaseEventRow.Kind.RESOLVED, actor, reason, null, now);

        // A tool-error case closes on a human saying "dealt with", and the accumulator behind it has to
        // hear that. It is no longer capped, so a serious outage leaves it high enough that draining at
        // the healthy rate would take millions of calls — the case would reopen on the next sweep and go
        // on reopening for weeks after the fix. Clearing it is the claim "this is over": if it is not,
        // evidence rebuilds from zero and raises a NEW case with an honest new onset, rather than
        // resurrecting this one off evidence from before the fix. The reason the wire contract already
        // requires becomes the note on the reset.
        if (CaseRow.Detector.TOOL_ERROR.equals(row.detector())) {
            toolErrorStates.reset(projectId, row.subjectId(), actor, reason, now.toString());
        }
        return CaseView.of(require(projectId, id));
    }

    /**
     * <b>Legitimate — absorb</b>: this shift is real and it is fine, so move the bar rather than argue
     * with it again tomorrow (launch requirement E3).
     *
     * <p>The distinction from {@link #resolve} is the whole point and is not cosmetic. Resolving closes
     * one case; the detector's reference is untouched, so the same population sitting at the same new
     * level earns another shifted window and opens another case within a day. Absorbing re-pins what the
     * detector compares against — metric drift's reference sketch, tool error's accepted counts — so the
     * new level becomes the baseline and only a further move fires. A partner who cannot say "this is our
     * new normal" has no way out of a case that is correct and unwanted, which is how a detector earns
     * the reputation that gets it turned off.
     *
     * <p><b>The re-pin is the finding's job and is delegated, never reimplemented.</b>
     * {@code FindingService.resolve(…, "expected")} is the one place that knows what "absorb" means
     * for each cause kind, and it refuses rather than guesses when the numbers to accept are unreadable.
     * That refusal propagates: if the reference cannot be moved, the case does not close, because a case
     * closed as absorbed while the detector kept its old bar would reopen on the next pass and read as
     * the button being broken.
     *
     * <p>Not transactional across the two writes by choice — the finding resolution is its own
     * transaction and commits first. If the case close then failed, the reference has still moved and the
     * detection stops firing, so {@link CaseReconciler} closes the case as recovered on the next pass. The
     * inverse order would be the dangerous one: a case closed against a bar that never moved.
     */
    public CaseView absorb(String projectId, String id, @Nullable String actor) {
        CaseRow row = require(projectId, id);
        if (!row.isLive()) throw new TessaryException(CaseError.ALREADY_RESOLVED, row.reference());
        String findingId = row.findingId();
        if (findingId == null || findingId.isBlank() || !absorbable(row)) {
            throw new TessaryException(CaseError.NOT_ABSORBABLE, row.reference());
        }
        if (!detectorAvailable(projectId, row)) {
            throw new TessaryException(CaseError.DETECTOR_UNAVAILABLE, row.detector());
        }
        drift.resolve(projectId, findingId, BehaviorResolutionRequest.EXPECTED, actor);
        // Through the ledger, not inline: the close and its trail line must commit together, and a
        // @Transactional method on THIS class invoked through `this` gets no proxy and no transaction.
        ledger.absorb(projectId, row.id(), actor, Instant.now());
        return CaseView.of(require(projectId, id));
    }

    /**
     * Press RCA on this case: resolve the finding behind it and enqueue the lane on that id alone.
     *
     * <p>Deliberately NOT transactional and deliberately not conditional on the case's state. A closed
     * or muted case is still worth root-causing — "we absorbed this, why did it happen" is a normal
     * question — and the report is an immutable artefact that changes nothing about the case.
     *
     * <p>A case with no finding behind it cannot be analysed: RCA is anchored on a claim and its
     * recorded evidence, and there is nothing here to anchor on. Only rows written before
     * {@code eval_case.finding_id} became mandatory can be in that state.
     */
    public RcaReportView runRca(String projectId, String id, @Nullable String actor) {
        CaseRow row = require(projectId, id);
        String findingId = row.findingId();
        if (findingId == null || findingId.isBlank()) {
            throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, row.reference());
        }
        return rcaTrigger.trigger(projectId, findingId, actor, null);
    }

    /** Silence a case without closing it — "known, stop paging". Scoped to this case alone; silencing
     *  the detector behind it is a heavier decision that belongs with the detector. */
    @Transactional
    public CaseView mute(String projectId, String id, @Nullable String actor) {
        CaseRow row = require(projectId, id);
        if (!row.isLive()) throw new TessaryException(CaseError.ALREADY_RESOLVED, row.reference());
        // Already silenced: nothing to do, and nothing to say about it. Two people reaching for mute on
        // the same case is ordinary, so this is a no-op rather than an error — but it must not append a
        // second "Muted" line to a trail whose job is to read as a story.
        if (CaseRow.State.MUTED.equals(row.state())) return CaseView.of(row);

        Instant now = Instant.now();
        cases.mute(projectId, row.id(), actor, now);
        events.append(projectId, row.id(), CaseEventRow.Kind.MUTED, actor, "Muted — known, stop paging.", null, now);
        return CaseView.of(require(projectId, id));
    }

    @Transactional
    public CaseView unmute(String projectId, String id, @Nullable String actor) {
        CaseRow row = require(projectId, id);
        if (!CaseRow.State.MUTED.equals(row.state())) throw new TessaryException(CaseError.NOT_MUTED, row.reference());

        Instant now = Instant.now();
        cases.unmute(projectId, row.id(), now);
        events.append(projectId, row.id(), CaseEventRow.Kind.UNMUTED, actor, "Unmuted.", null, now);
        return CaseView.of(require(projectId, id));
    }

    /**
     * The already-loaded report as the caption the header reads. Unfinished runs conclude nothing.
     *
     * <p>The VIEW rather than the row: the row's hypotheses are a raw jsonb string, and the view has
     * already parsed them for the body of the page. Deriving the header's caption from the same object
     * the reader sees below it is also what keeps the two from ever disagreeing.
     */
    private static @Nullable CaseLead leadOf(@Nullable RcaReportView rca) {
        if (rca == null || !JobRow.Status.DONE.equals(rca.status())) return null;
        return new CaseLead(
                rca.verdict(),
                rca.hypotheses().isEmpty() ? null : rca.hypotheses().get(0).title());
    }

    /** The most recent RCA on the FINDING behind this case. RCA is a finding-analysis lane now, so
     *  the anchor is the finding id rather than a (subject, metric) triple that only the retired CUSUM
     *  mover ever produced. */
    private @Nullable RcaReportRow latestRcaReport(String projectId, @Nullable FindingRow finding) {
        if (finding == null) return null;
        return rcaReports.listByFinding(projectId, finding.id(), 1).stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * That report as the wire view — verdict, hypotheses, ruled-out checklist, the agent's markdown — or null
     * while it is still running.
     *
     * <p><b>Running is the one state that stays a bare id.</b> A pending or claimed report is a shell: the
     * worker has written nothing into it yet, so inlining it would render an object whose every interesting
     * field is null, which reads as "the analysis concluded nothing" rather than "the analysis has not
     * finished". A terminal report is inlined whether it succeeded or failed, because the view carries its own
     * {@code status} and a reader can tell the two apart — and a failed analysis is a fact worth having, not a
     * pending one to wait on.
     *
     * <p>Fetched through {@link RcaReportService} rather than mapped here, so a case page and the RCA surface
     * render one report shape; the row's id is enough of a handle, and re-reading it by that id costs one
     * primary-key lookup. Gone between the two reads reads as absent — a case is not worth failing to render
     * over a report that was deleted mid-request.
     */
    private @Nullable RcaReportView inlinedRcaReport(String projectId, @Nullable RcaReportRow report) {
        if (report == null
                || JobRow.Status.PENDING.equals(report.status())
                || JobRow.Status.CLAIMED.equals(report.status())) {
            return null;
        }
        try {
            return rcaReportViews.get(projectId, report.id());
        } catch (TessaryException e) {
            return null;
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private CaseRow require(String projectId, String id) {
        Optional<CaseRow> row = cases.findById(projectId, id);
        if (row.isPresent()) return row.get();
        // Slack and ⌘K both hand back the human-facing reference, so C-118 resolves as well as the ulid.
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
