// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.plan.RequiresCapability;
import ai.tessary.promptlib.PromptDtos.CreatePromptRequest;
import ai.tessary.promptlib.PromptDtos.CreateVersionRequest;
import ai.tessary.promptlib.PromptDtos.DeleteResponse;
import ai.tessary.promptlib.PromptDtos.FolderDeleteResponse;
import ai.tessary.promptlib.PromptDtos.PromptPage;
import ai.tessary.promptlib.PromptDtos.PromptVersionView;
import ai.tessary.promptlib.PromptDtos.SetLabelsRequest;
import ai.tessary.promptlib.PromptDtos.UpdateTagsRequest;
import ai.tessary.promptlib.PromptService.CreatePromptCommand;
import ai.tessary.promptlib.PromptService.CreateVersionCommand;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.LabelMode;
import ai.tessary.promptlib.PromptService.PromptListQuery;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.traces.TracePageCodec;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Prompts page API. Reads need {@code ORG_VIEW}, writes {@code ORG_MANAGE}; a bearer key also needs the QUERY
 * scope to read and ADMIN to write, because every key is role {@code member} and RBAC alone would admit an ingest key.
 * Both POSTs answer 200, as {@code ResponseMeta.ok()} does.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/prompts")
@RequiresCapability(Capability.PROMPT_MANAGEMENT)
public class PromptController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final PromptService service;
    private final TenantPathResolver resolver;

    public PromptController(PromptService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    /** {@code name} is an exact match, at most one item: the span chip resolves a span's prompt to an id through it. */
    @GetMapping
    public ApiResponse<PromptPage> list(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String name,
            @RequestParam(required = false) @Nullable String folder,
            @RequestParam(required = false) @Nullable String label,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable Integer page,
            @RequestParam(required = false) @Nullable Integer limit) {
        Resolved r = view(ctx, orgSlug, projectSlug);
        PromptListQuery query = new PromptListQuery(
                blankToNull(name),
                blankToNull(folder),
                blankToNull(q),
                blankToNull(label),
                blankToNull(tag),
                type == null ? null : PromptType.fromWire(type),
                null,
                null,
                PromptListQuery.Order.UPDATED,
                page == null ? 1 : page,
                TracePageCodec.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT));
        return ApiResponse.ok(PromptPage.from(service.list(r.project().id(), query)));
    }

    @PostMapping
    public ApiResponse<PromptDtos.PromptDetail> create(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody CreatePromptRequest req) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        CreatePromptCommand cmd = new CreatePromptCommand(
                req.name(),
                req.type(),
                req.body(),
                req.config(),
                orEmpty(req.labels()),
                orEmpty(req.tags()),
                req.commitMessage());
        return ApiResponse.ok(detail(service.create(r.project().id(), ctx.userId(), CreateVia.UI, cmd)));
    }

    @DeleteMapping("/folders")
    public ApiResponse<FolderDeleteResponse> deleteFolder(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam String path) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                new FolderDeleteResponse(service.deleteFolder(r.project().id(), path)));
    }

    @GetMapping("/{promptId}")
    public ApiResponse<PromptDtos.PromptDetail> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId) {
        Resolved r = view(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(detail(service.get(r.project().id(), promptId)));
    }

    @PatchMapping("/{promptId}")
    public ApiResponse<PromptDtos.PromptDetail> updateTags(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId,
            @Valid @RequestBody UpdateTagsRequest req) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(detail(service.updateTags(r.project().id(), promptId, req.tags())));
    }

    @DeleteMapping("/{promptId}")
    public ApiResponse<DeleteResponse> delete(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(new DeleteResponse(service.delete(r.project().id(), promptId)));
    }

    @GetMapping("/{promptId}/versions/{version}")
    public ApiResponse<PromptVersionView> getVersion(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId,
            @PathVariable int version) {
        Resolved r = view(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(version(service.getVersion(r.project().id(), promptId, version)));
    }

    @PostMapping("/{promptId}/versions")
    public ApiResponse<PromptVersionView> createVersion(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId,
            @Valid @RequestBody CreateVersionRequest req) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        CreateVersionCommand cmd =
                new CreateVersionCommand(req.body(), req.config(), orEmpty(req.labels()), req.commitMessage());
        return ApiResponse.ok(
                version(service.createVersion(r.project().id(), promptId, ctx.userId(), CreateVia.UI, cmd)));
    }

    /** 409 {@code VERSION_LABELED} while a label points here; 409 {@code LAST_VERSION} for the only one. */
    @DeleteMapping("/{promptId}/versions/{version}")
    public ApiResponse<DeleteResponse> deleteVersion(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId,
            @PathVariable int version) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                new DeleteResponse(service.deleteVersion(r.project().id(), promptId, version)));
    }

    /** Promote is {@code ["production", ...]} on a version; rollback is the same call on an older one. */
    @PutMapping("/{promptId}/versions/{version}/labels")
    public ApiResponse<PromptVersionView> setLabels(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId,
            @PathVariable int version,
            @Valid @RequestBody SetLabelsRequest req) {
        Resolved r = manage(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(version(service.setLabels(
                r.project().id(),
                promptId,
                version,
                ctx.userId(),
                new LinkedHashSet<>(req.labels()),
                LabelMode.REPLACE)));
    }

    private PromptDtos.PromptDetail detail(PromptService.PromptDetail d) {
        return PromptDtos.PromptDetail.from(
                d,
                service.authors(
                        d.versions().stream().map(PromptVersionRow::createdBy).toList()));
    }

    private PromptVersionView version(PromptService.ResolvedVersion v) {
        return PromptVersionView.from(v, service.authors(List.of(v.version().createdBy())));
    }

    private Resolved view(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view prompts");
        if (ctx.isMcpToken() && !ctx.keyPermits(KeyScope.QUERY)) {
            throw new TessaryException(PromptsError.WRONG_KEY_SCOPE, "read");
        }
        return r;
    }

    private Resolved manage(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "change prompts");
        if (ctx.isMcpToken() && !ctx.keyPermits(KeyScope.ADMIN)) {
            throw new TessaryException(PromptsError.WRONG_KEY_SCOPE, "change");
        }
        return r;
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static List<String> orEmpty(@Nullable List<String> list) {
        return list == null ? List.of() : list;
    }
}
