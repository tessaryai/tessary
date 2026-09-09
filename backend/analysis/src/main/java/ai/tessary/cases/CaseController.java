// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.ResolveCaseRequest;
import ai.tessary.cases.CaseDtos.TriageView;
import ai.tessary.plan.Capability;
import ai.tessary.plan.RequiresCapability;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The case surface — Triage's list and one case's page.
 *
 * <p>Lifecycle is deliberately small: open → resolved, plus muted. There is no claim, no owner and no
 * "on it" state, so there is no endpoint for one. Multiple people can act on any case; the product
 * does not model who is holding it.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/cases")
public class CaseController {

    private final CaseService service;
    private final TenantPathResolver resolver;

    public CaseController(CaseService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    /** Triage: open cases worst first, plus the muted set, the week's closures and the coverage the
     *  all-clear state cites. */
    @GetMapping
    public ApiResponse<TriageView> triage(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.triage(r.project().id()));
    }

    /** One case. {@code id} accepts the stored id or the human reference ({@code C-118}), so a link
     *  quoting the number a person read resolves. */
    @GetMapping("/{id}")
    public ApiResponse<CaseDetailView> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.detail(r.project().id(), id));
    }

    /**
     * <em>Run RCA</em>: hand the finding behind this case to Layer 3. The press is the whole decision —
     * a person has read the case, including everything triage wrote on it, and judged the cause worth a
     * strong model's time.
     *
     * <p><b>Only the case id crosses, and only the finding id comes back out of it.</b> The lane is
     * given no verdict, no summary and no rule-outs from any earlier pass — see
     * {@code RcaAnalysisService}'s firewall section for why the boundary sits at the agent rather than
     * at the human. Re-pressing coalesces onto the running report; re-running a finished one is the RCA
     * surface's own {@code /rca/{id}/rerun}.
     */
    @RequiresCapability(Capability.RCA)
    @PostMapping("/{id}/rca")
    public ApiResponse<RcaReportView> runRca(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "run RCA on a case");
        return ApiResponse.ok(service.runRca(r.project().id(), id, ctx.userId()));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<CaseView> resolve(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody ResolveCaseRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.resolve(r.project().id(), id, req.reason(), ctx.userEmail()));
    }

    /**
     * <em>Legitimate — absorb</em>: close the case AND move the detector's reference so the level it
     * fired on becomes the new baseline. Distinct from {@code /resolve}, which closes this case and
     * leaves the bar alone — see {@link CaseService#absorb}.
     */
    @PostMapping("/{id}/absorb")
    public ApiResponse<CaseView> absorb(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.absorb(r.project().id(), id, ctx.userEmail()));
    }

    @PostMapping("/{id}/mute")
    public ApiResponse<CaseView> mute(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.mute(r.project().id(), id, ctx.userEmail()));
    }

    @PostMapping("/{id}/unmute")
    public ApiResponse<CaseView> unmute(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.unmute(r.project().id(), id, ctx.userEmail()));
    }
}
