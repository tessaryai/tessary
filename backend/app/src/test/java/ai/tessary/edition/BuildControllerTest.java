// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.testsupport.AuthEnforcedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/build} names the running build to any signed-in caller. The bug: the Organization page shows no
 * version, or one that is not the edition's, because the endpoint is missing or its wire key moved.
 */
@AuthEnforcedContext
record BuildControllerTest(MockMvc mvc) {

    @Test
    void aSignedInCallerReadsTheRunningVersion() throws Exception {
        String signup = new ObjectMapper()
                .writeValueAsString(Map.of("email", "build-reader@example.com", "password", "a-good-password"));
        Cookie session = Objects.requireNonNull(mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signup))
                .andReturn()
                .getResponse()
                .getCookie("tessary-session"));

        // A test classpath has no jar manifest, so the open edition reports AppVersion's fallback.
        mvc.perform(get("/api/build").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("dev"));
    }
}
