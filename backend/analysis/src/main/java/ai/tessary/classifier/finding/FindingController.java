// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorBaselineEventView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The findings read surface: the open findings, the correction loop, and the baseline changelog.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/findings")
public class FindingController {

    /** Page ceiling. Generous for a table, and still a bound: the sets behind it reach 27k rows. */
    private static final int MAX_EVIDENCE_PAGE = 200;

    private static final int DEFAULT_EVENT_LIMIT = 100;

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

    /**
     * One finding with its evidence parsed: what the finding's own page reads.
     *
     * <p>A separate call rather than a fatter list row: the evidence blob is the largest thing a finding
     * carries, and a list of fifty would ship fifty of them to render fifty headlines.
     */
    @GetMapping("/{id}")
    public ApiResponse<BehaviorFindingDetailView> finding(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.finding(r.project().id(), id));
    }

    /**
     * One page of a finding's evidence, joined to the spans it names: what the finding page's evidence
     * table renders.
     *
     * <p>The default page is small on purpose: a reader looks at the first few and pages on only if
     * the shape isn't already obvious.
     */
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
     * Record the human judgement on a finding. Marking it Expected allowlists the gram permanently and
     * skips the graduation wait; Not expected pins it in quarantine so it never graduates and keeps
     * firing. Doing nothing is also a valid answer: persistence graduation then runs as normal.
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
     * Hand this finding to Layer 2: a sandbox with the finding's claim materialized as a dossier and
     * this platform's read surface for the evidence behind it, no repository on any project.
     *
     * <p>{@code lane=grader} runs the call site's own rubrics against the same cited traces instead
     * of the triage agent, writing to the same {@code triage_*} columns. Omitted or unrecognized
     * means the triage agent.
     *
     * <p>409 without an exemplar trace, in either lane; never 409 for a missing repo, since triage
     * never opens one. A second call returns 200 with {@code alreadyEscalated} instead of triaging
     * twice.
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

    @GetMapping("/baseline-events")
    public ApiResponse<List<BehaviorBaselineEventView>> baselineEvents(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_EVENT_LIMIT) int limit) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.baselineEvents(r.project().id(), limit));
    }
}
