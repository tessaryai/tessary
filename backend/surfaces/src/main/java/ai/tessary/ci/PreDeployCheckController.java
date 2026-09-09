// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ci;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.gate.PreDeployCheckDtos.PreDeployCheckView;
import ai.tessary.gate.PreDeployCheckService;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the pre-deploy checks a project's discovered signals registered, and lets a human
 * dismiss or reinstate a noisy one without disabling its signal. Registration itself happens
 * automatically, via the async {@code ClassifierWorker} sweep behind {@code tessary.predeploy.enabled}.
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

    /** Resolve the project and require the CI integration entitlement. */
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
