// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import ai.tessary.evals.classifier.ClassifierService;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorBaselineEventView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.evals.classifier.finding.BehaviorDtos.EvidenceRefView;
import ai.tessary.evals.open.errors.ClassifierError;
import ai.tessary.evals.open.errors.EvalsException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The findings read + correction surface every classifier serves through.
 *
 * <p>It was {@code BehaviorDriftService} until #839, and the rename is the point: seven of its eight
 * public methods never had anything to do with behaviour drift. The findings store is shared — rows
 * carry a {@code classifier_key} — and metric drift, tool error and SOP conformance all list, page,
 * escalate and resolve through here.
 *
 * <p><b>The merged surface knows no table.</b> Listing, detail, resolution and the dossier all route
 * through {@link TriageSource}, in {@code @Order}, and the first source that claims an id ends the
 * iteration — that is the seam #840 and #841 lift a classifier out through, and it holds with no
 * exceptions.
 *
 * <p>It is not the whole class, and pretending otherwise would mislead the extraction that reads this
 * next. Three methods still hold {@code FindingRepository} and {@code FindingEvidenceRepository}
 * directly: they serve the SHARED table as itself — evidence rows, the reachability guard — rather
 * than any one classifier's view of it, so there is no source to route them to. What is left here is
 * the page envelope, the routing, the transaction boundary, and those shared-table reads.
 *
 * <p>A correction is a ROW saying "this cause is fine for this project" — auditable, reversible,
 * exportable, and explainable to a compliance reviewer. That property is worth more than any accuracy a
 * learned correction model would buy, which is why the loop rides the existing {@code annotation}
 * channel ({@code of_finding_id}, {@code agrees}) rather than mutating in place the thing it corrects.
 * Until Track A the anchor was {@code of_verdict_id} and the thing left unmutated was the verdict; that
 * column and that table are gone, the property they bought is not.
 */
@Service
public class FindingService {

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    /** The flag layer, asked whether this org still has the classifier a finding came from. */
    private final ClassifierService classifiers;

    private final BehaviorBaselineEventRepository events;
    /**
     * The finding stores a press or a page can land on, in registration order (the shared table first).
     * Each one owns its own reads, its own enqueue and its own writes; this class only routes.
     */
    private final List<TriageSource> triageSources;

    public FindingService(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierService classifiers,
            BehaviorBaselineEventRepository events,
            List<TriageSource> triageSources) {
        this.findings = findings;
        this.evidence = evidence;
        this.classifiers = classifiers;
        this.events = events;
        this.triageSources = triageSources;
    }

    /**
     * The findings page, Layer-2 gated by default.
     *
     * <p>Layer 1 detects change; it cannot tell change from a problem, and at its measured operating
     * point most of what it flags is legitimate. So the default view is what Layer 2 confirmed is a real
     * deviation. {@code confirmedOnly=false} is the "show me everything" escape hatch — the raw Layer-1
     * stream, which is a lead list, not an alert list.
     *
     * <p><b>The page is the concatenation of the sources, in {@code @Order}</b>, and that order is
     * wire-observable: the shared table's rows first, conformance's after them. Each source applies its
     * own narrowing — see {@link FindingFilters}, which holds the two that no query can express — and
     * each pages to its own limit, so a two-source page can hold twice one source's worth. That was true
     * before the seam and is unchanged by it; there is no cursor on this list.
     */
    public BehaviorFindingsView findings(
            String projectId,
            @Nullable String status,
            @Nullable String callSiteId,
            @Nullable String detector,
            boolean confirmedOnly) {
        List<BehaviorFindingView> rows = triageSources.stream()
                .flatMap(source -> source.list(projectId, status, callSiteId, detector, confirmedOnly).stream())
                .toList();
        // NOTE: `countWithheld` is a different "withheld" — findings held below the TRIAGE bar, not
        // by the flag layer — and each source counts it in SQL, so it can over-report by a withheld
        // classifier's held-back findings. Left as is rather than restructured: it is a count beside a
        // list, in a state only reachable after a flag flip on a project with history.
        long withheld = triageSources.stream()
                .mapToLong(source -> source.countWithheld(projectId, callSiteId, confirmedOnly))
                .sum();
        return new BehaviorFindingsView(rows, withheld, TriageLane.EVIDENCE_ONLY.wire());
    }

    /**
     * One finding with its evidence parsed, for the finding's own page.
     *
     * <p>Goes through each source's own reachability guard, so a classifier the org does not have is a
     * 404 here exactly as it is everywhere else — otherwise hiding a detector would still leave its
     * findings readable in full by id.
     *
     * <p>No evidence set on the detail either — {@link #findingEvidenceSpans} pages it. A tool-error
     * finding can cite 27,000 refs, and shipping them to draw a table that shows ten is the bug this
     * replaced.
     */
    public BehaviorFindingDetailView finding(String projectId, String findingId) {
        for (TriageSource source : triageSources) {
            Optional<BehaviorFindingDetailView> view = source.detail(projectId, findingId);
            if (view.isPresent()) return view.get();
        }
        throw new EvalsException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }

    /**
     * One page of a finding's evidence refs — the read side of the population a detector enumerated.
     *
     * <p>Behind the same reachability guard as {@link #finding}, and for the same reason: the refs ARE
     * the finding's claim, so a classifier the org does not hold must not become readable through its
     * evidence. Conformance rows need no special case here — they share the {@code finding} table, and
     * only their DETAIL shape differs.
     *
     * <p>The counts are read on every call, {@code countOnly} or not, because they are how a caller
     * sizes what it is about to page and how it reads a role that came back empty. Both readings ride
     * along: see {@link BehaviorDtos.FindingEvidencePage} for why they can disagree.
     *
     * <p>No sampling mode, deliberately. The tool pages in the detector's own order and an agent that
     * wants a stride or a random draw takes one and states that it did — a server-side sample would put
     * the selection rule back where the auditor cannot see it, which is the whole reason the write side
     * stopped capping.
     */
    public BehaviorDtos.FindingEvidencePage findingEvidence(
            String projectId,
            String findingId,
            @Nullable String role,
            int limit,
            @Nullable String cursor,
            boolean countOnly) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        Map<String, Long> recorded = new LinkedHashMap<>();
        for (String r : FindingEvidenceRow.Role.ALL) recorded.put(r, finding.evidenceCount(r));
        Map<String, Long> live = evidence.countsByRole(projectId, findingId);
        if (countOnly) {
            return new BehaviorDtos.FindingEvidencePage(List.of(), null, true, live, recorded);
        }
        FindingEvidenceRepository.Page page = evidence.page(projectId, findingId, role, limit, cursor);
        List<EvidenceRefView> refs =
                page.rows().stream().map(EvidenceRefView::of).toList();
        return new BehaviorDtos.FindingEvidencePage(refs, page.nextCursor(), false, live, recorded);
    }

    /**
     * One page of the finding page's evidence TABLE — the same population, joined to the spans it names.
     *
     * <p>Behind the same reachability guard as {@link #finding} and {@link #findingEvidence}, for the
     * same reason: the refs are the claim, so a withheld classifier must not become readable through
     * them. The counts come back on every page for the same reason they do there.
     *
     * <p>Paged rather than embedded in the finding, and that is the point of it existing. The refs used
     * to ride the finding view itself, uncapped, on the detail read AND on the list — so opening the
     * Classifiers page shipped every ref of every finding on it, which on real traffic is tens of
     * thousands of rows nobody rendered.
     */
    public BehaviorDtos.FindingEvidenceSpanPage findingEvidenceSpans(
            String projectId, String findingId, @Nullable String role, int limit, @Nullable String cursor) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        Map<String, Long> recorded = new LinkedHashMap<>();
        for (String r : FindingEvidenceRow.Role.ALL) recorded.put(r, finding.evidenceCount(r));
        FindingEvidenceRepository.SpanPage page = evidence.spanPage(projectId, findingId, role, limit, cursor);
        return new BehaviorDtos.FindingEvidenceSpanPage(
                page.rows().stream().map(BehaviorDtos.EvidenceSpanView::of).toList(),
                page.nextCursor(),
                evidence.countsByRole(projectId, findingId),
                recorded);
    }

    /**
     * Tenant + existence guard for one finding, extended to the flag layer: a finding whose classifier
     * the org does not have is a 404, exactly as the classifier itself is. Without this, hiding a
     * classifier would still leave its findings reachable — and WRITABLE — by id, so a stale tab could
     * hand a withheld finding to Layer 2 or resolve it.
     *
     * <p>Kept here rather than pushed behind the seam because the two evidence reads above are over the
     * shared {@code finding_evidence} table for EVERY classifier, conformance included: the refs are the
     * one part of the surface the stores genuinely share.
     */
    private FindingRow requireReachableFinding(String projectId, String findingId) {
        FindingRow finding = findings.findById(projectId, findingId)
                .orElseThrow(() -> new EvalsException(ClassifierError.FINDING_NOT_FOUND, findingId));
        if (classifiers.unavailableDetectorKinds(projectId).contains(finding.classifierKey())) {
            throw new EvalsException(ClassifierError.FINDING_NOT_FOUND, findingId);
        }
        return finding;
    }

    /**
     * Run Layer-2 on one finding, because a human asked for it.
     *
     * <p>The default and, unless an org opts in, the only way a triage gets enqueued. The sweeps
     * used to do it, and the reason they stopped is that a finding is a LEAD: behaviour drift detects
     * atypical, metric drift detects change, and neither can tell either from a problem — slow is not bad
     * and expensive is not bad. Every automatic escalation was an E2B microVM and an agent session spent
     * to find that out. A person reading the finding can usually tell, and when they cannot, this is the
     * button. {@link TriageAutoEscalator} is the opt-in that presses it unattended, flagged off by
     * default and bounded when on.
     *
     * <p><b>{@code requestedLane} no longer routes anywhere.</b> It used to choose between the triage
     * agent and a grader run over the finding's cited traces; Track A removed grading, so every press
     * takes the triage agent — the one lane there is. The parameter is kept because it is on the wire
     * and a stale tab may still send {@code grader}: accepting and ignoring it gives that press the
     * only ruling the platform can make, rather than a 400 the reader cannot act on.
     *
     * <p>Routing only, since the seam: the first {@link TriageSource} that owns the id handles the
     * press (once-per-cause, payload assembly and the ops log live in the source), and an id no source
     * claims — unknown, or withheld by the org's flag layer — is a 404 exactly as before.
     */
    public BehaviorAnalysisView analyze(String projectId, String findingId, @Nullable String requestedLane) {
        for (TriageSource source : triageSources) {
            Optional<BehaviorAnalysisView> view = source.analyze(projectId, findingId);
            if (view.isPresent()) return view.get();
        }
        throw new EvalsException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }

    public List<BehaviorBaselineEventView> baselineEvents(String projectId, int limit) {
        return events.listByProject(projectId, Math.clamp(limit, 1, 500)).stream()
                .map(BehaviorBaselineEventView::of)
                .toList();
    }

    /**
     * Resolve a finding: allowlist the cause permanently ({@code expected}) or pin it so it never
     * graduates and keeps firing ({@code not_expected}).
     *
     * <p><b>The transaction is annotated HERE, on the entry point, and that is load-bearing.</b> The
     * allowlist row, the gram state, the finding status and the changelog entry are one judgement — a
     * half-applied correction reads as resolved on the finding while the detector keeps firing on it.
     * Moving the annotation onto the adapter would leave the happy path working and nothing would fail
     * until a mid-write error left a finding resolved with no allowlist row behind it.
     *
     * <p>The verb is validated here and the writes are the source's, because the two verbs mean
     * different writes for the different classifiers even though the UI labels and the action strings
     * are the same two. Both labels and both action strings already exist in {@code ClassifiersPage.tsx},
     * so the branching is entirely server-side and no frontend change goes with it.
     */
    @Transactional
    public BehaviorFindingView resolve(String projectId, String findingId, String action, @Nullable String userId) {
        if (!BehaviorResolutionRequest.EXPECTED.equals(action)
                && !BehaviorResolutionRequest.NOT_EXPECTED.equals(action)) {
            throw new EvalsException(ClassifierError.INVALID_RESOLUTION, action);
        }
        for (TriageSource source : triageSources) {
            Optional<BehaviorFindingView> view = source.resolve(projectId, findingId, action, userId);
            if (view.isPresent()) return view.get();
        }
        throw new EvalsException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }
}
