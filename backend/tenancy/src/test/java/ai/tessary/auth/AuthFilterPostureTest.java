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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The four-way posture table of {@link AuthFilter#shouldNotFilter}, held here because #924's whole
 * subject is one of those four cells changing.
 *
 * <p>Plain JUnit with hand-built collaborators, on the {@code AbsentSopIntakeTest} /
 * {@code AbsentSlackMentionSourceTest} precedent: this needs no database, no Spring context and no
 * Docker, so it runs everywhere and it runs fast. It also cannot be weakened by the suite-wide
 * {@code TestAuthDisabledInitializer}, which is the point — a test asserting the closed default
 * must not sit in the module where the default is globally flipped open.
 */
class AuthFilterPostureTest {

    /** Actuator paths that must require authentication. Shared so the bypass and response
     * assertions below cannot drift apart -- they did, and that is how the index slipped through. */
    private static final String[] ACTUATOR_GUARDED = {
        "/actuator", "/actuator/env", "/actuator/loggers", "/actuator/heapdump", "/actuator/prometheus"
    };

    private static AuthFilter filter(boolean providerConfigured, boolean authDisabled) {
        return filter(providerConfigured, authDisabled, noPaidBypasses());
    }

    private static AuthFilter filter(
            boolean providerConfigured, boolean authDisabled, ObjectProvider<SelfAuthenticatingPath> paidBypasses) {
        return filter(providerConfigured, authDisabled, paidBypasses, mock(BearerTokenAuthenticator.class), notStaff());
    }

    /**
     * Full-control overload for the #935 authorization-boundary tests below, which need to drive a
     * genuinely non-null {@link TenantContext} through {@code doFilterInternal} (via a stubbed
     * {@code bearerAuth}) and a specific {@link PlatformStaff#isStaff} answer. The other overloads
     * default to "not staff" — harmless to every {@code shouldNotFilter}-only test above, since none
     * of them populate a ctx, but it is what makes the new tests actually exercise the new branch
     * instead of passing vacuously through the pre-existing {@code ctx == null} 401 arm.
     */
    private static AuthFilter filter(
            boolean providerConfigured,
            boolean authDisabled,
            ObjectProvider<SelfAuthenticatingPath> paidBypasses,
            BearerTokenAuthenticator bearerAuth,
            PlatformStaff platformStaff) {
        AuthProvider provider = mock(AuthProvider.class);
        when(provider.isEnabled()).thenReturn(providerConfigured);
        AuthProperties auth = new AuthProperties();
        auth.setDisabled(authDisabled);
        // Everything after the two property/provider objects is unreachable from shouldNotFilter, which reads
        // only the path, those two, and (for the paid-bypass cases below) paidBypasses. Bare mocks
        // rather than nulls: the constructor's parameters are not @Nullable, and NullAway is on for test
        // compilation too.
        return new AuthFilter(
                auth,
                provider,
                mock(SessionCipher.class),
                mock(PrincipalRepository.class),
                bearerAuth,
                new ObjectMapper(),
                paidBypasses,
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

    /** A bearer authenticator stubbed to resolve every request to the same fixed context —
     * enough to drive a request through the cookie-miss/bearer-fallback path in doFilterInternal
     * without a real ApiKeyService or ProjectRepository. */
    private static BearerTokenAuthenticator authenticatingAs(TenantContext ctx) {
        BearerTokenAuthenticator bearerAuth = mock(BearerTokenAuthenticator.class);
        when(bearerAuth.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.of(ctx));
        return bearerAuth;
    }

    /**
     * An empty {@code ObjectProvider} — the open edition's own state, with zero
     * {@link SelfAuthenticatingPath} implementations on the classpath. Same convention
     * {@code AbsentSlackMentionSourceTest.noSource()} established one module over (#920).
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<SelfAuthenticatingPath> noPaidBypasses() {
        ObjectProvider<SelfAuthenticatingPath> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    private static HttpServletRequest guarded() {
        return new MockHttpServletRequest("GET", "/api/orgs/acme/projects/web/traces");
    }

    @Test
    @DisplayName("no provider and no explicit opt-in: the request is FILTERED, i.e. 401 — the #924 fix")
    void absentProviderFailsClosed() {
        assertFalse(
                filter(false, false).shouldNotFilter(guarded()),
                "an unconfigured instance must refuse guarded paths, not serve them; this is the cell that "
                        + "used to put every /api/** path on the internet unauthenticated");
    }

    @Test
    @DisplayName("no provider but the operator opted in: bypassed — the dev stack and the suite")
    void absentProviderWithExplicitOptInBypasses() {
        assertTrue(filter(false, true).shouldNotFilter(guarded()));
    }

    @Test
    @DisplayName("the flag bypasses regardless of provider state — re-decided by #852/#996")
    void flagWinsRegardlessOfProviderState() {
        // Was "a configured provider always enforces, even with the opt-in set": #852 added
        // PasswordAuthProvider, unconditionally enabled, which made "no provider configured" a
        // state the open edition can no longer be in — the old precedence's provider check could
        // never fire again, silently retiring the operator's own TESSARY_AUTH_DISABLED escape hatch
        // (the dev stack and #996's own crew review both hit this). The flag is now authoritative
        // on its own, full stop; a test that wants enforcement despite the suite's global
        // disabled=true default must say so explicitly (see TestAuthDisabledInitializer's javadoc)
        // rather than lean on provider-configured precedence, which no longer exists.
        assertTrue(filter(true, true).shouldNotFilter(guarded()));
    }

    @Test
    @DisplayName("a configured provider enforces by default")
    void configuredProviderEnforces() {
        assertFalse(filter(true, false).shouldNotFilter(guarded()));
    }

    @Test
    @DisplayName("the unauthenticated-by-design paths stay bypassed in every posture")
    void byDesignPathsAreUnaffected() {
        for (String path : new String[] {"/auth/login", "/auth/callback", "/v3/api-docs"}) {
            assertTrue(
                    filter(true, false).shouldNotFilter(new MockHttpServletRequest("GET", path)),
                    path + " is unauthenticated by design and must not be caught by the closed default");
        }
    }

    @Test
    @DisplayName("/auth/mode is unauthenticated like /auth/me: not bypassed, and not hard-401'd (#853)")
    void authModeIsNotBypassedButAlsoNotHard401d() throws ServletException, IOException {
        // Same shape as /auth/me (see the NOTE at the top of AuthFilter.shouldNotFilter): the
        // filter doesn't add /auth/mode to the bypass list, but it also doesn't hard-401 a null
        // context for it -- only /api/** and actuator paths get that treatment. The controller
        // answers unconditionally either way, so a signed-out visitor can still learn which auth
        // flow to render.
        assertFalse(
                filter(true, false).shouldNotFilter(new MockHttpServletRequest("GET", "/auth/mode")),
                "/auth/mode must not be in the bypass list, exactly like /auth/me");

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/auth/mode");
        filter(true, false).doFilterInternal(req, res, chain);

        assertEquals(req, chain.getRequest(), "/auth/mode with no session must still reach the controller");
        assertEquals(200, res.getStatus(), "MockFilterChain never actually writes a status; asserted for clarity");
    }

    @Test
    @DisplayName("with no paid-contributed bypass, the old Slack path is filtered like any other path (#920)")
    void withNoPaidBypassTheSlackPathIsFiltered() {
        assertFalse(
                filter(true, false).shouldNotFilter(new MockHttpServletRequest("POST", "/internal/slack/mention")),
                "AuthFilter no longer hard-codes this path; an edition with no SelfAuthenticatingPath "
                        + "implementation must not bypass it either — the coverage that did not exist "
                        + "anywhere before #920 added it");
    }

    @Test
    @DisplayName("a paid-contributed SelfAuthenticatingPath bypasses the path it names, and no other")
    void aPaidContributedBypassIsHonoured() {
        SelfAuthenticatingPath stub = path -> "/internal/slack/mention".equals(path);
        @SuppressWarnings("unchecked")
        ObjectProvider<SelfAuthenticatingPath> provider = mock(ObjectProvider.class);
        // thenAnswer, not thenReturn: shouldNotFilter is called twice below, and a Stream can only be
        // consumed once — a fixed instance would throw IllegalStateException on the second call.
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(stub));

        AuthFilter withBypass = filter(true, false, provider);
        assertTrue(
                withBypass.shouldNotFilter(new MockHttpServletRequest("POST", "/internal/slack/mention")),
                "the seam mechanism itself, independent of any concrete paid bean: a provider that "
                        + "claims this path must bypass it");
        assertFalse(
                withBypass.shouldNotFilter(guarded()),
                "a bypass scoped to one path must not widen to an unrelated guarded path");
    }

    @Test
    @DisplayName("actuator health and its probes stay public")
    void actuatorHealthIsPublic() {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertTrue(
                    filter(true, false).shouldNotFilter(new MockHttpServletRequest("GET", path)),
                    path + " is polled by orchestrators before anything holds a credential");
        }
    }

    @Test
    @DisplayName("every other actuator endpoint is filtered, exposed or not (#929)")
    void actuatorNonHealthIsFiltered() {
        // Not currently exposed -- Spring Boot's default is `health` alone. That is the point: the
        // guarantee must hold for the endpoint a self-hoster adds tomorrow, not just the ones
        // shipped today, because widening exposure must not widen the unauthenticated surface.
        for (String path : ACTUATOR_GUARDED) {
            assertFalse(
                    filter(true, false).shouldNotFilter(new MockHttpServletRequest("GET", path)),
                    path + " must require authentication");
        }
    }

    /**
     * Not being bypassed is only half of it, and on its own it is the cosmetic half: a path that
     * escapes {@code shouldNotFilter} still reaches {@code chain.doFilter} unless something rejects
     * it. The bare {@code /actuator} index passed the {@code shouldNotFilter} assertion above while
     * being served unauthenticated, because {@code "/actuator".startsWith("/actuator/")} is false
     * and the 401 arm used a bare prefix. These tests assert the response, which is the half that
     * would have caught it.
     */
    @Test
    @DisplayName("a guarded actuator path with no credentials is answered 401, not served")
    void actuatorGuardedPathsAreRejectedNotServed() throws ServletException, IOException {
        for (String path : ACTUATOR_GUARDED) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter(true, false).doFilterInternal(new MockHttpServletRequest("GET", path), res, chain);

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
        filter(true, false).doFilterInternal(new MockHttpServletRequest("GET", "/actuator"), res, chain);

        assertEquals(401, res.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("a health GROUP is not public — show-details:always must not publish /actuator/health/db")
    void healthGroupsAreNotPublic() {
        assertFalse(
                filter(true, false).shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/db")),
                "the probes are enumerated, not prefixed, so a health component stays guarded");
    }

    @Test
    @DisplayName("a health-prefixed sibling does not inherit the exemption by name")
    void healthPrefixedSiblingsAreNotPublic() {
        assertFalse(
                filter(true, false).shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/healthz")),
                "/actuator/health is matched exactly, so a same-prefix sibling must not be public");
    }

    // ---------------------------------------------------------------------------------------
    // #935: authentication alone (a non-null ctx) is no longer enough for a guarded actuator
    // path -- the caller must also be platform staff. Every case here drives a genuinely
    // non-null TenantContext through doFilterInternal via a stubbed bearerAuth, unlike the
    // ctx==null cases above, so it actually exercises the new branch rather than passing
    // vacuously through the pre-existing 401 arm.
    // ---------------------------------------------------------------------------------------

    private static final TenantContext SOME_AUTHENTICATED_USER =
            new TenantContext("user-1", "someone@example.com", null, null, null, null);

    @Test
    @DisplayName("an authenticated, non-staff caller is rejected 403, not served (#935)")
    void actuatorGuardedPathsRejectNonStaffWithForbidden() throws ServletException, IOException {
        for (String path : ACTUATOR_GUARDED) {
            AuthFilter filter =
                    filter(true, false, noPaidBypasses(), authenticatingAs(SOME_AUTHENTICATED_USER), notStaff());
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
    @DisplayName("an authenticated, platform-staff caller reaches the chain (#935)")
    void actuatorGuardedPathsPassStaff() throws ServletException, IOException {
        for (String path : ACTUATOR_GUARDED) {
            AuthFilter filter =
                    filter(true, false, noPaidBypasses(), authenticatingAs(SOME_AUTHENTICATED_USER), isStaff());
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.addHeader("Authorization", "Bearer irrelevant-to-the-stub");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(req, res, chain);

            assertEquals(req, chain.getRequest(), path + " must reach the chain for a staff caller");
        }
    }

    @Test
    @DisplayName("the CSRF rejection body is unchanged by the reject403(code, message) refactor (#935)")
    void csrfRejectionBodyIsUnchanged() throws ServletException, IOException {
        // Drives the pre-existing CSRF arm for real: a cookie-authed, mutating /api/ request with
        // no X-Requested-With header. Step 3 only changed reject403's signature (a hardcoded
        // message became a parameter) -- this pins the existing call site's observable JSON body
        // to exactly what it was before that refactor.
        var cipher = mock(SessionCipher.class);
        var session = new SealedSession(
                "access-token",
                "refresh-token",
                java.time.Instant.now().plusSeconds(3600).toString(),
                "workos-user-1",
                "someone@example.com",
                null,
                null,
                null);
        when(cipher.unseal(org.mockito.ArgumentMatchers.any())).thenReturn(session);

        var users = mock(PrincipalRepository.class);
        var principal = ai.tessary.tenant.Principal.human(
                "user-1", "workos-user-1", "someone@example.com", null, null, "2026-01-01T00:00:00Z", null);
        when(users.findByWorkosId("workos-user-1")).thenReturn(java.util.Optional.of(principal));

        AuthProvider provider = mock(AuthProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        AuthFilter filter = new AuthFilter(
                new AuthProperties(),
                provider,
                cipher,
                users,
                mock(BearerTokenAuthenticator.class),
                new ObjectMapper(),
                noPaidBypasses(),
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
                        + "header must still be rejected by the CSRF arm, not the new #935 actuator arm");
        assertNull(chain.getRequest());
        String body = res.getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("\"csrf.missing_xrw\"") && body.contains("\"request rejected by CSRF guard\""),
                "the pre-existing CSRF call site's code and message text must survive the reject403(code, "
                        + "message) signature change byte-for-byte: " + body);
    }
}
