// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/findings")
public class FindingController {

    /** The sets behind it reach 27k rows. */
    private static final int MAX_EVIDENCE_PAGE = 200;

    private final FindingService service;
    private final TenantPathResolver resolver;

    public FindingController(FindingService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping("")
    public ApiResponse<BehaviorFindingsView> findings(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "callSiteId", required = false) @Nullable String callSiteId,
            @RequestParam(name = "detector", required = false) @Nullable String detector,
            @RequestParam(name = "include", defaultValue = "confirmed") String include) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        boolean confirmedOnly = !"all".equals(include);
        return ApiResponse.ok(service.findings(r.project().id(), status, callSiteId, detector, confirmedOnly));
    }

    /** Separate from the list because the evidence blob is the largest thing a finding carries. */
    @GetMapping("/{id}")
    public ApiResponse<BehaviorFindingDetailView> finding(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.finding(r.project().id(), id));
    }

    /** The default page is small on purpose. */
    @GetMapping("/{id}/evidence")
    public ApiResponse<BehaviorDtos.FindingEvidenceSpanPage> findingEvidence(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String cursor) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.findingEvidenceSpans(
                r.project().id(), id, role, Math.clamp(limit, 1, MAX_EVIDENCE_PAGE), cursor));
    }

    /**
     * {@code field} is a declared field's collapsed path ({@code items[].sku}), or {@code not_json} / {@code other}.
     */
    @GetMapping("/{id}/malformed-outputs")
    public ApiResponse<MalformedOutputEvidence.FailingOutputPage> malformedOutputs(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam String field,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String cursor) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                service.malformedOutputs(r.project().id(), id, field, Math.clamp(limit, 1, MAX_EVIDENCE_PAGE), cursor));
    }

    /**
     * Newest flag first. {@code rcaReport} and {@code cause} (0-based position in that report) narrow it to one RCA
     * cause's sessions.
     */
    @GetMapping("/{id}/frustrated-sessions")
    public ApiResponse<FrustrationEvidence.FrustratedSessionPage> frustratedSessions(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String rcaReport,
            @RequestParam(required = false) @Nullable Integer cause,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.frustratedSessions(
                r.project().id(), id, rcaReport, cause, Math.clamp(limit, 1, MAX_EVIDENCE_PAGE), cursor));
    }

    /** Newest flag first; {@code rcaReport} and {@code cause} filter as in {@link #frustratedSessions}. */
    @GetMapping("/{id}/flagged-answers")
    public ApiResponse<GroundednessEvidence.FlaggedAnswerPage> flaggedAnswers(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String rcaReport,
            @RequestParam(required = false) @Nullable Integer cause,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.flaggedAnswers(
                r.project().id(), id, rcaReport, cause, Math.clamp(limit, 1, MAX_EVIDENCE_PAGE), cursor));
    }

    /**
     * Expected allowlists the gram permanently; Not expected pins it in quarantine. Doing nothing lets persistence
     * graduation run as normal.
     */
    @PostMapping("/{id}/resolution")
    public ApiResponse<BehaviorFindingView> resolve(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody BehaviorResolutionRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.resolve(r.project().id(), id, req.action(), ctx.userId()));
    }

    /**
     * {@code lane=grader} runs the call site's own rubrics instead of the triage agent, into the same {@code triage_*}
     * columns. 409 without an exemplar trace; a second call returns 200 with {@code alreadyEscalated}.
     */
    @PostMapping("/{id}/analysis")
    public ApiResponse<BehaviorAnalysisView> analyze(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam(name = "lane", required = false) @Nullable String lane) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "run Layer-2 analysis on a finding");
        return ApiResponse.ok(service.analyze(r.project().id(), id, lane));
    }
}
