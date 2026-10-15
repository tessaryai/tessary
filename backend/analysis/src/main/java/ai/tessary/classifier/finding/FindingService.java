// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
import ai.tessary.classifier.detector.groundedness.GroundednessDetailService;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.frustration.FrustrationDetailService;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.frustration.FrustrationRateRepository;
import ai.tessary.classifier.malformed.MalformedOutputDetailService;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The findings read and correction surface for every classifier. Listing, detail, resolution and the dossier route
 * through {@link TriageSource} in {@code @Order}, and the first source that claims an id handles it. The shared-table
 * reads (evidence and the reachability guard) stay here. A correction is an auditable, reversible ROW on the
 * {@code annotation} channel, never an in-place mutation of what it corrects.
 */
@Service
public class FindingService {

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ClassifierService classifiers;
    /** In registration order, the shared table first. Each source owns its reads and writes; this class only routes. */
    private final List<TriageSource> triageSources;

    private final MalformedOutputDetailService malformedOutputs;

    private final FrustrationDetailService frustrations;

    private final GroundednessDetailService groundedness;

    public FindingService(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierService classifiers,
            List<TriageSource> triageSources,
            MalformedOutputDetailService malformedOutputs,
            FrustrationDetailService frustrations,
            GroundednessDetailService groundedness) {
        this.findings = findings;
        this.evidence = evidence;
        this.classifiers = classifiers;
        this.triageSources = triageSources;
        this.malformedOutputs = malformedOutputs;
        this.frustrations = frustrations;
        this.groundedness = groundedness;
    }

    /**
     * Layer-2 gated by default: Layer 1 cannot tell change from a problem, so the unfiltered stream
     * ({@code confirmedOnly=false}) is a lead list. The page concatenates sources in {@code @Order}, which is
     * wire-observable, each paging to its own limit.
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
        return new BehaviorFindingsView(rows, TriageLane.EVIDENCE_ONLY.wire());
    }

    /**
     * Each source's reachability guard makes a withheld classifier's finding a 404 here too. No evidence on the detail:
     * {@link #findingEvidenceSpans} pages it.
     */
    public BehaviorFindingDetailView finding(String projectId, String findingId) {
        for (TriageSource source : triageSources) {
            Optional<BehaviorFindingDetailView> view = source.detail(projectId, findingId);
            if (view.isPresent()) return view.get();
        }
        throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }

    /** Behind the same reachability guard as {@link #finding}: the refs ARE the claim. */
    public BehaviorDtos.FindingEvidencePage findingEvidence(String projectId, String findingId) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        Map<String, Long> recorded = new LinkedHashMap<>();
        for (String r : FindingEvidenceRow.Role.ALL) recorded.put(r, finding.evidenceCount(r));
        Map<String, Long> live = evidence.countsByRole(projectId, findingId);
        return new BehaviorDtos.FindingEvidencePage(List.of(), null, true, live, recorded);
    }

    /**
     * Same guard and counts as {@link #findingEvidence}. Paged rather than embedded, since a finding can cite tens of
     * thousands of refs.
     */
    public BehaviorDtos.FindingEvidenceSpanPage findingEvidenceSpans(
            String projectId, String findingId, @Nullable String role, int limit, @Nullable String cursor) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        Map<String, Long> recorded = new LinkedHashMap<>();
        for (String r : FindingEvidenceRow.Role.ALL) recorded.put(r, finding.evidenceCount(r));
        boolean secretLeak = BuiltInDetector.Kind.SECRET_LEAK.equals(finding.classifierKey());
        boolean malformedOutput = BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(finding.classifierKey());
        FindingEvidenceRepository.SpanPage page =
                evidence.spanPage(projectId, findingId, role, limit, cursor, secretLeak, malformedOutput);
        return new BehaviorDtos.FindingEvidenceSpanPage(
                page.rows().stream().map(BehaviorDtos.EvidenceSpanView::of).toList(),
                page.nextCursor(),
                evidence.countsByRole(projectId, findingId),
                recorded);
    }

    /**
     * A field with no failures, or a non-{@code malformed_rate} finding, is an empty page rather than a 404: the field
     * is selected from the finding's own schema tree.
     */
    public MalformedOutputEvidence.FailingOutputPage malformedOutputs(
            String projectId, String findingId, String field, int limit, @Nullable String cursor) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        return malformedOutputs.failingOutputs(finding, field, limit, cursor);
    }

    /** {@code rcaReport} and {@code cause} narrow to one RCA cause's share; both or neither. */
    public FrustrationEvidence.FrustratedSessionPage frustratedSessions(
            String projectId,
            String findingId,
            @Nullable String rcaReport,
            @Nullable Integer cause,
            int limit,
            @Nullable String cursor) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        if (!FindingRow.Cause.FRUSTRATION_RATE.equals(finding.causeKind())) {
            return new FrustrationEvidence.FrustratedSessionPage(List.of(), 0, null);
        }
        FrustrationRateRepository.CauseRef ref =
                rcaReport == null || cause == null ? null : new FrustrationRateRepository.CauseRef(rcaReport, cause);
        return frustrations.page(finding, ref, limit, cursor);
    }

    /** {@code rcaReport} and {@code cause} narrow to the traces that RCA cause names; both or neither. */
    public GroundednessEvidence.FlaggedAnswerPage flaggedAnswers(
            String projectId,
            String findingId,
            @Nullable String rcaReport,
            @Nullable Integer cause,
            int limit,
            @Nullable String cursor) {
        FindingRow finding = requireReachableFinding(projectId, findingId);
        if (!FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) {
            return new GroundednessEvidence.FlaggedAnswerPage(List.of(), 0, null);
        }
        GroundednessRateRepository.CauseRef ref =
                rcaReport == null || cause == null ? null : new GroundednessRateRepository.CauseRef(rcaReport, cause);
        return groundedness.page(finding, ref, limit, cursor);
    }

    public Map<SpanRef, List<GroundednessEvidence.FlaggedSentenceText>> flaggedSentences(
            String projectId, String findingId, Collection<SpanRef> refs) {
        // Only groundedness has an answer; one row read settles the kind before the guard runs on it.
        FindingRow finding = findings.findById(projectId, findingId).orElse(null);
        if (finding == null || !FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) return Map.of();
        return groundedness.flaggedSentences(requireReachable(projectId, finding), refs);
    }

    /**
     * A withheld classifier's finding is a 404, or a stale tab could still read, triage or resolve it by id. Kept here
     * because the evidence reads span every classifier's rows.
     */
    private FindingRow requireReachableFinding(String projectId, String findingId) {
        FindingRow finding = findings.findById(projectId, findingId)
                .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId));
        return requireReachable(projectId, finding);
    }

    private FindingRow requireReachable(String projectId, FindingRow finding) {
        if (classifiers.unavailableDetectorKinds(projectId).contains(finding.classifierKey())) {
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
        }
        return finding;
    }

    /**
     * Run Layer 2 on one finding because a human asked. A finding is a lead, so automatic escalation is opt-in
     * ({@link TriageAutoEscalator}). {@code requestedLane} is accepted and ignored: a stale tab may still send
     * {@code grader}, and there is only one lane. An id no source claims is a 404.
     */
    public BehaviorAnalysisView analyze(String projectId, String findingId, @Nullable String requestedLane) {
        for (TriageSource source : triageSources) {
            Optional<BehaviorAnalysisView> view = source.analyze(projectId, findingId);
            if (view.isPresent()) return view.get();
        }
        throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }

    /**
     * The transaction is annotated HERE, on the entry point: the allowlist row, gram state, finding status and
     * changelog are one judgement, and a half-applied correction reads as resolved while the detector keeps firing. The
     * verb is validated here; the writes are the source's.
     */
    @Transactional
    public BehaviorFindingView resolve(String projectId, String findingId, String action, @Nullable String userId) {
        if (!BehaviorResolutionRequest.EXPECTED.equals(action)
                && !BehaviorResolutionRequest.NOT_EXPECTED.equals(action)) {
            throw new TessaryException(ClassifierError.INVALID_RESOLUTION, action);
        }
        for (TriageSource source : triageSources) {
            Optional<BehaviorFindingView> view = source.resolve(projectId, findingId, action, userId);
            if (view.isPresent()) return view.get();
        }
        throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId);
    }
}
