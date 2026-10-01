// SPDX-License-Identifier: Apache-2.0
package ai.tessary.apidoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.tessary.testsupport.AuthEnforcedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The springdoc {@code /v3/api-docs} document, canonicalized (key-sorted, 2-space, {@code \n}), must byte-equal
 * {@code backend/contract/src/main/resources/openapi/tessary-api.json}. A wire-contract change fails {@code task
 * backend:check} until regenerated with {@code task contract:openapi} ({@code -Dtessary.openapi.regenerate=true});
 * the file self-seeds when absent.
 */
// This test's MockMvc adds no filters, and AuthFilter lets /v3/api-docs through regardless, so the auth-enforced
// context documents the intended posture; it does not itself prove the endpoint is served authenticated.
@AuthEnforcedContext
class OpenApiSpecDriftTest {

    /** {@code backend/contract/src/main/resources/openapi/tessary-api.json}, relative to the app module dir. */
    private static final Path SPEC =
            Path.of("..", "contract", "src", "main", "resources", "openapi", "tessary-api.json");

    private static final String REGENERATE_PROP = "tessary.openapi.regenerate";

    @Autowired
    WebApplicationContext wac;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void generatedSpecMatchesCheckedInContract() throws Exception {
        String live =
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String canonical = canonicalize(live);

        boolean regenerate = Boolean.getBoolean(REGENERATE_PROP);
        if (regenerate || !Files.exists(SPEC)) {
            Files.createDirectories(SPEC.getParent());
            Files.writeString(SPEC, canonical, StandardCharsets.UTF_8);
            return; // self-seed or regenerate: the write is the update
        }

        String checkedIn = Files.readString(SPEC, StandardCharsets.UTF_8);
        assertEquals(
                checkedIn,
                canonical,
                "The OpenAPI contract drifted from the checked-in spec. Regenerate it with `task contract:openapi` "
                        + "and commit backend/contract/src/main/resources/openapi/tessary-api.json.");
    }

    private String canonicalize(String json) throws Exception {
        return new OpenApiCanonicalizer(mapper).canonicalize(mapper.readTree(json));
    }
}
