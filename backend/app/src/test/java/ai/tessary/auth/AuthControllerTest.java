// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code POST /auth/signup} and {@code POST /auth/login} driven through the real
 * {@link PasswordAuthProvider} bean (no WorkOS configured -- the open-edition default posture),
 * plus the {@code GET /auth/login} degrade-to-redirect guard. Container-free on the
 * {@code OpenApiSpecDriftTest} precedent for the property setup; the isolated Postgres database
 * each {@code @SpringBootTest} context gets (via {@code TestcontainersPostgresInitializer}) is what
 * makes an end-to-end signup/login round trip meaningful here, unlike {@link PasswordAuthProviderTest}'s
 * mocked-repository unit coverage.
 */
@SpringBootTest
class AuthControllerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.auth.cookie-password", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("workos.api-key", () -> "");
        r.add("workos.client-id", () -> "");
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthProvider provider;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private String body(String email, String password) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    @Test
    void signupHappyPath() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup-happy@example.com", "a-good-password")))
                .andExpect(status().isOk());
    }

    @Test
    void loginHappyPathAfterSignup() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("login-happy@example.com", "a-good-password")))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("login-happy@example.com", "a-good-password")))
                .andExpect(status().isOk());
    }

    @Test
    void loginWrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrong-pw@example.com", "a-good-password")))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrong-pw@example.com", "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupDuplicateEmailIsRejected() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dup@example.com", "a-good-password")))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dup@example.com", "a-different-password")))
                .andExpect(status().isConflict());
    }

    @Test
    void getLoginDegradesToRedirectRatherThan500() throws Exception {
        // With PasswordAuthProvider active (isEnabled()=true, supportsRedirectFlow()=false), the
        // OAuth GET must degrade to the dev-shortcut redirect, not call authorizationUrl() on a
        // provider that has no OAuth dance -- verifies the AuthController guard added in step 5.
        // The bounce target is the frontend's own /login screen, not the app root -- the app root
        // sits behind ProtectedRoute, which would send an unauthenticated visitor straight back to
        // this same GET, i.e. a redirect loop.
        // The signup below pins which screen that is: a deployment with no account at all goes to
        // /signup instead, and this class shares one database across its methods, so the
        // target would otherwise depend on method order.
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("degrade-target@example.com", "a-good-password")))
                .andExpect(status().isOk());

        mvc.perform(get("/auth/login")).andExpect(status().is3xxRedirection()).andExpect(result -> {
            String location = result.getResponse().getRedirectedUrl();
            org.junit.jupiter.api.Assertions.assertNotNull(location);
            org.junit.jupiter.api.Assertions.assertTrue(
                    location.endsWith("/login"),
                    "expected the redirect to land on the frontend's own /login screen, got: " + location);
        });
    }

    @Test
    void getAuthModeReportsPasswordPosture() throws Exception {
        // PasswordAuthProvider (this file's whole posture, per the class javadoc) is enabled but
        // has no OAuth dance, so /auth/mode must report redirectFlow=false -- the signal the
        // Login/Signup views poll to decide whether to render a form or bounce to WorkOS.
        // firstRun is the second half of that answer: an account exists by the time this
        // asserts, so /login is a real destination and the view must not hand the visitor to
        // /signup. Same method-order reasoning as the degrade test above.
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("auth-mode@example.com", "a-good-password")))
                .andExpect(status().isOk());

        mvc.perform(get("/auth/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redirectFlow").value(false))
                .andExpect(jsonPath("$.data.firstRun").value(false));
    }
}
