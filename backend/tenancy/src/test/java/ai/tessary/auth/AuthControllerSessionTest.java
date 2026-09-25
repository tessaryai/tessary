// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.PrincipalRepository;
import ai.tessary.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The session half of {@link AuthController}: the OAuth login/callback dance (state CSRF, the
 * post-auth redirect cookie, the sealed session it issues), the credential routes' shared tail,
 * logout and the signed-out {@code /auth/me}. A real {@link SessionCipher} seals the cookie so the
 * tests can open it and assert the session it carries.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerSessionTest {

    private static final String STATE = "tessary-oauth-state";
    private static final String RETURN_TO = "tessary-post-auth-redirect";
    private static final Instant EXPIRES = Instant.parse("2026-09-25T13:00:00Z");

    @Mock
    AuthProvider provider;

    @Mock
    TenantService tenants;

    @Mock
    PrincipalRepository users;

    @Mock
    SignupPolicyService policy;

    private SessionCipher cipher;
    private MockMvc mvc;

    private final Principal user =
            Principal.human("usr_1", "wos_1", "ada@example.com", "Ada Lovelace", null, "2026-01-01T00:00:00Z", null);
    private final Organization defaultOrg =
            new Organization("org_1", "org_wos_default", "ada", "Ada", "2026-01-01T00:00:00Z", null, null);

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setFrontendUrl("https://app.example.com/");
        props.setCookieName("sid");
        props.setCookieSecure(true);
        props.setCookiePassword(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new SessionCipher(props, new ObjectMapper());
        AuthController controller = new AuthController(
                props,
                provider,
                cipher,
                tenants,
                org.mockito.Mockito.mock(OrganizationRepository.class),
                org.mockito.Mockito.mock(OrgMembershipRepository.class),
                users,
                org.mockito.Mockito.mock(PlatformStaff.class),
                policy);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static AuthProvider.AuthResult result(@Nullable String accessToken, @Nullable String orgId) {
        return new AuthProvider.AuthResult(
                accessToken, "rt_1", EXPIRES, "wos_1", "ada@example.com", "Ada", "Lovelace", null, orgId);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse();
    }

    /** The cookie {@code name} this response sets, or null. */
    private static @Nullable Cookie cookie(MockHttpServletResponse res, String name) {
        return res.getCookie(name);
    }

    /** A cookie's value and every attribute this controller sets, as one comparable line. */
    private static String describe(@Nullable Cookie c) {
        if (c == null) return "absent";
        return c.getValue() + " maxAge=" + c.getMaxAge() + " path=" + c.getPath() + " secure=" + c.getSecure()
                + " httpOnly=" + c.isHttpOnly() + " sameSite=" + ((MockCookie) c).getSameSite();
    }

    /** What an expired (single-use, consumed) cookie looks like on the wire. */
    private static final String KILLED = " maxAge=0 path=/ secure=true httpOnly=true sameSite=Lax";

    // ------------------------------------------------------------------ GET /auth/login

    static Stream<Arguments> returnToValues() {
        return Stream.of(
                Arguments.of("/traces?tab=1", "%2Ftraces%3Ftab%3D1"),
                Arguments.of("/", "%2F"),
                Arguments.of("//evil.example", null),
                Arguments.of("/\\evil.example", null),
                Arguments.of("https://evil.example/", null),
                Arguments.of("   ", null),
                Arguments.of("", null),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("returnToValues")
    void login_stashesOnlyASameOriginReturnToAndAlwaysMintsState(@Nullable String returnTo, @Nullable String stashed)
            throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(true);
        when(provider.authorizationUrl(anyString()))
                .thenAnswer(inv -> "https://idp.example/authorize?state=" + inv.getArgument(0));
        MockHttpServletRequestBuilder req = get("/auth/login");
        if (returnTo != null) req.param("returnTo", returnTo);

        MockHttpServletResponse res = perform(req);

        Cookie state = Objects.requireNonNull(cookie(res, STATE), "a login must always mint state");
        assertEquals(43, state.getValue().length(), "32 random bytes, base64url without padding");
        assertEquals(
                state.getValue() + " maxAge=600 path=/ secure=true httpOnly=true sameSite=Lax",
                describe(state),
                "the state cookie is HttpOnly, Secure when configured, and lives ten minutes");
        assertEquals("https://idp.example/authorize?state=" + state.getValue(), res.getRedirectedUrl());
        assertEquals(
                stashed == null ? "absent" : stashed + " maxAge=600 path=/ secure=true httpOnly=true sameSite=Lax",
                describe(cookie(res, RETURN_TO)));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "true, /traces, https://app.example.com/login?returnTo=%2Ftraces",
                "false, /traces, https://app.example.com/signup?returnTo=%2Ftraces",
                "true, //evil.example, https://app.example.com/login",
                "true, NULL, https://app.example.com/login"
            })
    void login_withoutARedirectFlowBouncesToTheEntryScreenCarryingOnlyASafeReturnTo(
            boolean humansExist, @Nullable String returnTo, String location) throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(false);
        when(users.anyHumanExists()).thenReturn(humansExist);
        MockHttpServletRequestBuilder req = get("/auth/login");
        if (returnTo != null) req.param("returnTo", returnTo);

        assertEquals(location, perform(req).getRedirectedUrl());
    }

    // ------------------------------------------------------------------ GET /auth/callback

    @Test
    void callback_withoutARedirectFlowBouncesToTheEntryScreenWithoutExchangingTheCode() throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(false);
        when(users.anyHumanExists()).thenReturn(true);

        MockHttpServletResponse res = perform(get("/auth/callback").param("code", "c1"));

        assertEquals(302, res.getStatus());
        assertEquals("https://app.example.com/login", res.getHeader("Location"));
        verify(provider, never()).authenticateWithCode(anyString());
    }

    static Stream<Arguments> stateMismatches() {
        Cookie state = new Cookie(STATE, "s1");
        Cookie other = new Cookie("unrelated", "x");
        return Stream.of(
                Arguments.of(new Cookie[] {}, "s1"),
                Arguments.of(new Cookie[] {other}, "s1"),
                Arguments.of(new Cookie[] {other, state}, null),
                Arguments.of(new Cookie[] {state}, "s2"));
    }

    @ParameterizedTest
    @MethodSource("stateMismatches")
    void callback_restartsSignInWhenTheStateIsMissingOrDoesNotMatch(Cookie[] cookies, @Nullable String state)
            throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(true);
        MockHttpServletRequestBuilder req = get("/auth/callback").param("code", "c1");
        if (cookies.length > 0) req.cookie(cookies);
        if (state != null) req.param("state", state);

        MockHttpServletResponse res = perform(req);

        assertEquals(302, res.getStatus());
        assertEquals("https://app.example.com/", res.getHeader("Location"));
        assertEquals("absent", describe(cookie(res, "sid")), "no session on a CSRF miss");
        verify(provider, never()).authenticateWithCode(anyString());
    }

    @Test
    void callback_consumesTheStateCookieSoTheCodeCannotBeReplayed() throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(true);
        when(provider.authenticateWithCode("c1")).thenThrow(new AuthProvider.AuthException("expired code"));

        MockHttpServletResponse res = perform(
                get("/auth/callback").param("code", "c1").param("state", "s1").cookie(new Cookie(STATE, "s1")));

        assertEquals(KILLED, describe(cookie(res, STATE)));
        // A rejected code is the identity provider failing, not the caller: 502 with its reason.
        assertEquals(502, res.getStatus());
        mvc.perform(get("/auth/callback")
                        .param("code", "c1")
                        .param("state", "s1")
                        .cookie(new Cookie(STATE, "s1")))
                .andExpect(jsonPath("$.meta.error.code").value("auth.workos_failed"))
                .andExpect(jsonPath("$.meta.error.message").value("expired code"));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                // provider org, return-to cookie, expected session org, expected Location
                "NULL, %2Ftraces%3Ftab%3D1, org_wos_default, /traces?tab=1",
                "org_wos_9, NULL, org_wos_9, https://app.example.com/",
                "NULL, %zz, org_wos_default, https://app.example.com/"
            })
    void callback_sealsTheSessionAndLandsOnTheStashedReturnTo(
            @Nullable String providerOrg, @Nullable String returnToCookie, String sessionOrg, String location)
            throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(true);
        when(provider.authenticateWithCode("c1")).thenReturn(result("at_1", providerOrg));
        when(tenants.upsertUserFromWorkos("wos_1", "ada@example.com", "Ada Lovelace", null))
                .thenReturn(user);
        when(tenants.ensureDefaultOrg(user, providerOrg)).thenReturn(defaultOrg);
        MockHttpServletRequestBuilder req =
                get("/auth/callback").param("code", "c1").param("state", "s1");
        if (returnToCookie == null) {
            req.cookie(new Cookie(STATE, "s1"));
        } else {
            req.cookie(new Cookie(STATE, "s1"), new Cookie(RETURN_TO, returnToCookie));
        }

        MockHttpServletResponse res = perform(req);

        assertEquals(302, res.getStatus());
        assertEquals(location, res.getHeader("Location"), "a malformed return-to falls back to the frontend");
        Cookie session = Objects.requireNonNull(cookie(res, "sid"));
        assertEquals(
                new SealedSession("rt_1", EXPIRES.toString(), "wos_1", sessionOrg), cipher.unseal(session.getValue()));
        assertEquals(
                session.getValue() + " maxAge=604800 path=/ secure=true httpOnly=true sameSite=Lax",
                describe(session),
                "the session lives as long as the configured cookie max-age");
        verify(tenants).consumePendingInvitations(user);
        if (returnToCookie != null) {
            assertEquals(KILLED, describe(cookie(res, RETURN_TO)), "the return-to cookie is single-use");
        }
    }

    @Test
    void callback_aTwoHundredWithoutAnAccessTokenIsAnUpstreamFailureNotASession() throws Exception {
        when(provider.supportsRedirectFlow()).thenReturn(true);
        when(provider.authenticateWithCode("c1")).thenReturn(result(null, null));
        when(tenants.upsertUserFromWorkos(any(), any(), any(), any())).thenReturn(user);
        when(tenants.ensureDefaultOrg(user, null)).thenReturn(defaultOrg);

        MockHttpServletResponse res = perform(
                get("/auth/callback").param("code", "c1").param("state", "s1").cookie(new Cookie(STATE, "s1")));

        assertEquals(502, res.getStatus());
        assertEquals("absent", describe(cookie(res, "sid")));
        mvc.perform(get("/auth/callback")
                        .param("code", "c1")
                        .param("state", "s1")
                        .cookie(new Cookie(STATE, "s1")))
                .andExpect(jsonPath("$.meta.error.code").value("auth.workos_failed"))
                .andExpect(jsonPath("$.meta.error.message").value("no access token"));
    }

    // ------------------------------------------------------------------ credential routes, logout, me

    @Test
    void signup_aProviderResultWithoutAnAccessTokenIsA502NotASession() throws Exception {
        when(provider.signupWithCredentials("ada@example.com", "long-enough-password"))
                .thenReturn(result(null, null));
        when(tenants.upsertUserFromWorkos(any(), any(), any(), any())).thenReturn(user);
        when(tenants.ensureDefaultOrg(user, null)).thenReturn(defaultOrg);

        MockHttpServletResponse res = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.meta.error.code").value("auth.provider_failed"))
                .andReturn()
                .getResponse();

        assertEquals("absent", describe(cookie(res, "sid")));
    }

    @Test
    void logout_expiresTheSessionCookieAndNamesTheFrontend() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frontendUrl").value("https://app.example.com/"))
                .andReturn()
                .getResponse();

        assertEquals(KILLED, describe(cookie(res, "sid")));
    }

    @Test
    @SuppressWarnings("NullAway") // deliberate: a context with no user id is the unauthenticated shape
    void me_withNoContextOrAnUnauthenticatedOneIs401() throws Exception {
        mvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.error.code").value("auth.unauthorized"));
        mvc.perform(get("/auth/me")
                        .requestAttr(TenantContext.ATTRIBUTE, new TenantContext(null, null, null, null, null, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.error.code").value("auth.unauthorized"));
    }
}
