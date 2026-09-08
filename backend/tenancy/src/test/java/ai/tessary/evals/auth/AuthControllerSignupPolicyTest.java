// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.evals.open.errors.AuthError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.tenant.OrgMembershipRepository;
import ai.tessary.evals.tenant.OrganizationRepository;
import ai.tessary.evals.tenant.PrincipalRepository;
import ai.tessary.evals.tenant.TenantService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The sign-up policy gate (#1226) at each of the three call sites that create a principal:
 * {@code POST /auth/signup}, {@code POST /auth/login}'s first-time path, and
 * {@code GET /auth/callback}. A refusal must reach the caller as the catalogued error, or as a
 * redirect naming it on the browser-navigation route, and must leave {@link TenantService} untouched.
 */
class AuthControllerSignupPolicyTest {

    private final AuthProvider provider = mock(AuthProvider.class);
    private final TenantService tenants = mock(TenantService.class);
    private final SignupPolicyService policy = mock(SignupPolicyService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setFrontendUrl("http://localhost:3000/");
        AuthController controller = new AuthController(
                props,
                provider,
                mock(SessionCipher.class),
                tenants,
                mock(OrganizationRepository.class),
                mock(OrgMembershipRepository.class),
                mock(PrincipalRepository.class),
                mock(PlatformStaff.class),
                policy);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        doThrow(new EvalsException(AuthError.SIGNUP_REFUSED)).when(policy).admit(any(), any());
    }

    private static AuthProvider.AuthResult result(String email) {
        return new AuthProvider.AuthResult(
                "token", null, Instant.now().plusSeconds(3600), "user_new", email, null, null, null, null, null);
    }

    @Test
    void signupIsRefusedBeforeTheProviderInserts() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stranger@example.com\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.error.code").value("AUTH.SIGNUP_REFUSED"));
        verify(provider, never()).signupWithCredentials(anyString(), anyString());
        verify(tenants, never()).upsertUserFromWorkos(any(), any(), any(), any());
    }

    @Test
    void firstTimeLoginIsRefusedAfterAuthenticationAndBeforeAnyRow() throws Exception {
        when(provider.authenticateWithCredentials("stranger@example.com", "pw"))
                .thenReturn(result("stranger@example.com"));
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stranger@example.com\",\"password\":\"pw\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.error.code").value("AUTH.SIGNUP_REFUSED"));
        verify(policy).admit("stranger@example.com", "user_new");
        verify(tenants, never()).upsertUserFromWorkos(any(), any(), any(), any());
    }

    @Test
    void callbackRefusalLandsOnTheSignInScreenWithTheReason() throws Exception {
        when(provider.isEnabled()).thenReturn(false);
        when(provider.authenticateWithCode("code-1")).thenReturn(result("stranger@example.com"));
        mvc.perform(get("/auth/callback").param("code", "code-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/login?error=signup_refused"));
        verify(tenants, never()).upsertUserFromWorkos(any(), any(), any(), any());
        verify(tenants, never()).ensureDefaultOrg(any(), any());
    }
}
