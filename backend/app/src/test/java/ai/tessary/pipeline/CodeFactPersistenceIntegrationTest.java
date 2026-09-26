// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.CallSite;
import ai.tessary.model.Capability;
import ai.tessary.model.Pipeline;
import ai.tessary.model.ProductProfile;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Code-tracked facts round-trip through Postgres (migration 0038). Asserted on the raw column too: JSON {@code
 * "null"} text and {@code "[]"} read back through {@code load()} indistinguishable from SQL NULL, and {@code
 * SubstrateReadRepository} filters on {@code IS NOT NULL}, which decides whether a classifier sees a schema.
 */
@SpringBootTest
class CodeFactPersistenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    PipelineRepository repo;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private String project(String name) {
        return TenantFixture.bootstrap(tenants, name).project().id();
    }

    private @Nullable String rawColumn(String projectId, String column) {
        return jdbc.sql("SELECT " + column + " FROM call_site WHERE project_id = :pid AND id = 'cs'")
                .param("pid", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void schemaAndToolsRoundTripThroughPostgres() throws Exception {
        String pid = project("codefact-roundtrip");
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}");
        repo.replace(
                pid,
                pipeline(
                        schema,
                        List.of(new CallSite.ToolSpec(
                                "search", "full-text search", MAPPER.readTree("{\"type\":\"object\"}"), "s.py:1")),
                        List.of(new Capability("search", "tool", "full-text", "s.py:1", List.of("cs")))));

        CallSite cs = repo.load(pid).callSites().get(0);
        assertNotNull(cs.outputSchema());
        assertEquals(
                "string",
                cs.outputSchema().path("properties").path("answer").path("type").asText());
        assertEquals(1, cs.tools().size());
        assertEquals("search", cs.tools().get(0).name());
        assertNotNull(cs.tools().get(0).inputSchema());
        assertEquals(List.of("cs"), repo.load(pid).capabilities().get(0).callSiteIds());
    }

    @Test
    void anExplicitNullSchemaPersistsAsSqlNullNotTheTextNull() {
        // Jackson binds `output_schema: null` to NullNode, so a `!= null` guard stored the string "null": the capture
        // never cleared and callSiteOutputSchemas kept handing it to Malformed Output, where networknt compiles it
        // permissively and every plain-text output lands in not_json.
        String pid = project("codefact-explicit-null");

        repo.replace(pid, pipeline(NullNode.getInstance(), List.of(), List.of()));

        assertNull(rawColumn(pid, "output_schema"), "an explicit null clears the column, as SQL NULL");
        assertNull(repo.load(pid).callSites().get(0).outputSchema());
    }

    @Test
    void anAbsentSchemaKeepsTheStoredSchemaAcrossAWipeAndWrite() throws Exception {
        String pid = project("codefact-absent-keeps");
        repo.replace(pid, pipeline(MAPPER.readTree("{\"type\":\"object\"}"), List.of(), List.of()));

        repo.replace(pid, pipeline(null, List.of(), List.of())); // silent on the fact

        assertNotNull(rawColumn(pid, "output_schema"), "silence carried the capture across the wipe");
    }

    @Test
    void aSilentBundleClearsToolsBecauseTheBundleIsTheirOnlyWriter() throws Exception {
        // `tools` has one writer, so silence means "no tools", unlike output_schema. Pinned so flipping it is a
        // decision.
        String pid = project("codefact-tools-cleared");
        repo.replace(pid, pipeline(null, List.of(new CallSite.ToolSpec("search", null, null, null)), List.of()));
        assertNotNull(rawColumn(pid, "tools_json"), "setup: tools stored");

        repo.replace(pid, pipeline(null, List.of(), List.of()));

        assertNull(rawColumn(pid, "tools_json"), "a bundle that stops declaring tools clears them");
        assertTrue(repo.load(pid).callSites().get(0).tools().isEmpty());
    }

    private static Pipeline pipeline(
            @Nullable JsonNode outputSchema, List<CallSite.ToolSpec> tools, List<Capability> capabilities) {
        return new Pipeline(
                "0.15.0",
                "v1",
                List.of(),
                new ProductProfile("docs", List.of(), null, List.of(), List.of(), List.of(), List.of()),
                List.of(),
                List.of(),
                null,
                List.of(new CallSite(
                        "cs",
                        "answer",
                        "sdk",
                        "anthropic",
                        "claude",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "rag_answer",
                        "high",
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(),
                        outputSchema,
                        tools)),
                List.of(),
                List.of(),
                List.of(),
                null,
                capabilities);
    }
}
