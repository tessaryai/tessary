// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.tenant.OrgMembership;
import ai.tessary.evals.tenant.OrgMembershipRepository;
import ai.tessary.evals.tenant.Organization;
import ai.tessary.evals.tenant.OrganizationRepository;
import ai.tessary.evals.tenant.Principal;
import ai.tessary.evals.tenant.PrincipalRepository;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.web.ApiResponse;
import ai.tessary.evals.web.ErrorBody;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The WorkOS AuthKit dance + {@code /auth/me}:
 *
 * <pre>
 *   GET  /auth/login?returnTo=...  → 302 to WorkOS AuthKit (returnTo stashed in a cookie)
 *   GET  /auth/callback?code=...   → exchange code, seal session cookie, 302 to returnTo
 *   GET  /auth/logout              → expire cookie, 302 to frontend home
 *   GET  /auth/me                  → current user + memberships, or 401 ApiResponse envelope
 *   POST /auth/signup {email,password} → create + sign in a local account, 200 (#852)
 *   POST /auth/login  {email,password} → sign in a local account, 200 (#852)
 *   GET  /auth/mode                → {redirectFlow, firstRun} which flow the active provider
 *                                    drives (#853) and whether any account exists yet (#1227)
 * </pre>
 *
 * <p>The two POST routes above only do anything when the active {@link AuthProvider} supports
 * them ({@link PasswordAuthProvider} today); {@link AuthProvider#signupWithCredentials} and
 * {@link AuthProvider#authenticateWithCredentials} default-throw {@link AuthProvider.AuthException}
 * for a provider that doesn't (WorkOS), which this class turns into a 400/401 — not a 502, unlike
 * the WorkOS-specific failures below, since a stray credential POST against a WorkOS-configured
 * instance is a client mistake, not an upstream failure. The two GET routes above are guarded by
 * {@link AuthProvider#supportsRedirectFlow()} so they degrade to the existing dev-shortcut redirect
 * instead of calling a method a credential-only provider has no way to implement.
 *
 * <p>All cookie writes go through Spring's {@link ResponseCookie} (correct
 * SameSite serialization) attached as a {@code Set-Cookie} header on the
 * {@link ResponseEntity}. Servlet-level {@code res.addCookie(...)} is avoided
 * because Tomcat 11 + ResponseEntity composition drops attributes inconsistently.</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String RETURN_TO_COOKIE = "evals-post-auth-redirect";
    private static final Duration RETURN_TO_TTL = Duration.ofMinutes(10);
    // OAuth CSRF: a random state minted at /login, echoed by WorkOS to /callback, and
    // checked against this cookie. The callback is a whitelisted GET (no session, no
    // X-Requested-With gate), so the state cookie is the only thing tying the redirect
    // back to a login this browser actually started.
    private static final String STATE_COOKIE = "evals-oauth-state";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final AuthProperties authProps;
    private final AuthProvider provider;
    private final SessionCipher cipher;
    private final TenantService tenants;
    private final OrganizationRepository orgs;
    private final OrgMembershipRepository memberships;
    private final PrincipalRepository users;
    private final PlatformStaff staff;
    private final SignupPolicyService signupPolicy;
    private final SecureRandom rng = new SecureRandom();

    public AuthController(
            AuthProperties authProps,
            AuthProvider provider,
            SessionCipher cipher,
            TenantService tenants,
            OrganizationRepository orgs,
            OrgMembershipRepository memberships,
            PrincipalRepository users,
            PlatformStaff staff,
            SignupPolicyService signupPolicy) {
        this.authProps = authProps;
        this.provider = provider;
        this.cipher = cipher;
        this.tenants = tenants;
        this.orgs = orgs;
        this.memberships = memberships;
        this.users = users;
        this.staff = staff;
        this.signupPolicy = signupPolicy;
    }

    /** {@code POST /auth/signup} request body. */
    public record SignupRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 200) String password) {}

    /** {@code POST /auth/login} request body. */
    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank String password) {}

    /**
     * {@code GET /auth/mode} response body: which flow the active provider drives (#853), whether
     * this deployment has no account yet (#1227), and the sign-up policy in force (#1226) so the
     * sign-up screen can say "invitation required" instead of offering a form the server will refuse.
     */
    public record AuthModeView(boolean redirectFlow, boolean firstRun, String signupPolicy) {}

    /**
     * Tells the frontend's {@code /login} and {@code /signup} screens which flow to render,
     * without requiring a rebuild when a deployment switches providers (e.g. a self-hoster adds
     * WorkOS credentials): {@code redirectFlow=true} means those screens should immediately bounce
     * to {@link AuthController#login} instead of rendering an email/password form. Unauthenticated
     * like {@code /auth/me} — no {@link AuthFilter} bypass-list entry needed for the same reason
     * that route has none.
     *
     * <p>{@code firstRun=true} tells {@code /login} to hand the visitor straight to {@code /signup}:
     * a deployment with no account has nothing to sign in to, and the same answer picks
     * {@link #entryPageUrl}'s target so the common path is one server redirect rather than a
     * visible second hop in the browser.
     */
    @GetMapping("/mode")
    public ResponseEntity<?> mode() {
        boolean redirectFlow = provider.isEnabled() && provider.supportsRedirectFlow();
        return ResponseEntity.ok(ApiResponse.ok(new AuthModeView(
                redirectFlow,
                isFirstRun(redirectFlow),
                signupPolicy.current().mode().wire())));
    }

    /**
     * Whether this deployment has no account yet, so the first thing a visitor should meet is
     * {@code /signup} rather than {@code /login}. False under a redirect-flow provider whatever the
     * table says: WorkOS owns its own signup screen and the local one cannot create an account
     * there, so bouncing a visitor to it would be a dead end.
     */
    private boolean isFirstRun(boolean redirectFlow) {
        return !redirectFlow && !users.anyHumanExists();
    }

    @GetMapping("/login")
    public RedirectView login(
            @RequestParam(value = "returnTo", required = false) String returnTo, HttpServletResponse res) {
        // Dev shortcut: when WorkOS isn't configured, bounce to the frontend's own /login screen
        // rather than the app root — the app root is itself behind ProtectedRoute, which sends an
        // unauthenticated visitor right back to this same GET, so landing here previously meant an
        // infinite redirect loop (#853). supportsRedirectFlow() covers the other reason this GET
        // route can't proceed: the active provider is enabled but has no OAuth dance to run
        // (PasswordAuthProvider, #852) — calling authorizationUrl() on it would throw
        // UnsupportedOperationException instead of a clean redirect, so the same bounce applies.
        if (!provider.isEnabled() || !provider.supportsRedirectFlow()) {
            return new RedirectView(entryPageUrl(returnTo));
        }
        if (returnTo != null && !returnTo.isBlank() && isSafeReturnTo(returnTo)) {
            res.addHeader(
                    HttpHeaders.SET_COOKIE,
                    buildCookie(RETURN_TO_COOKIE, URLEncoder.encode(returnTo, StandardCharsets.UTF_8), RETURN_TO_TTL)
                            .toString());
        }
        // Mint and stash an OAuth state token; /callback rejects any code that doesn't carry it back.
        String state = randomState();
        res.addHeader(
                HttpHeaders.SET_COOKIE,
                buildCookie(STATE_COOKIE, state, STATE_TTL).toString());
        return new RedirectView(provider.authorizationUrl(state));
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(
            @RequestParam("code") String code,
            @RequestParam(value = "state", required = false) String state,
            HttpServletRequest req,
            HttpServletResponse res) {
        // Same supportsRedirectFlow() reasoning as /login: a provider that has no OAuth dance
        // (PasswordAuthProvider, #852) cannot answer authenticateWithCode(), so bounce to the
        // frontend's own entry screen (same reasoning as /login's degrade branch, #853) rather
        // than call a method that provider has no way to implement. Only reached directly (not via
        // /login's own redirect) when something hits this URL by hand — there's no returnTo query
        // param on a bare /callback hit, so this always lands on a bare screen with no query.
        if (provider.isEnabled() && !provider.supportsRedirectFlow()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(entryPageUrl(null)))
                    .build();
        }
        // CSRF: the code is only honored if it carries back the state we minted at /login
        // (popCookie consumes the state cookie so it can't be replayed). Skipped when WorkOS is
        // disabled (dev), where /login never mints state. On a miss — expired (>10min), a
        // replay, a stale tab, or a forged callback — restart sign-in rather than render a raw
        // 400 JSON body to this top-level browser navigation; a fresh /login mints a new state.
        if (provider.isEnabled()) {
            String expectedState = popCookie(req, res, STATE_COOKIE);
            if (expectedState == null || state == null || !constantTimeEquals(expectedState, state)) {
                log.warn("auth/callback: missing or mismatched OAuth state; restarting sign-in");
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(authProps.getFrontendUrl()))
                        .build();
            }
        }
        AuthProvider.AuthResult r;
        try {
            r = provider.authenticateWithCode(code);
        } catch (AuthProvider.AuthException e) {
            log.warn("auth/callback: WorkOS rejected the code: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(), new ErrorBody("auth.workos_failed", e.getMessage(), null)));
        }

        // The policy gate sits between authentication and account creation (#1226). This is a
        // top-level navigation, so a refusal lands on the sign-in screen with a reason, not raw JSON.
        try {
            signupPolicy.admit(r.email(), r.workosUserId());
        } catch (EvalsException e) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(signupRefusedUrl()))
                    .build();
        }

        Principal user =
                tenants.upsertUserFromWorkos(r.workosUserId(), r.email(), r.displayName(), r.profilePictureUrl());
        tenants.consumePendingInvitations(user);
        Organization defaultOrg = tenants.ensureDefaultOrg(user, r.organizationId());

        // A successful WorkOS authenticate always returns an access_token; a null here means
        // a malformed 2xx body, which we treat as an auth failure rather than seal a broken session.
        String accessToken = r.accessToken();
        if (accessToken == null) {
            log.warn("auth/callback: WorkOS 2xx response carried no access_token");
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(),
                            new ErrorBody("auth.workos_failed", "no access token", null)));
        }
        SealedSession session = new SealedSession(
                accessToken,
                r.refreshToken(),
                r.accessTokenExpiresAt().toString(),
                user.workosUserId(),
                user.email(),
                user.displayName(),
                user.avatarUrl(),
                r.organizationId() != null ? r.organizationId() : defaultOrg.workosOrgId());
        String setCookie = buildCookie(
                        authProps.getCookieName(),
                        cipher.seal(session),
                        Duration.ofSeconds(authProps.getCookieMaxAgeSeconds()))
                .toString();

        String returnTo = readReturnTo(req, res);
        String target = returnTo != null ? returnTo : authProps.getFrontendUrl();
        log.info("auth/callback: signed in principal {} → {}", user.id(), target);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.SET_COOKIE, setCookie)
                .build();
    }

    /**
     * Create a local account and sign in, via {@link AuthProvider#signupWithCredentials}. 409 on
     * a duplicate email or any other {@link AuthProvider.AuthException} (the provider is the one
     * that knows why signup failed; this route doesn't try to distinguish reasons further).
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest req) {
        // Before the provider, because PasswordAuthProvider's signup IS the principal insert.
        try {
            signupPolicy.admit(req.email(), null);
        } catch (EvalsException e) {
            return signupRefused(e);
        }
        AuthProvider.AuthResult r;
        try {
            r = provider.signupWithCredentials(req.email(), req.password());
        } catch (AuthProvider.AuthException e) {
            log.warn("auth/signup: rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.failure(
                            HttpStatus.CONFLICT.value(), new ErrorBody("auth.signup_failed", e.getMessage(), null)));
        }
        return establishSession(r);
    }

    /** Sign in with email/password, via {@link AuthProvider#authenticateWithCredentials}. */
    @PostMapping("/login")
    public ResponseEntity<?> loginWithPassword(@Valid @RequestBody LoginRequest req) {
        AuthProvider.AuthResult r;
        try {
            r = provider.authenticateWithCredentials(req.email(), req.password());
        } catch (AuthProvider.AuthException e) {
            log.warn("auth/login: rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.failure(
                            HttpStatus.UNAUTHORIZED.value(),
                            new ErrorBody("auth.invalid_credentials", e.getMessage(), null)));
        }
        return establishSession(r);
    }

    /**
     * The signup/login shared tail: run {@code /callback}'s own upsert → invitations → default-org
     * → seal-cookie sequence against an {@link AuthProvider.AuthResult} that didn't
     * come from an OAuth code exchange, and answer 200 JSON (these two routes are {@code fetch()}
     * calls, not top-level navigations, unlike {@code /callback}'s 302). Since
     * {@code authenticateWithCredentials}'s result already carries the row's current
     * {@code displayName}/{@code avatarUrl}, the {@code upsertUserFromWorkos} call below is a
     * same-value no-op on login, not a clobber — see {@code TenantService.upsertUserFromWorkos}.
     */
    private ResponseEntity<?> establishSession(AuthProvider.AuthResult r) {
        try {
            signupPolicy.admit(r.email(), r.workosUserId());
        } catch (EvalsException e) {
            return signupRefused(e);
        }
        Principal user =
                tenants.upsertUserFromWorkos(r.workosUserId(), r.email(), r.displayName(), r.profilePictureUrl());
        tenants.consumePendingInvitations(user);
        Organization defaultOrg = tenants.ensureDefaultOrg(user, r.organizationId());

        // Same "malformed 2xx" defensiveness as /callback's accessToken null-check, even though
        // PasswordAuthProvider always sets a placeholder token today — a future AuthProvider
        // implementing signupWithCredentials/authenticateWithCredentials might not, and this is the
        // one seam SealedSession's non-null accessToken flows through for both credential routes.
        String accessToken = r.accessToken();
        if (accessToken == null) {
            log.warn("auth: provider returned no access token for principal {}", user.id());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(),
                            new ErrorBody("auth.provider_failed", "no access token", null)));
        }

        SealedSession session = new SealedSession(
                accessToken,
                r.refreshToken(),
                r.accessTokenExpiresAt().toString(),
                user.workosUserId(),
                user.email(),
                user.displayName(),
                user.avatarUrl(),
                r.organizationId() != null ? r.organizationId() : defaultOrg.workosOrgId());
        String setCookie = buildCookie(
                        authProps.getCookieName(),
                        cipher.seal(session),
                        Duration.ofSeconds(authProps.getCookieMaxAgeSeconds()))
                .toString();

        log.info("auth: signed in principal {}", user.id());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, setCookie)
                .body(ApiResponse.ok(Map.of(
                        "id", user.id(),
                        "email", user.email() != null ? user.email() : "",
                        "orgId", defaultOrg.id())));
    }

    private static ResponseEntity<?> signupRefused(EvalsException e) {
        return ResponseEntity.status(e.error().status())
                .body(ApiResponse.failure(
                        e.error().status().value(), new ErrorBody(e.error().code(), e.getMessage(), null)));
    }

    /** The sign-in screen with the refusal named, for the callback's top-level navigation. */
    private String signupRefusedUrl() {
        return frontendBase() + "/login?error=signup_refused";
    }

    /**
     * getFrontendUrl() is an operator-supplied EVALS_AUTH_FRONTEND_URL and is not guaranteed to end
     * in "/" (only the property's own default does); strip it defensively, same as
     * DeviceLinkController#linkUri and GithubInstallController#frontendBase.
     */
    private String frontendBase() {
        String frontendUrl = authProps.getFrontendUrl();
        if (frontendUrl.endsWith("/")) {
            frontendUrl = frontendUrl.substring(0, frontendUrl.length() - 1);
        }
        return frontendUrl;
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        // POST-only — a GET logout endpoint could be triggered by <img src> or
        // any cross-site navigation. Max-Age=0 same name/path tells the
        // browser to drop the cookie. We return a small JSON body and let the
        // frontend navigate; sending a 302 in a fetch() response would chase
        // the browser into an opaque redirect.
        String kill = buildCookie(authProps.getCookieName(), "", Duration.ZERO).toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, kill)
                .body(ApiResponse.ok(Map.of("frontendUrl", authProps.getFrontendUrl())));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        TenantContext ctx = (TenantContext) req.getAttribute(TenantContext.ATTRIBUTE);
        if (ctx == null || !ctx.isAuthenticated()) {
            // 401 in the canonical ApiResponse shape so the React client parses
            // it like any other failed call. /auth/me is the one filter-friendly
            // endpoint where "not signed in" is a normal response, not an error.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.failure(
                            HttpStatus.UNAUTHORIZED.value(),
                            new ErrorBody("auth.unauthorized", "not signed in", null)));
        }
        List<Organization> orgList = orgs.findByUserId(ctx.userId());
        List<Map<String, Object>> orgPayloads = new ArrayList<>(orgList.size());
        for (Organization o : orgList) {
            Optional<OrgMembership> m = memberships.find(o.id(), ctx.userId());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", o.id());
            p.put("slug", o.slug());
            p.put("name", o.name());
            p.put("role", m.map(OrgMembership::role).orElse("member"));
            orgPayloads.add(p);
        }
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", ctx.userId());
        me.put("email", ctx.userEmail());
        me.put("orgs", orgPayloads);
        // A UI affordance hint, not an authorization: it tells the billing screen whether to render the
        // staff plan control. Every staff route re-checks identity AND org standing server-side.
        me.put("platform_staff", staff.isStaff(ctx));
        return ResponseEntity.ok(ApiResponse.ok(me));
    }

    // ---------------------------------------------------------------- helpers

    /** A high-entropy URL-safe OAuth state token. */
    private String randomState() {
        byte[] b = new byte[32];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Constant-time equality so a state check can't be turned into a timing oracle. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Read a cookie's raw value and immediately expire it (single-use). Null when absent. */
    private @Nullable String popCookie(HttpServletRequest req, HttpServletResponse res, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName())) {
                killCookie(res, name);
                return c.getValue();
            }
        }
        return null;
    }

    /** Expire a cookie via the same {@link #buildCookie} path as every other write in this class —
     *  raw {@code res.addCookie} drops attributes inconsistently under Tomcat 11 + ResponseEntity. */
    private void killCookie(HttpServletResponse res, String name) {
        res.addHeader(
                HttpHeaders.SET_COOKIE, buildCookie(name, "", Duration.ZERO).toString());
    }

    private ResponseCookie buildCookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(authProps.isCookieSecure())
                .path("/")
                .maxAge(maxAge)
                .sameSite("Lax")
                .build();
    }

    /**
     * The frontend screen an unauthenticated visitor belongs on (#853), carrying {@code returnTo}
     * through as a query param when it's present and passes {@link #isSafeReturnTo}. This is the
     * shared bounce target for both GET-route degrade branches above — replacing the old
     * {@code authProps.getFrontendUrl()} bounce to the app root, which sat behind
     * {@code ProtectedRoute} and sent an unauthenticated visitor straight back to this same GET,
     * i.e. an infinite redirect loop.
     *
     * <p>On a deployment with no account yet that screen is {@code /signup}, not {@code /login}
     * (#1227): the visitor's only move is to create the first account, and deciding it here means
     * the browser makes one redirect instead of landing on a sign-in form and being bounced again.
     */
    private String entryPageUrl(@Nullable String returnTo) {
        // Both callers are degrade branches, i.e. reached only when the provider drives no redirect
        // flow, so the redirectFlow half of isFirstRun is already false here.
        String base = frontendBase() + (users.anyHumanExists() ? "/login" : "/signup");
        if (returnTo != null && !returnTo.isBlank() && isSafeReturnTo(returnTo)) {
            return base + "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
        }
        return base;
    }

    private static boolean isSafeReturnTo(String returnTo) {
        // Same-origin relative paths only. We require: starts with '/' AND the
        // second char is not '/' or '\' (Chromium normalises a leading "/\"
        // into "//host" — same open-redirect class as "//evil").
        if (returnTo == null || returnTo.length() < 1) return false;
        if (returnTo.charAt(0) != '/') return false;
        if (returnTo.length() >= 2) {
            char c = returnTo.charAt(1);
            if (c == '/' || c == '\\') return false;
        }
        return true;
    }

    /**
     * Pop the post-auth redirect cookie if present, URL-decoding its value.
     * Returns null if absent or malformed; caller falls back to the frontend home.
     */
    private @Nullable String readReturnTo(HttpServletRequest req, HttpServletResponse res) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (!RETURN_TO_COOKIE.equals(c.getName())) continue;
            // Consume the cookie so a stale value doesn't leak into the next login.
            killCookie(res, RETURN_TO_COOKIE);
            try {
                return URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
