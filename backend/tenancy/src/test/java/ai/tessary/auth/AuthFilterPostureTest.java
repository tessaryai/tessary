// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.tenant.PrincipalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The posture table of {@link AuthFilter#shouldNotFilter}.
 *
 * <p>Plain JUnit with hand-built collaborators: no database, no Spring context, no Docker, so it
 * runs everywhere and fast. It also cannot be weakened by the suite-wide {@code
 * TestAuthDisabledInitializer}, since a test asserting the closed default must not sit in the
 * module where the default is globally flipped open.
 */
class AuthFilterPostureTest {

    /** Actuator paths that must require authentication. Shared so the bypass and response
     * assertions below cannot drift apart -- they did, and that is how the index slipped through. */
    private static final String[] ACTUATOR_GUARDED = {
        "/actuator", "/actuator/env", "/actuator/loggers", "/actuator/heapdump", "/actuator/prometheus"
    };

    private static AuthFilter filter(boolean authDisabled) {
        return filter(authDisabled, mock(BearerTokenAuthenticator.class), notStaff());
    }

    /**
     * Full-control overload for the authorization-boundary tests below, which drive a genuinely
     * non-null {@link TenantContext} through {@code doFilterInternal} (via a stubbed {@code
     * bearerAuth}) and a specific {@link PlatformStaff#isStaff} answer. The other overload defaults
     * to "not staff", harmless above since none of those tests populate a ctx.
     */
    private static AuthFilter filter(
            boolean authDisabled, BearerTokenAuthenticator bearerAuth, PlatformStaff platformStaff) {
        AuthProperties auth = new AuthProperties();
        auth.setDisabled(authDisabled);
        // Everything after the property object is unreachable from shouldNotFilter, which only
        // reads the path and that. Bare mocks rather than nulls: the constructor's parameters are
        // not @Nullable, and NullAway checks test compilation too.
        return new AuthFilter(
                auth,
                mock(AuthProvider.class),
                mock(SessionCipher.class),
                mock(PrincipalRepository.class),
                bearerAuth,
                new ObjectMapper(),
                platformStaff);
    }

    /** A {@link PlatformStaff} stub that answers "not staff" to everything — the safe default for
     * every test above that never populates a ctx, and the negative case for the new ones below. */
    private static PlatformStaff notStaff() {
        PlatformStaff staff = mock(PlatformStaff.class);
        when(staff.isStaff(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        return staff;
    }

    /** A {@link PlatformStaff} stub that answers "staff" to everything. */
    private static PlatformStaff isStaff() {
        PlatformStaff staff = mock(PlatformStaff.class);
        when(staff.isStaff(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        return staff;
    }

    /** A bearer authenticator stubbed to resolve every request to the same fixed context: drives
     * the cookie-miss/bearer-fallback path in doFilterInternal without a real ApiKeyService or
     * ProjectRepository. */
    private static BearerTokenAuthenticator authenticatingAs(TenantContext ctx) {
        BearerTokenAuthenticator bearerAuth = mock(BearerTokenAuthenticator.class);
        when(bearerAuth.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of(ctx));
        return bearerAuth;
    }

    private static HttpServletRequest guarded() {
        return new MockHttpServletRequest("GET", "/api/orgs/acme/projects/web/traces");
    }

    @Test
    @DisplayName("the flag bypasses regardless of provider state")
    void flagWinsRegardlessOfProviderState() {
        // A provider is always configured (PasswordAuthProvider is the fallback), so "no provider
        // configured" is not a reachable state. The flag is authoritative on its own; a test that wants
        // enforcement despite the suite's global disabled=true default must say so explicitly
        // (see TestAuthDisabledInitializer's javadoc).
        assertTrue(filter(true).shouldNotFilter(guarded()));
    }

    @Test
    @DisplayName("a configured provider enforces by default")
    void configuredProviderEnforces() {
        assertFalse(filter(false).shouldNotFilter(guarded()));
    }

    @Test
    @DisplayName("the unauthenticated-by-design paths stay bypassed in every posture")
    void byDesignPathsAreUnaffected() {
        for (String path : new String[] {"/auth/login", "/auth/callback", "/v3/api-docs"}) {
            assertTrue(
                    filter(false).shouldNotFilter(new MockHttpServletRequest("GET", path)),
                    path + " is unauthenticated by design and must not be caught by the closed default");
        }
    }

    @Test
    @DisplayName("/auth/mode is unauthenticated like /auth/me: not bypassed, and not hard-401'd")
    void authModeIsNotBypassedButAlsoNotHard401d() throws ServletException, IOException {
        // Same shape as /auth/me: the filter doesn't add /auth/mode to the bypass list, but it
        // also doesn't hard-401 a null context for it -- only /api/** and actuator paths get that
        // treatment. The controller answers unconditionally, so a signed-out visitor can still
        // learn which auth flow to render.
        assertFalse(
                filter(false).shouldNotFilter(new MockHttpServletRequest("GET", "/auth/mode")),
                "/auth/mode must not be in the bypass list, exactly like /auth/me");

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/auth/mode");
        filter(false).doFilterInternal(req, res, chain);

        assertEquals(req, chain.getRequest(), "/auth/mode with no session must still reach the controller");
        assertEquals(200, res.getStatus(), "MockFilterChain never actually writes a status; asserted for clarity");
    }

    @Test
    @DisplayName("actuator health and its probes stay public")
    void actuatorHealthIsPublic() {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertTrue(
                    filter(false).shouldNotFilter(new MockHttpServletRequest("GET", path)),
                    path + " is polled by orchestrators before anything holds a credential");
        }
    }

    @Test
    @DisplayName("every other actuator endpoint is filtered, exposed or not")
    void actuatorNonHealthIsFiltered() {
        // Not currently exposed -- Spring Boot's default is `health` alone. That is the point: the
        // guarantee must hold for the endpoint a self-hoster adds tomorrow, not just the ones
        // shipped today, because widening exposure must not widen the unauthenticated surface.
        for (String path : ACTUATOR_GUARDED) {
            assertFalse(
                    filter(false).shouldNotFilter(new MockHttpServletRequest("GET", path)),
                    path + " must require authentication");
        }
    }

    /**
     * A path that escapes {@code shouldNotFilter} still reaches {@code chain.doFilter} unless
     * something rejects it. The bare {@code /actuator} index passed {@code shouldNotFilter} above
     * while being served unauthenticated, because {@code "/actuator".startsWith("/actuator/")} is
     * false and the 401 arm used a bare prefix. These tests assert the response, the half that
     * would have caught it.
     */
    @Test
    @DisplayName("a guarded actuator path with no credentials is answered 401, not served")
    void actuatorGuardedPathsAreRejectedNotServed() throws ServletException, IOException {
        for (String path : ACTUATOR_GUARDED) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter(false).doFilterInternal(new MockHttpServletRequest("GET", path), res, chain);

            assertEquals(401, res.getStatus(), path + " must be rejected");
            assertNull(chain.getRequest(), path + " must never reach the filter chain unauthenticated");
        }
    }

    @Test
    @DisplayName("the bare /actuator index is guarded — startsWith(\"/actuator/\") does not match it")
    void bareActuatorIndexIsGuarded() throws ServletException, IOException {
        // Spring Boot serves this by default as a HAL page listing every exposed endpoint, so an
        // unauthenticated index is a map of the management surface even when each entry is guarded.
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(false).doFilterInternal(new MockHttpServletRequest("GET", "/actuator"), res, chain);

        assertEquals(401, res.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("a health GROUP is not public — show-details:always must not publish /actuator/health/db")
    void healthGroupsAreNotPublic() {
        assertFalse(
                filter(false).shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/db")),
                "the probes are enumerated, not prefixed, so a health component stays guarded");
    }

    @Test
    @DisplayName("a health-prefixed sibling does not inherit the exemption by name")
    void healthPrefixedSiblingsAreNotPublic() {
        assertFalse(
                filter(false).shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/healthz")),
                "/actuator/health is matched exactly, so a same-prefix sibling must not be public");
    }

    // ---------------------------------------------------------------------------------------
    // Authentication alone (a non-null ctx) is not enough for a guarded actuator path: the
    // caller must also be platform staff. Every case here drives a genuinely non-null
    // TenantContext through doFilterInternal via a stubbed bearerAuth, so it exercises the
    // staff-check branch rather than the ctx == null 401 arm above.
    // ---------------------------------------------------------------------------------------

    private static final TenantContext SOME_AUTHENTICATED_USER =
            new TenantContext("user-1", "someone@example.com", null, null, null, null);

    @Test
    @DisplayName("an authenticated, non-staff caller is rejected 403, not served")
    void actuatorGuardedPathsRejectNonStaffWithForbidden() throws ServletException, IOException {
        for (String path : ACTUATOR_GUARDED) {
            AuthFilter filter = filter(false, authenticatingAs(SOME_AUTHENTICATED_USER), notStaff());
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.addHeader("Authorization", "Bearer irrelevant-to-the-stub");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(req, res, chain);

            assertEquals(403, res.getStatus(), path + " must reject an authenticated non-staff caller");
            assertNull(chain.getRequest(), path + " must never reach the filter chain for a non-staff caller");
        }
    }

    @Test
    @DisplayName("an authenticated, platform-staff caller reaches the chain")
    void actuatorGuardedPathsPassStaff() throws ServletException, IOException {
        for (String path : ACTUATOR_GUARDED) {
            AuthFilter filter = filter(false, authenticatingAs(SOME_AUTHENTICATED_USER), isStaff());
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.addHeader("Authorization", "Bearer irrelevant-to-the-stub");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(req, res, chain);

            assertEquals(req, chain.getRequest(), path + " must reach the chain for a staff caller");
        }
    }

    @Test
    @DisplayName("the CSRF rejection body is unchanged by the reject403(code, message) refactor")
    void csrfRejectionBodyIsUnchanged() throws ServletException, IOException {
        // Drives the pre-existing CSRF arm for real: a cookie-authed, mutating /api/ request with
        // no X-Requested-With header. Step 3 only changed reject403's signature (a hardcoded
        // message became a parameter) -- this pins the existing call site's observable JSON body
        // to exactly what it was before that refactor.
        var cipher = mock(SessionCipher.class);
        var session = new SealedSession(
                "refresh-token", java.time.Instant.now().plusSeconds(3600).toString(), "workos-user-1", null);
        when(cipher.unseal(org.mockito.ArgumentMatchers.any())).thenReturn(session);

        var users = mock(PrincipalRepository.class);
        var principal = ai.tessary.tenant.Principal.human(
                "user-1", "workos-user-1", "someone@example.com", null, null, "2026-01-01T00:00:00Z", null);
        when(users.findByWorkosId("workos-user-1")).thenReturn(java.util.Optional.of(principal));

        AuthFilter filter = new AuthFilter(
                new AuthProperties(),
                mock(AuthProvider.class),
                cipher,
                users,
                mock(BearerTokenAuthenticator.class),
                new ObjectMapper(),
                notStaff());

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/orgs/acme/projects/web/traces");
        req.setCookies(new jakarta.servlet.http.Cookie(new AuthProperties().getCookieName(), "opaque-cookie-value"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertEquals(
                403,
                res.getStatus(),
                "a cookie-authed mutating /api/ request with no X-Requested-With "
                        + "header must still be rejected by the CSRF arm, not the actuator arm");
        assertNull(chain.getRequest());
        String body = res.getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("\"csrf.missing_xrw\"") && body.contains("\"request rejected by CSRF guard\""),
                "the pre-existing CSRF call site's code and message text must survive the reject403(code, "
                        + "message) signature change byte-for-byte: " + body);
    }

    // ---------------------------------------------------------------------------------------
    // The cookie session: a real SessionCipher seals each cookie, so every case below is a cookie
    // the filter could genuinely receive, and a reissued cookie can be opened and read back.
    // ---------------------------------------------------------------------------------------

    private static final String GUARDED_API = "/api/orgs/acme/projects/web/traces";
    private static final String FAR_FUTURE = "2099-01-01T00:00:00Z";
    private static final String LONG_AGO = "2020-01-01T00:00:00Z";

    private static AuthProperties cookieProps() {
        AuthProperties p = new AuthProperties();
        p.setCookieName("sid");
        p.setCookieSecure(true);
        p.setCookiePassword(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        return p;
    }

    private static final SessionCipher CIPHER = new SessionCipher(cookieProps(), new ObjectMapper());

    private static PrincipalRepository knowsWos1() {
        PrincipalRepository users = mock(PrincipalRepository.class);
        when(users.findByWorkosId("wos_1"))
                .thenReturn(java.util.Optional.of(ai.tessary.tenant.Principal.human(
                        "usr_1", "wos_1", "ada@example.com", null, null, "2026-01-01T00:00:00Z", null)));
        return users;
    }

    private static AuthFilter cookieFilter(AuthProvider provider, PrincipalRepository users) {
        return new AuthFilter(
                cookieProps(),
                provider,
                CIPHER,
                users,
                mock(BearerTokenAuthenticator.class),
                new ObjectMapper(),
                notStaff());
    }

    private static MockHttpServletRequest withCookie(String method, String path, jakarta.servlet.http.Cookie... c) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setCookies(c);
        return req;
    }

    private static jakarta.servlet.http.Cookie session(
            @org.jspecify.annotations.Nullable String refreshToken,
            @org.jspecify.annotations.Nullable String expiresAt,
            String workosUserId) {
        return new jakarta.servlet.http.Cookie(
                "sid", CIPHER.seal(new SealedSession(refreshToken, expiresAt, workosUserId, "org_old")));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> untrustedCookies() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "no session cookie among others", new jakarta.servlet.http.Cookie("theme", "dark")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tampered or foreign-key cookie", new jakarta.servlet.http.Cookie("sid", "not-a-sealed-value")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "principal gone since the cookie was issued", session("rt_1", FAR_FUTURE, "wos_gone")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "expired with no refresh token", session(null, LONG_AGO, "wos_1")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "unparseable expiry reads as expired", session(null, "yesterday", "wos_1")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "absent expiry reads as expired", session(null, null, "wos_1")));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("untrustedCookies")
    @DisplayName("a cookie that cannot vouch for a live principal is no session: the API answers 401")
    void untrustedCookieIsNoSession(String why, jakarta.servlet.http.Cookie cookie)
            throws ServletException, IOException {
        AuthProvider provider = mock(AuthProvider.class);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        cookieFilter(provider, knowsWos1()).doFilterInternal(withCookie("GET", GUARDED_API, cookie), res, chain);

        assertEquals(401, res.getStatus(), why);
        assertNull(chain.getRequest(), why);
        // With no refresh token there is nothing to refresh with: the provider is never asked.
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("an expired session whose refresh fails or comes back without a token is no session")
    void failedRefreshIsNoSession() throws ServletException, IOException {
        AuthProvider provider = mock(AuthProvider.class);
        when(provider.refresh("rt_1", "org_old"))
                .thenThrow(new AuthProvider.AuthException("revoked"))
                .thenReturn(new AuthProvider.AuthResult(
                        null, "rt_2", java.time.Instant.parse(FAR_FUTURE), null, null, null, null, null, null));
        AuthFilter filter = cookieFilter(provider, knowsWos1());

        for (String attempt : new String[] {"provider throws", "2xx without access_token"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilterInternal(withCookie("GET", GUARDED_API, session("rt_1", LONG_AGO, "wos_1")), res, chain);

            assertEquals(401, res.getStatus(), attempt);
            assertNull(res.getCookie("sid"), attempt + ": a failed refresh must not reissue the cookie");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
            nullValues = "NULL",
            value = {"NULL, NULL, wos_1, org_old", "wos_2, org_new, wos_2, org_new"})
    @DisplayName("a refreshed session is resealed, keeping the old ids where the provider sent none")
    void expiredSessionIsRefreshedAndReissued(
            @org.jspecify.annotations.Nullable String newUser,
            @org.jspecify.annotations.Nullable String newOrg,
            String sealedUser,
            String sealedOrg)
            throws ServletException, IOException {
        AuthProvider provider = mock(AuthProvider.class);
        when(provider.refresh("rt_1", "org_old"))
                .thenReturn(new AuthProvider.AuthResult(
                        "at_2", "rt_2", java.time.Instant.parse(FAR_FUTURE), newUser, null, null, null, null, newOrg));
        MockHttpServletRequest req = withCookie("GET", GUARDED_API, session("rt_1", LONG_AGO, "wos_1"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        cookieFilter(provider, knowsWos1()).doFilterInternal(req, res, chain);

        assertEquals(req, chain.getRequest());
        assertEquals(
                new TenantContext("usr_1", "ada@example.com", null, null, null, null),
                req.getAttribute(TenantContext.ATTRIBUTE));
        jakarta.servlet.http.Cookie reissued = java.util.Objects.requireNonNull(res.getCookie("sid"));
        assertEquals(new SealedSession("rt_2", FAR_FUTURE, sealedUser, sealedOrg), CIPHER.unseal(reissued.getValue()));
        assertEquals(
                "maxAge=604800 path=/ secure=true httpOnly=true sameSite=Lax",
                "maxAge=" + reissued.getMaxAge() + " path=" + reissued.getPath() + " secure=" + reissued.getSecure()
                        + " httpOnly=" + reissued.isHttpOnly() + " sameSite="
                        + ((org.springframework.mock.web.MockCookie) reissued).getSameSite());
    }

    @Test
    @DisplayName("a request with no method is not a mutation, so a cookie session passes without the CSRF header")
    void nullMethodIsNotMutating() throws ServletException, IOException {
        MockHttpServletRequest req = withCookie("GET", GUARDED_API, session("rt_1", FAR_FUTURE, "wos_1"));
        req.setMethod(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        cookieFilter(mock(AuthProvider.class), knowsWos1()).doFilterInternal(req, res, chain);

        assertEquals(req, chain.getRequest());
    }

    @Test
    @DisplayName("a public probe driven straight into the filter skips the staff check")
    void publicProbeSkipsTheStaffCheck() throws ServletException, IOException {
        AuthFilter filter = filter(false, authenticatingAs(SOME_AUTHENTICATED_USER), notStaff());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        req.addHeader("Authorization", "Bearer irrelevant-to-the-stub");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, new MockHttpServletResponse(), chain);

        assertEquals(req, chain.getRequest(), "the probes stay reachable for any caller, staff or not");
    }

    // ---------------------------------------------------------------------------------------
    // The path the decisions are made on.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the container's servlet path wins over the raw URI")
    void servletPathWinsOverTheRawUri() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orgs/acme/projects/web/traces");
        req.setServletPath("/auth/login");
        assertTrue(filter(false).shouldNotFilter(req));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "/auth/login;jsessionid=1",
                "/v3/api-docs/../api/orgs",
                "/v3/api-docs/..%2Fapi%2Forgs",
                "/v3/api-docs/..%2fapi",
                "/v3/api-docs/%5C..%5Capi",
            })
    @DisplayName("a raw URI carrying traversal or parameter tricks never matches a bypass")
    void obfuscatedUrisAreFiltered(String uri) {
        assertFalse(filter(false).shouldNotFilter(new MockHttpServletRequest("GET", uri)), uri);
    }

    @Test
    @DisplayName("a request with no URI at all is filtered, not bypassed, and does not crash the filter")
    void missingUriIsFiltered() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ignored");
        req.setRequestURI(null);
        MockFilterChain chain = new MockFilterChain();

        assertFalse(filter(false).shouldNotFilter(req));
        filter(false).doFilterInternal(req, new MockHttpServletResponse(), chain);
        assertEquals(req, chain.getRequest(), "an empty path is not under /api/, so it is the controller's call");
    }
}
