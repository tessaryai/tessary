// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.search;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.search.GlobalSearchDtos.GlobalSearchView;
import ai.tessary.evals.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The UI-facing global search surface: one tenant-scoped full-text query across a project's
 * content entities (failure modes, graders, datasets, traces), returning a ranked, typed result list that the
 * ⌘K command palette consumes as an async command source, filling the palette's deliberate
 * async-source seam.
 *
 * <p>On the UI tenant path, NOT the token-scoped {@code /v1/query} API family that
 * {@code QueryController} serves: an operator searches from the app, so access is org-membership-scoped
 * via {@link TenantPathResolver#requireProject} and the resolved {@code project().id()} is the single
 * tenant boundary passed into the service. READ-ONLY — never writes to any store.
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

    /**
     * Ranked content matches for {@code q} within the resolved project, best-first across entity types.
     * A blank/absent query returns an empty hit list (not an error) — the palette degrades to its static
     * commands. Tenant-scoped: results can only come from the project the caller's org membership
     * resolves to, and a query never carries a project id.
     */
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
