// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.storage.TraceFilters;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP boundary for session reads; shapes live in {@link SessionReadService} so MCP shares them. No sort parameter,
 * contractually: sorting by cost would sum every session before choosing a page.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/sessions")
public class SessionsController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final SessionReadService sessions;
    private final TenantPathResolver resolver;

    public SessionsController(SessionReadService sessions, TenantPathResolver resolver) {
        this.sessions = sessions;
        this.resolver = resolver;
    }

    /**
     * {@code include=totals} adds totals to the page already chosen by recency. A session is listed when the traces
     * list would show one of its traces.
     */
    @GetMapping
    public ApiResponse<SessionDtos.SessionsPage> list(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String include,
            @RequestParam(required = false) @Nullable String model,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable String callSite,
            @RequestParam(required = false) @Nullable Boolean hasCallSite,
            @RequestParam(required = false) @Nullable String fromTimestamp,
            @RequestParam(required = false) @Nullable String toTimestamp,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable List<String> detectedBy,
            @RequestParam(required = false) @Nullable String promptName,
            @RequestParam(required = false) @Nullable String promptVersion) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view sessions");
        boolean includeTotals = "totals".equals(include);
        var filter = new TraceV2Repository.TraceQuery(
                model,
                kind,
                callSite,
                hasCallSite,
                fromTimestamp,
                toTimestamp,
                status,
                q,
                TraceFilters.detectedBy(detectedBy),
                promptName,
                promptVersion);
        return ApiResponse.ok(sessions.page(
                r.project().id(),
                TracePageCodec.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT),
                cursor,
                includeTotals,
                filter));
    }

    /** Given the traces list's filters, also names the traces that pass them, with any picked detection. */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDtos.SessionDetail> detail(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String sessionId,
            @RequestParam(required = false) @Nullable String model,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable String callSite,
            @RequestParam(required = false) @Nullable Boolean hasCallSite,
            @RequestParam(required = false) @Nullable String fromTimestamp,
            @RequestParam(required = false) @Nullable String toTimestamp,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable List<String> detectedBy,
            @RequestParam(required = false) @Nullable String promptName,
            @RequestParam(required = false) @Nullable String promptVersion) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view a session");
        var filter = new TraceV2Repository.TraceQuery(
                model,
                kind,
                callSite,
                hasCallSite,
                fromTimestamp,
                toTimestamp,
                status,
                q,
                TraceFilters.detectedBy(detectedBy),
                promptName,
                promptVersion);
        return ApiResponse.ok(sessions.detail(r.project().id(), sessionId, filter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")));
    }

    /** Its own endpoint so callers wanting only totals don't pay for spans. */
    @GetMapping("/{sessionId}/spans")
    public ApiResponse<SessionDtos.SessionSpans> spans(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String sessionId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view a session's spans");
        return ApiResponse.ok(sessions.spans(r.project().id(), sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")));
    }
}
