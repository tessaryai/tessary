// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.testsupport.AuthEnforcedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The staff-only ingest health surface over HTTP: {@code /actuator/health/ingest} answers a
 * platform-staff session with the spool's numbers, stays closed to an anonymous caller, and the
 * public top-level document still renders no component detail.
 */
@AuthEnforcedContext
record IngestHealthGroupTest(MockMvc mvc) {

    private static final String STAFF = "staff-984@example.com";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theIngestGroupRendersTheSpoolForStaffAndNobodyElse() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/ingest")).andExpect(status().isUnauthorized());

        Cookie session = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", STAFF, "password", "a-good-password"))))
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
