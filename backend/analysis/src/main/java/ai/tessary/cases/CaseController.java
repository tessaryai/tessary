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

/** Triage's list and one case's page. Lifecycle is open to resolved only: no claim, owner or "on it" state. */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/cases")
public class CaseController {

    private final CaseService service;
    private final TenantPathResolver resolver;

    public CaseController(CaseService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<TriageView> triage(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.triage(r.project().id()));
    }

    /** {@code id} accepts the stored id or the human reference ({@code C-118}). */
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
     * Only the case id crosses, and only the finding id comes out: the lane gets no earlier verdict or summary (see
     * {@code RcaAnalysisService}). Re-pressing coalesces onto the running report.
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
        return ApiResponse.ok(service.resolve(r.project().id(), id, req.reason(), ctx.userEmail(), req.disposition()));
    }

    /**
     * Closes the case AND moves the detector's reference; {@code /resolve} leaves the bar alone. See
     * {@link CaseService#absorb}.
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
}
