// SPDX-License-Identifier: Apache-2.0
package ai.tessary.search;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.search.GlobalSearchDtos.GlobalSearchView;
import ai.tessary.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global search for the ⌘K palette. On the UI tenant path, not the token-scoped {@code /v1/query}: access is
 * org-membership-scoped via {@link TenantPathResolver#requireProject}. Read-only.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/search")
public class GlobalSearchController {

    private final GlobalSearchService service;
    private final TenantPathResolver resolver;

    public GlobalSearchController(GlobalSearchService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    /** A blank query returns no hits rather than an error. */
    @GetMapping
    public ApiResponse<GlobalSearchView> search(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "q", required = false, defaultValue = "") String q) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(GlobalSearchView.of(service.search(r.project().id(), q)));
    }
}
