// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.read.EndUserDtos.EndUserCaseBlock;
import ai.tessary.endusers.read.EndUserDtos.EndUserEraseRequest;
import ai.tessary.endusers.read.EndUserDtos.EndUserErasure;
import ai.tessary.endusers.read.EndUserDtos.EndUserHitCounts;
import ai.tessary.endusers.read.EndUserDtos.EndUserIdPage;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssue;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueList;
import ai.tessary.endusers.read.EndUserDtos.EndUserOverview;
import ai.tessary.endusers.read.EndUserDtos.EndUserSilentIds;
import ai.tessary.open.errors.EndUserError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Users view. Every read needs {@code ORG_VIEW} on the project; {@code {caseId}} takes a case ULID or a reference
 * such as {@code C-118}. The erasure needs a signed-in session and {@code ORG_MANAGE}: a token context carries role
 * member, which holds {@code ORG_MANAGE}, so the session check comes first. No user ID is ever in a path.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/end-users")
public class EndUserController {

    private final EndUserReadService service;
    private final TenantPathResolver tenants;

    public EndUserController(EndUserReadService service, TenantPathResolver tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @GetMapping("/overview")
    public ApiResponse<EndUserOverview> overview(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        return ApiResponse.ok(service.overview(read(ctx, orgSlug, projectSlug)));
    }

    @GetMapping("/issues")
    public ApiResponse<EndUserIssueList> issues(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        return ApiResponse.ok(service.issues(read(ctx, orgSlug, projectSlug)));
    }

    @GetMapping("/issues/{caseId}")
    public ApiResponse<EndUserIssue> issue(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String caseId) {
        return ApiResponse.ok(service.issue(read(ctx, orgSlug, projectSlug), caseId));
    }

    @GetMapping("/issues/{caseId}/silent-ids")
    public ApiResponse<EndUserSilentIds> silentIds(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String caseId,
            @RequestParam(required = false) @Nullable String cursor) {
        return ApiResponse.ok(service.silentIds(read(ctx, orgSlug, projectSlug), caseId, cursor));
    }

    /** @param stage active, silent or stopped */
    @GetMapping("/ids")
    public ApiResponse<EndUserIdPage> ids(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam String stage,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + EndUserReadService.DEFAULT_LIMIT) int limit) {
        String projectId = read(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.ids(projectId, Stage.fromWire(stage), cursor, limit));
    }

    @GetMapping("/cases/{caseId}")
    public ApiResponse<EndUserCaseBlock> caseBlock(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String caseId) {
        return ApiResponse.ok(service.caseBlock(read(ctx, orgSlug, projectSlug), caseId));
    }

    @GetMapping("/hit-counts")
    public ApiResponse<EndUserHitCounts> hitCounts(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        return ApiResponse.ok(service.hitCounts(read(ctx, orgSlug, projectSlug)));
    }

    /** Deletes every stored row of one user ID. The ID travels in the body only. */
    @PostMapping("/erasures")
    public ApiResponse<EndUserErasure> erase(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody EndUserEraseRequest req) {
        var r = tenants.requireProject(ctx, orgSlug, projectSlug);
        if (ctx.isMcpToken()) throw new TessaryException(EndUserError.ERASE_NEEDS_SESSION);
        r.require(Permission.ORG_MANAGE, "erase a user ID");
        return ApiResponse.ok(service.erase(r.project().id(), req.userId()));
    }

    private String read(TenantContext ctx, String orgSlug, String projectSlug) {
        var r = tenants.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "read the Users view");
        return r.project().id();
    }
}
