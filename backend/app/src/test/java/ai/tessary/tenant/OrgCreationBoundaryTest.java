// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * This build's org-creation boundary: a dedicated controller test for
 * {@code OrganizationController}'s HTTP surface, driving the real signup -> bootstrap-org -> me flow
 * through {@code MockMvc}, container-backed, on the same {@code @DynamicPropertySource} shape
 * {@link AuthControllerTest} and {@code OpenApiSpecDriftTest} use.
 *
 * <p>Pins three things:
 * <ul>
 *   <li>a fresh signup ends with exactly one org, visible on {@code GET /auth/me} with no call to
 *       any other endpoint</li>
 *   <li>{@code POST /api/orgs} succeeds once (the bootstrap org) then 429s on a second attempt,
 *       under the default {@link OrgCreationLimitConfig} cap of 1</li>
 *   <li>{@code GET /api/me/orgs}, {@code POST /api/orgs/{slug}/archive}, and
 *       {@code POST /api/orgs/{slug}/transfer-ownership} 404 (route absent, not just forbidden)
 *       in this build</li>
 * </ul>
 */
@SpringBootTest
class OrgCreationBoundaryTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.auth.cookie-password", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("workos.api-key", () -> "");
        r.add("workos.client-id", () -> "");
        // Enable auth so this test exercises the real, authenticated request path -- the suite's
        // global default (TestAuthDisabledInitializer) is unauthenticated, and this test's whole
        // point is the cookie-session boundary, so it must say so directly rather than relying on
        // an indirect toggle. See AuthControllerTest/ImportControllerTest for the same override.
        r.add("tessary.auth.disabled", () -> "false");
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthFilter authFilter;

    @Autowired
    TenantService tenants;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Without addFilters(authFilter) the security filter never runs at all under MockMvc, so
        // every request reaches the controller with no TenantContext -- which happened to read as
        // "route absent" (404, no filter to hard-401 first) for the two GET/POST calls this test
        // originally exercised without a real filter chain, silently proving nothing about auth.
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    /** Signs a fresh account up and returns the session cookie the frontend would carry forward. */
    private Cookie signUp(String email) throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        // MockHttpServletResponse parses Set-Cookie itself (attributes, encoding) rather than this
        // test re-deriving that by splitting the raw header value.
        Cookie session = res.getCookie("tessary-session");
        assertNotNull(session, "signup must set the tessary-session cookie");
        return session;
    }

    @Test
    void freshSignupHasExactlyOneOrgAndNeedsNoMovedEndpoint() throws Exception {
        Cookie session = signUp("fresh-org-862@example.com");

        // Signup itself bootstraps the default org (AuthController's signup/callback tail calls
        // TenantService.ensureDefaultOrg) -- /auth/me is the only read the frontend needs;
        // Onboarding/App/Link/Sidebar do not call GET /api/me/orgs.
        mvc.perform(get("/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgs.length()").value(1));
    }

    @Test
    void bootstrapOrgSucceedsOnceThenCapsAtOpenDefault() throws Exception {
        Cookie session = signUp("cap-862@example.com");

        // Signup already bootstrapped one org (see above), so a further createOrg call is already
        // over the default cap of 1 -- assert the 429 shape API consumers rely on.
        mvc.perform(post("/api/orgs")
                        .cookie(session)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "second-org"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void movedMultiOrgEndpointsAreAbsentUnderTheOpenProfile() throws Exception {
        Cookie session = signUp("moved-862@example.com");

        mvc.perform(get("/api/me/orgs").cookie(session)).andExpect(status().isNotFound());

        // archive/transfer-ownership need a real orgSlug in the path to reach routing at all, but
        // the route itself is what's under test here -- any slug 404s the same way a real one
        // would if the mapping doesn't exist, which is exactly the "route absent" claim.
        mvc.perform(post("/api/orgs/whatever/archive").cookie(session).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orgs/whatever/transfer-ownership")
                        .cookie(session)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("to_user_id", "someone"))))
                .andExpect(status().isNotFound());
        // Deliberately not asserting DELETE /api/orgs/{orgSlug} here: the path pattern itself is
        // still mapped open-side (GET getOrg, PATCH updateOrg), so an unmapped DELETE on it 405s,
        // not 404s -- a different signal than the three fully-departed routes above.
    }

    /**
     * The owned-org cap is a check-then-insert, so two concurrent creates by one user could both
     * read the count before either inserted and exceed the cap. This drives two racers at the
     * service layer (each call is its own transaction) against one free slot and requires exactly
     * one winner: the per-owner advisory lock inside {@code TenantService.bootstrapOrg} is what
     * makes that deterministic rather than merely likely.
     */
    @Test
    void concurrentCreatesCannotExceedTheOwnedOrgCap() throws Exception {
        String email = "race-" + Ids.ulid().toLowerCase(Locale.ROOT) + "@example.com";
        MockHttpServletResponse res = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("email", email, "password", "correct-horse-battery-staple"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        JsonNode body = mapper.readTree(res.getContentAsString());
        String userId = (body.has("data") ? body.get("data") : body).get("id").asText();

        // Signup already bootstrapped ONE org; a cap of 2 leaves exactly one slot for two racers.
        int cap = 2;
        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            String slug = tenants.uniqueSlug("race-" + i + "-" + Ids.ulid().toLowerCase(Locale.ROOT));
            Organization o = new Organization(
                    Ids.ulid(), null, slug, "Race " + i, Instant.now().toString(), null, null);
            results.add(pool.submit(() -> {
                go.await();
                try {
                    return tenants.bootstrapOrg(o, userId, cap);
                } catch (ResponseStatusException e) {
                    return e;
                }
            }));
        }
        go.countDown();
        int created = 0;
        int rejected = 0;
        try {
            for (Future<Object> f : results) {
                Object r = f.get(30, TimeUnit.SECONDS);
                if (r instanceof Organization) {
                    created++;
                } else if (r instanceof ResponseStatusException e
                        && e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    rejected++;
                } else {
                    throw new AssertionError("unexpected racer outcome: " + r);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, created, "exactly one racer may take the last slot");
        assertEquals(1, rejected, "the other must be told the cap is reached, not handed a second org");
    }
}
