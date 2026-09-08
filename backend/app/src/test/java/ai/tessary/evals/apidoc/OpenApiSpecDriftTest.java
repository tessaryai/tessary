// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.apidoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The OpenAPI contract drift guard: the springdoc-generated {@code /v3/api-docs} document
 * — canonicalized (recursively key-sorted, 2-space, {@code \n} line endings) — must byte-equal the
 * checked-in single source of truth at {@code backend/contract/src/main/resources/openapi/evals-api.json}.
 *
 * <p>From here, any controller/DTO change that shifts the wire contract fails {@code task backend:check}
 * until the spec is regenerated — the intentional coupling that keeps the checked-in spec honest across
 * P2/P4/P5 edits. Regenerate with {@code task contract:openapi} (runs this test with
 * {@code -Devals.openapi.regenerate=true}); the guard also self-seeds the file on first run when it is
 * absent.
 */
@SpringBootTest
class OpenApiSpecDriftTest {

    /** {@code backend/contract/src/main/resources/openapi/evals-api.json}, relative to the app module dir. */
    private static final Path SPEC = Path.of("..", "contract", "src", "main", "resources", "openapi", "evals-api.json");

    private static final String REGENERATE_PROP = "evals.openapi.regenerate";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        // Matches the app's normal (auth-enforced) posture rather than the suite's global
        // unauthenticated default -- say so directly rather than configuring a fake
        // external-provider key as an indirect toggle. See TestAuthDisabledInitializer's javadoc
        // (#852/#996). NOTE: this test's own MockMvc is built via webAppContextSetup(wac).build()
        // with no .addFilters(...), so AuthFilter is not actually in this test's filter chain
        // regardless of this property, and /v3/api-docs bypasses auth unconditionally in
        // AuthFilter anyway (crew review, #996) -- this override documents the intended posture,
        // it does not itself prove the endpoint is served authenticated.
        r.add("evals.auth.disabled", () -> "false");
    }

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
            return; // self-seed / regenerate mode — the write IS the update
        }

        String checkedIn = Files.readString(SPEC, StandardCharsets.UTF_8);
        assertEquals(
                checkedIn,
                canonical,
                "The OpenAPI contract drifted from the checked-in spec. Regenerate it with `task contract:openapi` "
                        + "and commit backend/contract/src/main/resources/openapi/evals-api.json.");
    }

    private String canonicalize(String json) throws Exception {
        return new OpenApiCanonicalizer(mapper).canonicalize(mapper.readTree(json));
    }
}
