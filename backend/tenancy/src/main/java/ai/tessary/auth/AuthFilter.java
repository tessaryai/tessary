// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.Principal;
import ai.tessary.tenant.PrincipalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cookie validation is local-only: unseal, check {@code accessTokenExpiresAt}, refresh only when past expiry.
 * Non-health actuator paths need {@link PlatformStaff#isStaff}, since proving identity is not permission to read
 * heapdumps and env vars.
 */
@Component
@Order(10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final AuthProperties authProps;
    private final AuthProvider provider;
    private final SessionCipher cipher;
    private final PrincipalRepository users;
    private final BearerTokenAuthenticator bearerAuth;
    private final ObjectMapper mapper;
    private final PlatformStaff platformStaff;

    public AuthFilter(
            AuthProperties authProps,
            AuthProvider provider,
            SessionCipher cipher,
            PrincipalRepository users,
            BearerTokenAuthenticator bearerAuth,
            ObjectMapper mapper,
            PlatformStaff platformStaff) {
        this.authProps = authProps;
        this.provider = provider;
        this.cipher = cipher;
        this.users = users;
        this.bearerAuth = bearerAuth;
        this.mapper = mapper;
        this.platformStaff = platformStaff;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = normalisedPath(req);
        // /auth/me is deliberately not bypassed: it needs the filter to populate TenantContext, and with no cookie the
        // context is just left null.
        if ("/auth/login".equals(path)) return true;
        if ("/auth/callback".equals(path)) return true;
        if ("/auth/logout".equals(path)) return true;
        // The secret device_code is the credential. The browser-facing /api/link/** confirm endpoints stay under the
        // cookie session + CSRF.
        if ("/auth/link/start".equals(path)) return true;
        if ("/auth/link/poll".equals(path)) return true;
        // GitHub redirects here with no guaranteed cookie; the signed `state` param (verified in
        // GithubCallbackController) is the credential.
        if ("/git/github/callback".equals(path)) return true;
        // Same as above; the signed `state` is verified in GithubManifestController.
        if ("/git/github/manifest/callback".equals(path)) return true;
        // The health probes only, never the whole /actuator/ prefix, so widening `management.endpoints.web.exposure`
        // cannot widen the unauthenticated surface.
        if (isPublicActuatorPath(path)) return true;
        if ("/v3/api-docs".equals(path) || path.startsWith("/v3/api-docs/")) return true;
        // Authoritative on its own: with PasswordAuthProvider always available, "no provider configured" is not
        // reachable. Tests that want auth enforced override `tessary.auth.disabled` to false (see
        // TestAuthDisabledInitializer).
        return authProps.isDisabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String path = normalisedPath(req);
        TenantContext ctx;

        if (path.startsWith("/mcp")) {
            ctx = resolveBearerToken(req);
            if (ctx == null) {
                rejectMcp(res);
                return;
            }
            req.setAttribute(TenantContext.ATTRIBUTE, ctx);
            chain.doFilter(req, res);
            return;
        }

        // Anything under /api/** must hard-401 here; /auth/me answers its own 401.
        boolean cookieAuthed = false;
        ctx = resolveCookie(req, res);
        if (ctx != null) {
            cookieAuthed = true;
        } else {
            ctx = resolveBearerToken(req);
        }
        if (ctx != null) {
            req.setAttribute(TenantContext.ATTRIBUTE, ctx);
        }

        // shouldNotFilter has already released the health probes, so anything actuator-shaped here is a management
        // endpoint and must not reach chain.doFilter with a null context.
        if (ctx == null) {
            if (path.startsWith("/api/") || isActuatorPath(path)) {
                reject401(res, "unauthorized");
                return;
            }
        } else if (isActuatorPath(path) && !isPublicActuatorPath(path) && !platformStaff.isStaff(ctx)) {
            reject403(res, "actuator.staff_only", "platform staff only");
            return;
        }

        // CSRF: browsers won't attach a custom header cross-origin without a preflight, and no origin may preflight.
        // Bearer requests are exempt because browsers don't attach Authorization automatically. Actuator is included
        // because /actuator/shutdown takes an empty POST a cross-site form can send.
        if (cookieAuthed && (path.startsWith("/api/") || isActuatorPath(path)) && isMutating(req.getMethod())) {
            String xrw = req.getHeader("X-Requested-With");
            if (xrw == null || !"XMLHttpRequest".equalsIgnoreCase(xrw)) {
                reject403(res, "csrf.missing_xrw", "request rejected by CSRF guard");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    /**
     * A method rather than {@code startsWith}: {@code "/actuator".startsWith("/actuator/")} is false, and Spring Boot
     * serves the bare index by default as a HAL page listing every exposed endpoint.
     */
    static boolean isActuatorPath(String path) {
        return "/actuator".equals(path) || path.startsWith("/actuator/");
    }

    /**
     * Enumerated, not prefixed: {@code /actuator/health/*} would publish every health group and component (e.g.
     * {@code /actuator/health/db}) under {@code show-details: always}, and a sibling like {@code /actuator/healthz}
     * must not inherit the exemption.
     */
    static boolean isPublicActuatorPath(String path) {
        return "/actuator/health".equals(path)
                || "/actuator/health/liveness".equals(path)
                || "/actuator/health/readiness".equals(path);
    }

    /**
     * The servlet path is already normalised by the container. MockMvc may leave it empty, so fall back to the raw URI
     * and reject anything resembling path confusion such as {@code /actuator/..%2Fapi/foo}.
     */
    private static String normalisedPath(HttpServletRequest req) {
        String servletPath = req.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) return servletPath;
        String uri = req.getRequestURI();
        if (uri == null) return "";
        // Any of these in a URI is almost certainly an obfuscation attempt
        // (browsers don't naturally produce them on legitimate navigations).
        // Returning an opaque non-matching string makes shouldNotFilter()
        // fall through to "filter this" — auth gets enforced.
        if (uri.contains("..")
                || uri.contains(";")
                || uri.toLowerCase(Locale.ROOT).contains("%2f")
                || uri.toLowerCase(Locale.ROOT).contains("%5c")) {
            return "/__suspicious__";
        }
        return uri;
    }

    private static boolean isMutating(String method) {
        if (method == null) return false;
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private @Nullable TenantContext resolveCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie cookie = findCookie(req, authProps.getCookieName());
        if (cookie == null) return null;
        SealedSession session = cipher.unseal(cookie.getValue());
        if (session == null) {
            // Key rotated, tampered, or a stale session from a previous deploy. Loud so it shows up.
            log.warn("auth: cookie unseal failed for {} (key rotated or tampered)", req.getRequestURI());
            return null;
        }
        Principal user = users.findByWorkosId(session.workosUserId()).orElse(null);
        if (user == null) {
            // The DB was wiped after the cookie was issued; treat it as "not signed in".
            log.warn("auth: cookie unsealed but principal not found for workos_user_id={}", session.workosUserId());
            return null;
        }
        if (isExpired(session.accessTokenExpiresAt())) {
            session = tryRefresh(session, res);
            if (session == null) return null; // refresh failure already logged
        }
        return new TenantContext(
                user.id(),
                user.email(),
                null,
                null,
                null, // org+project resolved per-request by TenantPathResolver
                null);
    }

    private @Nullable SealedSession tryRefresh(SealedSession old, HttpServletResponse res) {
        if (old.refreshToken() == null) return null;
        try {
            var r = provider.refresh(old.refreshToken(), old.organizationId());
            // A null means a malformed 2xx body; treat it as a refresh failure rather than seal a broken session.
            if (r.accessToken() == null) {
                log.warn("cookie refresh: WorkOS 2xx response carried no access_token");
                return null;
            }
            SealedSession fresh = new SealedSession(
                    r.refreshToken(),
                    r.accessTokenExpiresAt().toString(),
                    r.workosUserId() != null ? r.workosUserId() : old.workosUserId(),
                    r.organizationId() != null ? r.organizationId() : old.organizationId(),
                    r.sessionId() != null ? r.sessionId() : old.sessionId());
            // Tomcat's Cookie.setAttribute("SameSite", ...) is unreliable combined with ResponseEntity.
            ResponseCookie c = ResponseCookie.from(authProps.getCookieName(), cipher.seal(fresh))
                    .httpOnly(true)
                    .secure(authProps.isCookieSecure())
                    .path("/")
                    .maxAge(Duration.ofSeconds(authProps.getCookieMaxAgeSeconds()))
                    .sameSite("Lax")
                    .build();
            res.addHeader(HttpHeaders.SET_COOKIE, c.toString());
            return fresh;
        } catch (RuntimeException e) {
            log.warn("cookie refresh failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isExpired(@Nullable String iso) {
        if (iso == null) return true;
        try {
            return Instant.parse(iso).isBefore(Instant.now().minusSeconds(30));
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Delegates to {@link BearerTokenAuthenticator} so the servlet path and the OTLP gRPC interceptor resolve tokens
     * identically.
     */
    private @Nullable TenantContext resolveBearerToken(HttpServletRequest req) {
        return bearerAuth.authenticate(req.getHeader("Authorization")).orElse(null);
    }

    private static @Nullable Cookie findCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName())) return c;
        }
        return null;
    }

    private void rejectMcp(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer realm=\"tessary-mcp\"");
        res.setContentType("application/json");
        res.getWriter()
                .write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"unauthorized\"}}");
    }

    private void reject401(HttpServletResponse res, String reason) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        var body = Map.of("ok", false, "error", Map.of("code", "auth.unauthorized", "message", reason));
        res.getWriter().write(mapper.writeValueAsString(body));
    }

    private void reject403(HttpServletResponse res, String code, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        var body = Map.of("ok", false, "error", Map.of("code", code, "message", message));
        res.getWriter().write(mapper.writeValueAsString(body));
    }
}
