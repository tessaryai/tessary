// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.errors.VersionError;
import ai.tessary.version.CommitLineageService.NodeKind;
import ai.tessary.version.ProjectVersionDtos.ProjectVersionView;
import ai.tessary.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The project's version timeline — one entry per commit SHA the platform has
 * attached to (pipeline sync, benchmark run, or observer finding). Drives the
 * benchmark-by-version selector in the UI so metrics never blend across SHAs.
 *
 * <p>{@code /lineage/{nodeKind}/{nodeId}} is the resolution surface of the commit-SHA lineage
 * spine: any node — verdict, substrate grain, entry, run, observer alert — resolves to the
 * exact commit it belongs to (see {@link CommitLineageService}).
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/versions")
public class ProjectVersionController {

    private final ProjectVersionService service;
    private final CommitLineageService lineage;
    private final TenantPathResolver resolver;

    public ProjectVersionController(
            ProjectVersionService service, CommitLineageService lineage, TenantPathResolver resolver) {
        this.service = service;
        this.lineage = lineage;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<List<ProjectVersionView>> timeline(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.timeline(r.project().id()));
    }

    @GetMapping("/{commitSha}")
    public ApiResponse<ProjectVersionView> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String commitSha) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.get(r.project().id(), commitSha));
    }

    /** Resolve any lineage node to the project version (commit SHA) that caused it. */
    @GetMapping("/lineage/{nodeKind}/{nodeId}")
    public ApiResponse<ProjectVersionView> lineage(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String nodeKind,
            @PathVariable String nodeId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        NodeKind kind = NodeKind.parse(nodeKind)
                .orElseThrow(() -> new TessaryException(VersionError.UNKNOWN_NODE_KIND, nodeKind));
        return ApiResponse.ok(lineage.resolve(r.project().id(), kind, nodeId)
                .map(ProjectVersionView::from)
                .orElseThrow(() -> new TessaryException(VersionError.LINEAGE_UNRESOLVED, nodeKind, nodeId)));
    }
}
