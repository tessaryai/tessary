// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.debug.ClassifierDebugDtos.ClassifierDebugView;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug surface for one classifier — everything the production {@code ClassifierController} already
 * computes or persists but does not expose. Mounted standalone (not on {@code ClassifierController})
 * on purpose: this endpoint is ops tooling, not a product surface, so it carries no entitlement and
 * removing the feature later is deleting this package, not editing the production controller.
 *
 * <p>Gated by {@link Permission#ORG_MANAGE} — the same permission {@code
 * ClassifierController#setEnabled}/{@code #setMode} already require, so this needs no new permission
 * tier of its own.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/classifiers/{id}/debug")
public class ClassifierDebugController {

    private final ClassifierDebugService service;
    private final TenantPathResolver resolver;

    public ClassifierDebugController(ClassifierDebugService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<ClassifierDebugView> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "read classifier debug detail");
        return ApiResponse.ok(service.debug(r.project().id(), id));
    }
}
