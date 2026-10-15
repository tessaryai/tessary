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
 * Ops-only debug surface, mounted apart from {@code ClassifierController} so removing it is deleting a package. Carries
 * no entitlement; gated by {@link Permission#ORG_MANAGE}.
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
