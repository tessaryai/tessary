// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.auth.TenantPathResolver.Resolved;
import ai.tessary.evals.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.evals.git.GitIntegrationDtos.DeleteResponse;
import ai.tessary.evals.git.GitIntegrationDtos.GitIntegrationView;
import ai.tessary.evals.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Connect / inspect / disconnect a project's git provider binding. Owner-gated for mutations. */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/git")
public class GitIntegrationController {

    private final GitIntegrationService service;
    private final TenantPathResolver resolver;

    public GitIntegrationController(GitIntegrationService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<GitIntegrationView> get(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                service.find(r.project().id()).map(GitIntegrationView::from).orElse(null));
    }

    @PostMapping("/connect")
    public ApiResponse<GitIntegrationView> connect(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody ConnectRequest req) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                GitIntegrationView.from(service.connect(r.project().id(), req)));
    }

    @DeleteMapping
    public ApiResponse<DeleteResponse> disconnect(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(new DeleteResponse(service.delete(r.project().id())));
    }

    private Resolved requireOwner(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        if (!r.isOwner() && !ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner role required to manage git integration");
        }
        return r;
    }
}
