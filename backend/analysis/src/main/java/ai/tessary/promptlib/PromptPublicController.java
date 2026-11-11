// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.promptlib.PromptPublicDtos.DeleteResponse;
import ai.tessary.promptlib.PromptPublicDtos.PromptList;
import ai.tessary.promptlib.PromptPublicDtos.PublicCreatePromptRequest;
import ai.tessary.promptlib.PromptPublicDtos.PublicPrompt;
import ai.tessary.promptlib.PromptPublicDtos.UpdateLabelsRequest;
import ai.tessary.promptlib.PromptService.CreatePromptCommand;
import ai.tessary.promptlib.PromptService.CreateVersionCommand;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.LabelMode;
import ai.tessary.promptlib.PromptService.PromptListQuery;
import ai.tessary.tenant.KeyScope;
import ai.tessary.traces.TracePageCodec;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The headless prompts API, a common prompt API shape over a project-scoped bearer key. Outside {@code /api/**}, so it
 * checks the key and the capability itself: reads need a QUERY or ADMIN key, writes an ADMIN key, and an ingest key gets
 * nothing, so a leaked one cannot read a body or move {@code production}. The scope check runs before the capability
 * check, so a key that may not read learns nothing about the org. {@code name} is always a query parameter, because a
 * name may hold {@code /} and Tomcat rejects {@code %2F} in a path.
 */
@RestController
@RequestMapping("/v1/prompts")
public class PromptPublicController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final PromptService service;
    private final CapabilityService capabilities;

    public PromptPublicController(PromptService service, CapabilityService capabilities) {
        this.service = service;
        this.capabilities = capabilities;
    }

    /** Neither {@code version} nor {@code label} reads {@code production}; {@code label=latest} the newest version. */
    @GetMapping("/fetch")
    public ApiResponse<PublicPrompt> fetch(
            TenantContext ctx,
            @RequestParam(required = false) @Nullable String name,
            @RequestParam(required = false) @Nullable Integer version,
            @RequestParam(required = false) @Nullable String label) {
        String projectId = project(ctx, KeyScope.QUERY, "read");
        return ApiResponse.ok(PublicPrompt.from(service.resolve(projectId, requireName(name), version, label)));
    }

    /** Ordered by name. */
    @GetMapping
    public ApiResponse<PromptList> list(
            TenantContext ctx,
            @RequestParam(required = false) @Nullable String name,
            @RequestParam(required = false) @Nullable String label,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable Integer page,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String fromUpdatedAt,
            @RequestParam(required = false) @Nullable String toUpdatedAt) {
        String projectId = project(ctx, KeyScope.QUERY, "read");
        PromptListQuery query = new PromptListQuery(
                blankToNull(name),
                null,
                null,
                blankToNull(label),
                blankToNull(tag),
                null,
                instant("fromUpdatedAt", fromUpdatedAt),
                instant("toUpdatedAt", toUpdatedAt),
                PromptListQuery.Order.NAME,
                page == null ? 1 : page,
                TracePageCodec.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT));
        return ApiResponse.ok(PromptList.from(service.list(projectId, query)));
    }

    /** A new name creates version 1; an existing name creates its next version, of the same type. */
    @PostMapping
    public ApiResponse<PublicPrompt> create(TenantContext ctx, @Valid @RequestBody PublicCreatePromptRequest req) {
        String projectId = project(ctx, KeyScope.ADMIN, "change");
        Optional<PromptRow> existing = service.findByName(projectId, req.name());
        if (existing.isPresent()) return ApiResponse.ok(nextVersion(ctx, projectId, existing.get(), req));
        PromptType type = req.type() == null ? PromptType.TEXT : req.type();
        CreatePromptCommand cmd = new CreatePromptCommand(
                req.name(),
                type,
                req.prompt(),
                req.config(),
                orEmpty(req.labels()),
                orEmpty(req.tags()),
                req.commitMessage());
        try {
            PromptService.PromptDetail created = service.create(projectId, ctx.userId(), CreateVia.API, cmd);
            return ApiResponse.ok(PublicPrompt.from(
                    service.getVersion(projectId, created.prompt().id(), 1)));
        } catch (TessaryException e) {
            // Another writer created the name between the read and the insert: write the next version once.
            if (e.error() != PromptsError.NAME_EXISTS) throw e;
            PromptRow raced = service.findByName(projectId, req.name()).orElseThrow(() -> e);
            return ApiResponse.ok(nextVersion(ctx, projectId, raced, req));
        }
    }

    @PatchMapping("/labels")
    public ApiResponse<PublicPrompt> updateLabels(TenantContext ctx, @Valid @RequestBody UpdateLabelsRequest req) {
        String projectId = project(ctx, KeyScope.ADMIN, "change");
        PromptRow row = requirePrompt(projectId, req.name());
        return ApiResponse.ok(PublicPrompt.from(service.setLabels(
                projectId,
                row.id(),
                req.version(),
                ctx.userId(),
                new LinkedHashSet<>(req.newLabels()),
                LabelMode.ADD)));
    }

    /** {@code version} deletes that version, {@code label} removes that label, neither deletes the prompt. */
    @DeleteMapping
    public ApiResponse<DeleteResponse> delete(
            TenantContext ctx,
            @RequestParam(required = false) @Nullable String name,
            @RequestParam(required = false) @Nullable Integer version,
            @RequestParam(required = false) @Nullable String label) {
        String projectId = project(ctx, KeyScope.ADMIN, "change");
        String prompt = requireName(name);
        if (version != null && label != null) throw new TessaryException(PromptsError.BOTH_VERSION_AND_LABEL);
        PromptRow row = requirePrompt(projectId, prompt);
        boolean deleted;
        if (version != null) {
            deleted = service.deleteVersion(projectId, row.id(), version);
        } else if (label != null) {
            deleted = service.removeLabel(projectId, row.id(), label);
        } else {
            deleted = service.delete(projectId, row.id());
        }
        return ApiResponse.ok(new DeleteResponse(deleted));
    }

    private PublicPrompt nextVersion(
            TenantContext ctx, String projectId, PromptRow row, PublicCreatePromptRequest req) {
        if (req.type() != null && req.type() != row.type()) {
            throw new TessaryException(
                    PromptsError.TYPE_MISMATCH, row.name(), row.type().wire());
        }
        CreateVersionCommand cmd =
                new CreateVersionCommand(req.prompt(), req.config(), orEmpty(req.labels()), req.commitMessage());
        return PublicPrompt.from(service.createVersion(projectId, row.id(), ctx.userId(), CreateVia.API, cmd));
    }

    /** A cookie session is refused here: this surface is for keys bound to one project. */
    private String project(TenantContext ctx, KeyScope needed, String verb) {
        String projectId = ctx.projectId();
        String orgId = ctx.orgId();
        if (!ctx.isMcpToken() || projectId == null || orgId == null) {
            throw new TessaryException(PromptsError.TOKEN_REQUIRED);
        }
        if (!ctx.keyPermits(needed)) throw new TessaryException(PromptsError.WRONG_KEY_SCOPE, verb);
        capabilities.require(orgId, Capability.PROMPT_MANAGEMENT);
        return projectId;
    }

    private PromptRow requirePrompt(String projectId, String name) {
        return service.findByName(projectId, name)
                .orElseThrow(() -> new TessaryException(PromptsError.NOT_FOUND, name));
    }

    private static String requireName(@Nullable String name) {
        if (name == null || name.isBlank()) throw new TessaryException(PromptsError.NAME_REQUIRED);
        return name;
    }

    private static @Nullable Instant instant(String param, @Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new TessaryException(CommonError.INVALID_PARAMETER, param);
        }
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static List<String> orEmpty(@Nullable List<String> list) {
        return list == null ? List.of() : list;
    }
}
