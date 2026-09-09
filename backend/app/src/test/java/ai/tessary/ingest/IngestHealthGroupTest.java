// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
 * The staff-only ingest health surface over HTTP: {@code /actuator/health/ingest} answers a
 * platform-staff session with the spool's numbers, stays closed to an anonymous caller, and the
 * public top-level document still renders no component detail.
 */
@SpringBootTest
class IngestHealthGroupTest {

    private static final String STAFF = "staff-984@example.com";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("tessary.auth.cookie-password", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("workos.api-key", () -> "");
        r.add("workos.client-id", () -> "");
        r.add("tessary.auth.disabled", () -> "false");
        r.add("tessary.platform.staff-emails", () -> STAFF);
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthFilter authFilter;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    @Test
    void theIngestGroupRendersTheSpoolForStaffAndNobodyElse() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/ingest")).andExpect(status().isUnauthorized());

        Cookie session = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", STAFF, "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("tessary-session");
        assertNotNull(session);
        mvc.perform(get("/actuator/health/ingest").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.ingestSpool.details.spool").value("memory"))
                .andExpect(jsonPath("$.components.ingestSpool.details.drainerAlive")
                        .value(true))
                .andExpect(
                        jsonPath("$.components.ingestSpool.details.oldestAgeMs").isNumber())
                .andExpect(jsonPath("$.components.ingestSpool.details.deadLettered")
                        .isNumber());
    }
}
