// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.testsupport.AuthEnforcedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /auth/signup} and {@code POST /auth/login} driven through the real
 * {@link PasswordAuthProvider} bean (no WorkOS configured -- the open-edition default posture),
 * plus the {@code GET /auth/login} degrade-to-redirect guard. Container-free on the
 * {@code OpenApiSpecDriftTest} precedent for the property setup; the real Postgres database
 * each {@code @SpringBootTest} context gets (via {@code TestcontainersPostgresInitializer}) is what
 * makes an end-to-end signup/login round trip meaningful here, unlike {@link PasswordAuthProviderTest}'s
 * mocked-repository unit coverage.
 */
@AuthEnforcedContext
record AuthControllerTest(MockMvc mvc, AuthProvider provider) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String body(String email, String password) throws Exception {
        return MAPPER.writeValueAsString(Map.of("email", email, "password", password));
    }

    @Test
    void loginHappyPathAfterSignup() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("login-happy@example.com", "a-good-password")))
                .andExpect(status().isOk());

        Cookie session = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("login-happy@example.com", "a-good-password")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("tessary-session");
        assertNotNull(session, "a successful login sets the session cookie");

        // The cookie is the sign-in: it must resolve to the account that just logged in.
        mvc.perform(get("/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("login-happy@example.com"));
    }

    /** After a signup, a login with the wrong password is 401 and a second signup for the same email is 409. */
    @ParameterizedTest
    @CsvSource({
        "wrong-pw@example.com, /auth/login, not-the-password, 401",
        "dup@example.com, /auth/signup, a-different-password, 409"
    })
    void aWrongPasswordOrATakenEmailIsRejected(String email, String then, String password, int expected)
            throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "a-good-password")))
                .andExpect(status().isOk());

        mvc.perform(post(then).contentType(MediaType.APPLICATION_JSON).content(body(email, password)))
                .andExpect(status().is(expected));
    }

    @Test
    void getLoginDegradesToRedirectRatherThan500() throws Exception {
        // With PasswordAuthProvider active (supportsRedirectFlow()=false), the
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
            assertNotNull(location);
            assertTrue(
                    location.endsWith("/login"),
                    "expected the redirect to land on the frontend's own /login screen, got: " + location);
        });
    }

    @Test
    void getAuthModeReportsPasswordPosture() throws Exception {
        // PasswordAuthProvider (this file's whole posture, per the class javadoc) is active but
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
