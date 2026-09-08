// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import ai.tessary.evals.tenant.Principal;
import ai.tessary.evals.tenant.PrincipalRepository;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authn + tenant resolution. Runs on every request that isn't an unauthenticated
 * endpoint by design:
 *
 * <ul>
 *   <li><b>Bypassed</b> ({@code shouldNotFilter}): {@code /auth/login},
 *       {@code /auth/callback}, {@code /auth/logout}, {@code /auth/link/start},
 *       {@code /auth/link/poll}, {@code /actuator/health} and its two probes (NOT the rest
 *       of {@code /actuator/}, nor the bare {@code /actuator} index), any path a paid-contributed
 *       {@link SelfAuthenticatingPath} claims (empty by default in the open edition — see that
 *       interface), and — only when {@code EVALS_AUTH_DISABLED} is set — every path,
 *       regardless of which {@link AuthProvider} is active (#852 re-decided this: with
 *       {@link PasswordAuthProvider} always enabled, "no provider configured" is no longer a state
 *       the open edition can be in). See {@link AuthProperties}.</li>
 *   <li><b>Everything else under {@code /actuator/}</b>: having a principal at all is not enough
 *       (#935). These paths name no org, so the org-scoped {@link PlatformStaff#canAdminister} has
 *       nothing to resolve against; instead they're gated on the org-independent
 *       {@link PlatformStaff#isStaff}, which also already excludes bearer/MCP contexts by
 *       construction. Authenticated-but-not-staff is a 403, not a 200 — #929 only proved you were
 *       someone, not that you were allowed to see JVM heapdumps and env vars.</li>
 *   <li><b>{@code /mcp}</b>: bearer-token auth only; cookies ignored. Missing/bad
 *       token = hard 401 with a JSON-RPC-shaped body the MCP client can parse.</li>
 *   <li><b>{@code /api/**}</b>: cookie session preferred; falls back to bearer
 *       for headless callers. Missing context = hard 401.</li>
 *   <li><b>Everything else under {@code /auth/**}</b> (today just {@code /auth/me}):
 *       resolve best-effort, leave the request attribute null when there's no
 *       session, and let the controller decide how to respond.</li>
 * </ul>
 *
 * <p>Cookie validation is local-only: unseal, check {@code accessTokenExpiresAt},
 * refresh against WorkOS only when past expiry. No JWKS fetch on the hot path.</p>
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
    private final ObjectProvider<SelfAuthenticatingPath> paidBypasses;
    private final PlatformStaff platformStaff;

    public AuthFilter(
            AuthProperties authProps,
            AuthProvider provider,
            SessionCipher cipher,
            PrincipalRepository users,
            BearerTokenAuthenticator bearerAuth,
            ObjectMapper mapper,
            ObjectProvider<SelfAuthenticatingPath> paidBypasses,
            PlatformStaff platformStaff) {
        this.authProps = authProps;
        this.provider = provider;
        this.cipher = cipher;
        this.users = users;
        this.bearerAuth = bearerAuth;
        this.mapper = mapper;
        this.paidBypasses = paidBypasses;
        this.platformStaff = platformStaff;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = normalisedPath(req);
        // Unauthenticated by design: the WorkOS dance + the actuator health probe.
        // NOTE: /auth/me is deliberately NOT in this list — it needs the filter
        // to unseal the cookie and populate TenantContext before the controller
        // can answer "who am I". The filter doesn't reject /auth/** paths; if
        // there's no cookie it just leaves the context null, and the controller
        // handles that case.
        if ("/auth/login".equals(path)) return true;
        if ("/auth/callback".equals(path)) return true;
        if ("/auth/logout".equals(path)) return true;
        // Device-link start/poll are plugin-facing and unauthenticated by
        // design (the secret device_code is the credential). The browser-facing
        // /api/link/** confirm endpoints stay under the cookie session + CSRF.
        if ("/auth/link/start".equals(path)) return true;
        if ("/auth/link/poll".equals(path)) return true;
        // The /webhooks/git/ bypass was here. Its only endpoint, GitWebhookController, went with the
        // observer in Track A, and an unauthenticated exemption for a path nothing serves is a strictly
        // worse posture than no exemption: it says "the session filter stands aside" about a route that
        // will 404 either way. Restore it together with a controller that verifies the provider HMAC.
        // Paid-contributed self-authenticating paths (#920) — Slack's `/internal/slack/mention` today,
        // generalises later per tessary-paid/OPEN-CORE.md issue 24. Each implementation is responsible for its own
        // credential check before answering; this only says the session filter should stand aside.
        // orderedStream().anyMatch on an empty stream is false by definition, so the open edition (zero
        // implementations) bypasses nothing here — this is exactly the spot a silent fail-open would
        // hide, so it does not get one: no implementation means no bypass, not an open door.
        if (paidBypasses.orderedStream().anyMatch(p -> p.bypasses(path))) return true;
        // GitHub App install callback: GitHub redirects the browser here with no
        // guaranteed cookie. The signed `state` param IS the credential (verified
        // in GithubCallbackController), so bypass the cookie/bearer session.
        if ("/git/github/callback".equals(path)) return true;
        // GitHub App MANIFEST callback (#860's BYO-App wizard): same reasoning as the callback
        // above — GitHub redirects the browser here with no cookie, and the signed `state` param
        // (verified in GithubManifestController) is the credential.
        if ("/git/github/manifest/callback".equals(path)) return true;
        // The health probes only, never the whole /actuator/ prefix (#929) — so widening
        // `management.endpoints.web.exposure` cannot widen the unauthenticated surface with it.
        if (isPublicActuatorPath(path)) return true;
        // The generated OpenAPI contract (springdoc, Phase 3): the API spec is public — it is the
        // checked-in source of truth (backend/contract) and carries no secrets. No cookie/bearer session.
        if ("/v3/api-docs".equals(path) || path.startsWith("/v3/api-docs/")) return true;
        // The operator's own explicit escape hatch (#924, re-decided #852). Originally gated on
        // "AND no provider is configured", because the only provider (WorkOs) could be legitimately
        // absent — that absence was the normal, unauthenticated-by-default state the flag existed to
        // override. #852 added PasswordAuthProvider, the open edition's dependency-free default,
        // which is unconditionally enabled: "no provider configured" is no longer a state the open
        // edition can be in, so a condition requiring it could never fire again — the flag would be
        // permanently dead, breaking the dev stack and every test that relies on it (#996 review).
        //
        // The flag is now authoritative on its own: an operator (or the dev-only compose profile)
        // who sets EVALS_AUTH_DISABLED gets exactly that, full stop, regardless of which provider is
        // selected. Tests that want auth ENFORCED despite the suite's global unauthenticated default
        // (see TestAuthDisabledInitializer) must say so explicitly — override `evals.auth.disabled`
        // back to `false` in their own @DynamicPropertySource — rather than relying on a
        // fake-but-realistic external provider key as an indirect toggle. See
        // TestAuthDisabledInitializer's javadoc for the convention this replaces and why.
        return authProps.isDisabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String path = normalisedPath(req);
        TenantContext ctx;

        // MCP is bearer-only: cookies are ignored, and a missing/bad token
        // is a hard 401 with a JSON-RPC body the MCP client can parse.
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

        // Every other request: try cookie session first, then bearer as a
        // fallback for headless API callers. If both miss, leave ctx null and
        // let the controller decide — /auth/me wants to answer 401 with a
        // clean JSON shape, but anything under /api/** must hard-401 here.
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

        // Actuator sits alongside /api/ here (#929): shouldNotFilter has already released the health
        // probes, so anything actuator-shaped reaching this point is a management endpoint, and
        // without this arm it would reach chain.doFilter with a null context and be SERVED.
        if (ctx == null && (path.startsWith("/api/") || isActuatorPath(path))) {
            reject401(res, "unauthorized");
            return;
        }

        // Actuator, second gate (#935): having ANY principal was never the bar here — #929 only
        // closed the "no credential at all" door. isPublicActuatorPath paths never reach this
        // method (shouldNotFilter already released them), so the guard below is redundant-but-cheap
        // symmetry with the 401 arm above, not load-bearing. isStaff, not canAdminister: these paths
        // name no org, so the org-scoped predicate has nothing to resolve against.
        if (isActuatorPath(path) && !isPublicActuatorPath(path)) {
            // ctx is guaranteed non-null here — the 401 arm above already returned for a null ctx
            // on every actuator path — but NullAway can't fold that proof across two separate `if`
            // conditions, so this re-check is for the type checker, not the runtime: it can never
            // actually fire.
            if (ctx == null) {
                reject401(res, "unauthorized");
                return;
            }
            if (!platformStaff.isStaff(ctx)) {
                reject403(res, "actuator.staff_only", "platform staff only");
                return;
            }
        }

        // CSRF: any state-changing /api request made with a cookie session must
        // carry X-Requested-With. Browsers will not attach a custom header on a
        // cross-origin request without a CORS preflight, and we don't allow any
        // origin to preflight — so this header check blocks classic CSRF
        // (form-submit, <img>, <link>, etc.) without needing a per-request
        // token. Bearer-auth requests (MCP, headless) are exempt: they can't
        // be ridden cross-site because browsers don't carry the Authorization
        // header automatically.
        // isActuatorPath, not a bare prefix: /actuator/shutdown takes an empty POST with no
        // content-type constraint, which is exactly what a cross-site form can send. Widened with
        // the 401 arm above rather than after it, because leaving one of a pair of sibling
        // predicates behind is the bug this whole issue keeps producing.
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
     * Every actuator path, including the bare {@code /actuator} index.
     *
     * <p>The index is the reason this is a method rather than a {@code startsWith} at each call
     * site: {@code "/actuator".startsWith("/actuator/")} is FALSE, so a prefix test silently misses
     * it — and Spring Boot serves it by default ({@code WebEndpointProperties.Discovery.enabled}),
     * as a HAL page listing every exposed endpoint. Two sibling predicates each had to know that,
     * and one of them not knowing it is how the gap arrived in the first place.
     */
    static boolean isActuatorPath(String path) {
        return "/actuator".equals(path) || path.startsWith("/actuator/");
    }

    /**
     * The actuator paths served without authentication: the health endpoint and its two probes,
     * which orchestrators poll before anything holds a credential.
     *
     * <p>Enumerated, not prefixed. {@code /actuator/health/*} would admit every health GROUP and
     * COMPONENT, so a deployment setting {@code show-details: always} would publish
     * {@code /actuator/health/db} — connection state, and whatever a future indicator reports —
     * without anyone choosing to. Exact matches also stop a sibling like {@code /actuator/healthz}
     * inheriting the exemption by name.
     */
    static boolean isPublicActuatorPath(String path) {
        return "/actuator/health".equals(path)
                || "/actuator/health/liveness".equals(path)
                || "/actuator/health/readiness".equals(path);
    }

    /**
     * Returns the path we make auth decisions on. Prefer the servlet path
     * because it is already normalised by the container (no {@code %2f},
     * {@code ..}, or {@code ;} parameter tricks). MockMvc, however, leaves
     * the servlet path empty in some configurations — fall back to the raw
     * request URI in that case, and additionally reject any URI that smells
     * like a path-confusion attempt so we don't bypass auth via
     * {@code /actuator/..%2Fapi/foo} or similar.
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

    // -------------------------------------------------------- cookie session

    private @Nullable TenantContext resolveCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie cookie = findCookie(req, authProps.getCookieName());
        if (cookie == null) return null;
        SealedSession session = cipher.unseal(cookie.getValue());
        if (session == null) {
            // Cookie present but failed AES-GCM auth. Cookie key rotated, attacker,
            // or a stale browser session from a previous deploy. Loud so it shows up.
            log.warn("auth: cookie unseal failed for {} (key rotated or tampered)", req.getRequestURI());
            return null;
        }
        Principal user = users.findByWorkosId(session.workosUserId()).orElse(null);
        if (user == null) {
            // Sealed cookie carries a workos_user_id with no matching row.
            // Means the DB was wiped after the cookie was issued — same effect as
            // a key rotation; we treat it as "not signed in" and let the client retry.
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
            // A successful refresh always returns a fresh access_token; a null means a
            // malformed 2xx body — treat as a refresh failure rather than seal a broken session.
            String accessToken = r.accessToken();
            if (accessToken == null) {
                log.warn("cookie refresh: WorkOS 2xx response carried no access_token");
                return null;
            }
            SealedSession fresh = new SealedSession(
                    accessToken,
                    r.refreshToken(),
                    r.accessTokenExpiresAt().toString(),
                    r.workosUserId() != null ? r.workosUserId() : old.workosUserId(),
                    r.email() != null ? r.email() : old.email(),
                    r.displayName() != null ? r.displayName() : old.displayName(),
                    r.profilePictureUrl() != null ? r.profilePictureUrl() : old.avatarUrl(),
                    r.organizationId() != null ? r.organizationId() : old.organizationId());
            // Tomcat's Cookie.setAttribute("SameSite", ...) path is unreliable
            // combined with ResponseEntity; emit Set-Cookie via ResponseCookie.
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

    // -------------------------------------------------------- bearer (MCP + headless API)

    /**
     * Parse the {@code Authorization: Bearer <token>} header, look up the MCP
     * token, and return a project-bound context. Used both by {@code /mcp}
     * (only auth method allowed) and {@code /api/**} (fallback when no cookie).
     *
     * <p>Delegates to the shared {@link BearerTokenAuthenticator} so the servlet path and the OTLP gRPC
     * receiver's interceptor (not a servlet request) resolve bearer tokens identically.
     */
    private @Nullable TenantContext resolveBearerToken(HttpServletRequest req) {
        return bearerAuth.authenticate(req.getHeader("Authorization")).orElse(null);
    }

    // -------------------------------------------------------- helpers

    private static @Nullable Cookie findCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName())) return c;
        }
        return null;
    }

    private void rejectMcp(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer realm=\"evals-mcp\"");
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
