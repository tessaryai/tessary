// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ci;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.auth.TenantPathResolver.Resolved;
import ai.tessary.evals.gate.PreDeployCheckDtos.PreDeployCheckView;
import ai.tessary.evals.gate.PreDeployCheckService;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-deploy check read / lifecycle surface: list the checks a project's discovered signals
 * registered, and dismiss/reinstate a noisy one WITHOUT disabling its signal. The registration itself is
 * automatic (the async {@code ClassifierWorker} sweep, behind {@code evals.predeploy.enabled}); this controller
 * makes the closed loop visible and lets a human curate it — mirroring {@code ClassifierController}'s posture.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/predeploy-checks")
public class PreDeployCheckController {

    private final PreDeployCheckService service;
    private final TenantPathResolver resolver;
    private final CapabilityService capabilities;

    public PreDeployCheckController(
            PreDeployCheckService service, TenantPathResolver resolver, CapabilityService capabilities) {
        this.service = service;
        this.resolver = resolver;
        this.capabilities = capabilities;
    }

    /** CI is a paid capability ({@link Feature#CI_INTEGRATION}): resolve the project AND require the entitlement. */
    private Resolved requireCapableProject(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        capabilities.require(r.org().id(), Capability.CI_INTEGRATION);
        return r;
    }

    @GetMapping
    public ApiResponse<List<PreDeployCheckView>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.list(r.project().id()));
    }

    @PostMapping("/{id}/dismiss")
    public ApiResponse<Void> dismiss(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        service.dismiss(r.project().id(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reinstate")
    public ApiResponse<Void> reinstate(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        service.reinstate(r.project().id(), id);
        return ApiResponse.ok(null);
    }
}
