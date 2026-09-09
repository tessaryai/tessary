// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import ai.tessary.auth.AuthProperties;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.auth.link.DeviceLinkService.PollOutcome;
import ai.tessary.auth.link.DeviceLinkService.Started;
import ai.tessary.auth.link.DeviceLinkService.View;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device-link endpoints. The plugin-facing {@code /auth/link/start} and
 * {@code /auth/link/poll} are unauthenticated (see {@code AuthFilter.shouldNotFilter})
 * and per-IP rate limited here. The browser-facing {@code /api/link/**} run
 * under the cookie session + CSRF guard.
 */
@RestController
public class DeviceLinkController {

    private static final Logger log = LoggerFactory.getLogger(DeviceLinkController.class);

    private final DeviceLinkService service;
    private final TenantPathResolver resolver;
    private final AuthProperties props;

    // Per-IP fixed-window limiter for the unauthenticated endpoints.
    private static final int WINDOW_SECONDS = 60;
    private static final int MAX_PER_WINDOW = 60; // poll is ~20/min/code; allow a few concurrent links
    private final ConcurrentMap<String, long[]> ipWindows = new ConcurrentHashMap<>();

    public DeviceLinkController(DeviceLinkService service, TenantPathResolver resolver, AuthProperties props) {
        this.service = service;
        this.resolver = resolver;
        this.props = props;
    }

    public record StartRequest(String client_label) {}

    public record StartResponse(
            String device_code,
            String user_code,
            String verification_uri,
            String verification_uri_complete,
            int interval,
            long expires_in) {}

    public record PollRequest(String device_code) {}

    public record PollResponse(
            String status,
            @Nullable String token,
            @Nullable String org_slug,
            @Nullable String project_slug) {
        static PollResponse from(PollOutcome o) {
            return new PollResponse(o.status(), o.token(), o.orgSlug(), o.projectSlug());
        }
    }

    public record ConfirmRequest(String org_slug, String project_slug) {}

    // ----------------------------------------------------------- plugin-facing (unauth)

    @PostMapping("/auth/link/start")
    public ApiResponse<StartResponse> start(@RequestBody(required = false) StartRequest req, HttpServletRequest http) {
        rateLimit(http);
        String label = req != null ? req.client_label() : null;
        Started s = service.start(label);
        String verifyUri = linkUri(null);
        String verifyComplete = linkUri(s.userCode());
        long expiresIn =
                Math.max(0, s.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        return ApiResponse.ok(new StartResponse(
                s.deviceCode(),
                s.userCode(),
                verifyUri,
                verifyComplete,
                DeviceLinkService.POLL_INTERVAL_SECONDS,
                expiresIn));
    }

    @PostMapping("/auth/link/poll")
    public ResponseEntity<ApiResponse<PollResponse>> poll(@RequestBody PollRequest req, HttpServletRequest http) {
        rateLimit(http);
        PollOutcome outcome = service.poll(req == null ? null : req.device_code());
        HttpStatus code =
                switch (outcome.status()) {
                    case "expired" -> HttpStatus.GONE;
                    case "already_claimed" -> HttpStatus.CONFLICT;
                    default -> HttpStatus.OK;
                };
        return ResponseEntity.status(code).body(ApiResponse.ok(PollResponse.from(outcome)));
    }

    // ----------------------------------------------------------- browser-facing (cookie + CSRF)

    @GetMapping("/api/link/{userCode}")
    public ApiResponse<View> view(@PathVariable String userCode) {
        View v = service.view(userCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "link not found"));
        return ApiResponse.ok(v);
    }

    @PostMapping("/api/link/{userCode}/confirm")
    public ApiResponse<Map<String, String>> confirm(
            TenantContext ctx, @PathVariable String userCode, @RequestBody ConfirmRequest req) {
        if (req == null || req.org_slug() == null || req.project_slug() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "org_slug and project_slug required");
        }
        // Enforces the caller's membership in the org + that the project exists.
        Resolved r = resolver.requireProject(ctx, req.org_slug(), req.project_slug());
        // Confirming is a mint: DeviceLinkService.poll lazy-mints a KeyScope.ADMIN token against this
        // project once the link is claimed, and every bearer key resolves back as role `member`. Same
        // gate as POST /mcp-tokens for the same reason -- without it this route is the other door to
        // the same self-service escalation.
        r.require(Permission.ORG_MANAGE, "confirm a device link");
        boolean ok = service.confirm(userCode, r.org().id(), r.project().id(), ctx.userId());
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "link is no longer pending or has expired");
        }
        log.info("device link {} confirmed → project {}", userCode, r.project().slug());
        return ApiResponse.ok(Map.of("status", "confirmed"));
    }

    @PostMapping("/api/link/{userCode}/deny")
    public ApiResponse<Map<String, String>> deny(TenantContext ctx, @PathVariable String userCode) {
        service.deny(userCode);
        return ApiResponse.ok(Map.of("status", "denied"));
    }

    // ----------------------------------------------------------- helpers

    private String linkUri(@Nullable String userCode) {
        String base = props.getFrontendUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/link" + (userCode != null ? "?code=" + userCode : "");
    }

    // Coarse cap so the limiter map can't grow without bound (mirrors RateLimitFilter's
    // accepted single-instance tradeoff); cleared wholesale when it gets large.
    private static final int MAX_TRACKED_IPS = 50_000;

    private void rateLimit(HttpServletRequest http) {
        // getRemoteAddr() is the real client IP: server.forward-headers-strategy=native
        // makes Tomcat resolve X-Forwarded-For from the trusted proxy. Reading the raw
        // header here instead would be client-spoofable, defeating the limiter.
        String ip = http.getRemoteAddr();
        long nowWindow = Instant.now().getEpochSecond() / WINDOW_SECONDS;
        if (ipWindows.size() > MAX_TRACKED_IPS) ipWindows.clear();
        long[] w = ipWindows.compute(ip, (k, v) -> {
            if (v == null || v[0] != nowWindow) return new long[] {nowWindow, 1};
            v[1]++;
            return v;
        });
        if (w[1] > MAX_PER_WINDOW) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down");
        }
    }
}
