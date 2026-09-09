// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read API for sessions — one continuous interaction with one user (substrate-model.md §5.2, §7.5).
 *
 * <p>The shapes and the assembly live in {@link SessionReadService} / {@link SessionDtos}, because MCP's
 * {@code list_sessions} / {@code get_session} serve the same two answers and a session's honesty devices
 * ({@code unsettled_traces}, {@code traces_truncated}) are only honest if every surface carries them. What
 * remains here is the HTTP boundary: tenancy, permission, the page-size policy, and the 404.
 *
 * <p><b>The list has no sort parameter, and that is contractual.</b> Listing sessions by cost or tokens would
 * mean summing every session in the project before the page could be chosen — the read shape this substrate
 * exists to make impossible. Recency is what {@code session.last_activity_at} and
 * {@code ix_session_project_active} serve without any rollup behind them; anything else needs a session
 * materialization with its own staleness contract, as a separate piece of work.
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
     * A page of the project's sessions, most recently active first.
     *
     * <p>Deliberately no {@code sort} parameter — see the class javadoc. Adding one for cost or tokens is
     * not an omission to be filled in later without a design change behind it.
     *
     * <p>{@code include=totals} is a display opt-in, not a sort: it adds each returned session's totals and
     * dominant call site to the page already chosen by recency, batched as two grouped queries for the whole
     * page (see {@link SessionReadService#page}). Any other value, or the parameter's absence, is the plain
     * identity-only read.
     */
    @GetMapping
    public ApiResponse<SessionDtos.SessionsPage> list(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String include) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view sessions");
        boolean includeTotals = "totals".equals(include);
        return ApiResponse.ok(sessions.page(
                r.project().id(), TracePageCodec.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT), cursor, includeTotals));
    }

    /** One session: identity, the summed rollups of its traces, and those traces oldest first. */
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDtos.SessionDetail> detail(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String sessionId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view a session");
        return ApiResponse.ok(sessions.detail(r.project().id(), sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")));
    }

    /**
     * Every span across a session's traces — deliberately not a field on {@link #detail}, which every
     * caller wanting only totals or a trace list would otherwise pay for. See
     * {@link SessionReadService#spans} for why this and {@link #detail} always describe the same traces.
     */
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
