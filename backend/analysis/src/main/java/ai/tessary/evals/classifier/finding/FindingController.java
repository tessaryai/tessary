// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorBaselineEventView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.evals.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.evals.tenant.rbac.Permission;
import ai.tessary.evals.web.ApiResponse;
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
 * {@code /behavior/profiles}, the one route reading the paid {@link ProfileSource} port, moved to
 * {@code tessary-paid/behavior-drift} in #919 — see that module's {@code BehaviorProfileController},
 * which keeps its own separate {@code @RequestMapping} at the old {@code .../behavior} prefix.
 * This class was renamed from {@code BehaviorController} to {@code FindingController} in #921,
 * once the paid route above was the only thing still hanging off the {@code /behavior} name.
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
     * One finding with its evidence parsed — what the finding's own page reads.
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
     * One page of a finding's evidence, joined to the spans it names — what the finding page's evidence
     * table renders.
     *
     * <p>Paged because a population is not a field. This set used to ride the finding view itself,
     * uncapped, on this endpoint AND on the list beside it, so a single render of the Classifiers page
     * shipped every ref of every finding on it. The default page is deliberately small: a reader looks
     * at the first few and pages on if the shape is not already obvious.
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
     * firing. Doing nothing is also a valid answer — persistence graduation then runs as normal.
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
     * Hand this finding to Layer 2 — a sandbox with the finding's claim materialized as a dossier and
     * this platform's read surface for the evidence behind it. No repository, on any project.
     *
     * <p>The only thing that enqueues a triage. Both drift sweeps used to do it automatically;
     * they no longer do, because a finding is a lead and every automatic escalation was a microVM
     * spent to find out whether it was more than that.
     *
     * <p><b>{@code lane=grader} picks the graders instead</b>: the call site's own rubrics, run against
     * the same cited traces, ruling onto the same {@code triage_*} columns. Omitted (or unrecognized)
     * means the triage agent, which is what the button always did.
     *
     * <p>409 without an exemplar trace — nothing to rule on, in either lane. <b>Not</b> 409 without a
     * repo: triage never opens one, so a project with no connection reaches exactly the run every other
     * project reaches. A second press returns 200 with {@code alreadyEscalated} — the cause is triaged
     * once, and the press lands on the existing job.
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
