// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.PrincipalRepository;
import ai.tessary.tenant.TenantService;
import ai.tessary.web.ApiResponse;
import ai.tessary.web.ErrorBody;
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
 * Cookie writes go through {@link ResponseCookie} as a {@code Set-Cookie} header: {@code res.addCookie(...)} drops
 * attributes inconsistently under Tomcat 11 + ResponseEntity composition. A credential POST against a provider that
 * lacks it is a client mistake, so it maps to 400/401, not 502.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String RETURN_TO_COOKIE = "tessary-post-auth-redirect";
    private static final Duration RETURN_TO_TTL = Duration.ofMinutes(10);
    // OAuth CSRF: the callback is a whitelisted GET with no session, so this state cookie is the only thing tying the
    // redirect back to a login this browser started.
    private static final String STATE_COOKIE = "tessary-oauth-state";
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

    public record SignupRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 200) String password) {}

    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank String password) {}

    public record AuthModeView(boolean redirectFlow, boolean firstRun, String signupPolicy) {}

    /** Unauthenticated like {@code /auth/me}, so no {@link AuthFilter} bypass entry is needed. */
    @GetMapping("/mode")
    public ResponseEntity<?> mode() {
        boolean redirectFlow = provider.supportsRedirectFlow();
        return ResponseEntity.ok(ApiResponse.ok(new AuthModeView(
                redirectFlow,
                isFirstRun(redirectFlow),
                signupPolicy.current().mode().wire())));
    }

    /**
     * False under a redirect-flow provider: WorkOS owns its own signup screen, so bouncing a visitor to the local one
     * would be a dead end.
     */
    private boolean isFirstRun(boolean redirectFlow) {
        return !redirectFlow && !users.anyHumanExists();
    }

    @GetMapping("/login")
    public RedirectView login(
            @RequestParam(value = "returnTo", required = false) String returnTo, HttpServletResponse res) {
        // The app root is behind ProtectedRoute, which sends an unauthenticated visitor back to this GET, so bounce to
        // the frontend's own screen to avoid a redirect loop.
        if (!provider.supportsRedirectFlow()) {
            return new RedirectView(entryPageUrl(returnTo));
        }
        if (isSafeReturnTo(returnTo)) {
            res.addHeader(
                    HttpHeaders.SET_COOKIE,
                    buildCookie(RETURN_TO_COOKIE, URLEncoder.encode(returnTo, StandardCharsets.UTF_8), RETURN_TO_TTL)
                            .toString());
        }
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
        // A provider with no OAuth dance cannot answer authenticateWithCode(), so bounce to the entry screen.
        if (!provider.supportsRedirectFlow()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(entryPageUrl(null)))
                    .build();
        }
        // CSRF: popCookie consumes the state so it can't be replayed. On a miss restart sign-in rather than render raw
        // JSON to a top-level navigation.
        String expectedState = popCookie(req, res, STATE_COOKIE);
        if (expectedState == null || state == null || !constantTimeEquals(expectedState, state)) {
            log.warn("auth/callback: missing or mismatched OAuth state; restarting sign-in");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(authProps.getFrontendUrl()))
                    .build();
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

        // This is a top-level navigation, so a refusal lands on the sign-in screen with a reason, not raw JSON.
        try {
            signupPolicy.admit(r.email(), r.workosUserId());
        } catch (TessaryException e) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(signupRefusedUrl()))
                    .build();
        }

        Principal user =
                tenants.upsertUserFromWorkos(r.workosUserId(), r.email(), r.displayName(), r.profilePictureUrl());
        tenants.consumePendingInvitations(user);
        Organization defaultOrg = tenants.ensureDefaultOrg(user, r.organizationId());

        // A null here means a malformed 2xx body; treat it as an auth failure rather than seal a broken session.
        if (r.accessToken() == null) {
            log.warn("auth/callback: WorkOS 2xx response carried no access_token");
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(),
                            new ErrorBody("auth.workos_failed", "no access token", null)));
        }
        SealedSession session = new SealedSession(
                r.refreshToken(),
                r.accessTokenExpiresAt().toString(),
                user.workosUserId(),
                r.organizationId() != null ? r.organizationId() : defaultOrg.workosOrgId(),
                r.sessionId());
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

    /** 409 on a duplicate email or any other {@link AuthProvider.AuthException}. */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest req) {
        // Before the provider, because PasswordAuthProvider's signup IS the principal insert.
        try {
            signupPolicy.admit(req.email(), null);
        } catch (TessaryException e) {
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

    /** These two routes are {@code fetch()} calls, so they answer 200 JSON rather than {@code /callback}'s 302. */
    private ResponseEntity<?> establishSession(AuthProvider.AuthResult r) {
        try {
            signupPolicy.admit(r.email(), r.workosUserId());
        } catch (TessaryException e) {
            return signupRefused(e);
        }
        Principal user =
                tenants.upsertUserFromWorkos(r.workosUserId(), r.email(), r.displayName(), r.profilePictureUrl());
        tenants.consumePendingInvitations(user);
        Organization defaultOrg = tenants.ensureDefaultOrg(user, r.organizationId());

        // Defensive: a future credential provider might not set a token, and this is the one seam both credential
        // routes share.
        if (r.accessToken() == null) {
            log.warn("auth: provider returned no access token for principal {}", user.id());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(),
                            new ErrorBody("auth.provider_failed", "no access token", null)));
        }

        SealedSession session = new SealedSession(
                r.refreshToken(),
                r.accessTokenExpiresAt().toString(),
                user.workosUserId(),
                r.organizationId() != null ? r.organizationId() : defaultOrg.workosOrgId(),
                r.sessionId());
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

    private static ResponseEntity<?> signupRefused(TessaryException e) {
        return ResponseEntity.status(e.error().status())
                .body(ApiResponse.failure(
                        e.error().status().value(), new ErrorBody(e.error().code(), e.getMessage(), null)));
    }

    private String signupRefusedUrl() {
        return frontendBase() + "/login?error=signup_refused";
    }

    /** TESSARY_AUTH_FRONTEND_URL is operator-supplied and may lack a trailing "/", so strip defensively. */
    private String frontendBase() {
        String frontendUrl = authProps.getFrontendUrl();
        if (frontendUrl.endsWith("/")) {
            frontendUrl = frontendUrl.substring(0, frontendUrl.length() - 1);
        }
        return frontendUrl;
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        // POST-only: a GET logout could be triggered by <img src> or any cross-site navigation. A 302 in a fetch()
        // response would chase the browser into an opaque redirect, so return JSON.
        SealedSession session = readSession(req);
        String sessionId = session != null ? session.sessionId() : null;
        String next = provider.signOutUrl(sessionId, authProps.getFrontendUrl());
        log.info("auth/logout: signed out, provider session id {}", sessionId != null ? "present" : "absent");
        String kill = buildCookie(authProps.getCookieName(), "", Duration.ZERO).toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, kill)
                .body(ApiResponse.ok(Map.of("frontendUrl", next)));
    }

    private @Nullable SealedSession readSession(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (authProps.getCookieName().equals(c.getName())) return cipher.unseal(c.getValue());
        }
        return null;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        TenantContext ctx = (TenantContext) req.getAttribute(TenantContext.ATTRIBUTE);
        if (ctx == null || !ctx.isAuthenticated()) {
            // /auth/me is the one endpoint where "not signed in" is a normal response, so 401 in the ApiResponse shape.
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
        // A UI affordance hint, not an authorization: every staff route re-checks identity and org standing
        // server-side.
        me.put("platform_staff", staff.isStaff(ctx));
        return ResponseEntity.ok(ApiResponse.ok(me));
    }

    private String randomState() {
        byte[] b = new byte[32];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Single-use: the cookie is expired as it is read. */
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

    /** Raw {@code res.addCookie} drops attributes inconsistently under Tomcat 11 + ResponseEntity. */
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
     * Bouncing to the app root would send an unauthenticated visitor straight back to this GET, an infinite redirect
     * loop. With no account yet the target is {@code /signup}, saving a second hop.
     */
    private String entryPageUrl(@Nullable String returnTo) {
        // Both callers are degrade branches, so the redirectFlow half of isFirstRun is already false here.
        String base = frontendBase() + (users.anyHumanExists() ? "/login" : "/signup");
        if (isSafeReturnTo(returnTo)) {
            return base + "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
        }
        return base;
    }

    private static boolean isSafeReturnTo(@Nullable String returnTo) {
        // Same-origin relative paths only: the second char must not be '/' or '\' (Chromium normalises "/\" into
        // "//host", the same open-redirect class as "//evil").
        if (returnTo == null || returnTo.length() < 1) return false;
        if (returnTo.charAt(0) != '/') return false;
        if (returnTo.length() >= 2) {
            char c = returnTo.charAt(1);
            if (c == '/' || c == '\\') return false;
        }
        return true;
    }

    private @Nullable String readReturnTo(HttpServletRequest req, HttpServletResponse res) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (!RETURN_TO_COOKIE.equals(c.getName())) continue;
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
