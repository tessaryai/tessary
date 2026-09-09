// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.sources.SourceDtos.CreateSourceRequest;
import ai.tessary.sources.SourceDtos.DeleteResponse;
import ai.tessary.sources.SourceDtos.SourceResponse;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/sources")
public class SourceController {

    private final SourceService service;
    private final TenantPathResolver resolver;

    public SourceController(SourceService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<List<SourceResponse>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                service.list(r.project().id()).stream().map(SourceResponse::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<SourceResponse> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(SourceResponse.of(service.get(r.project().id(), id)));
    }

    @PostMapping
    public ApiResponse<SourceResponse> create(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody CreateSourceRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        SourceRow created = service.create(r.project().id(), req);
        return ApiResponse.ok(SourceResponse.of(created));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<DeleteResponse> delete(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        service.delete(r.project().id(), id);
        return ApiResponse.ok(new DeleteResponse(true));
    }
}
