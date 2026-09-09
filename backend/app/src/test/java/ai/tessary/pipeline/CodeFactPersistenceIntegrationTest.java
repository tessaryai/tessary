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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The code-tracked facts survive a real round-trip through Postgres — parse is only half the contract,
 * and the columns are new in migration 0038.
 *
 * <p>Asserts against the raw column as well as the reconstituted record, because the two failure modes
 * that matter here are invisible through {@code load()} alone: a JSON value stored as the four-character
 * text {@code "null"} reads back as a perfectly good {@code NullNode}, and an empty list stored as the
 * text {@code "[]"} reads back as an empty list — both indistinguishable from the correct SQL NULL
 * unless the column itself is inspected. {@code SubstrateReadRepository} filters on {@code IS NOT NULL},
 * so that distinction is what decides whether a classifier sees a schema at all.
 */
@SpringBootTest
class CodeFactPersistenceIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
        // The bug this pins: Jackson binds `output_schema: null` to NullNode, so a `!= null` guard
        // stores the four-character string "null". That is not SQL NULL, so it never clears the
        // capture the contract says it clears — and callSiteOutputSchemas (IS NOT NULL) would keep
        // handing "null" to the Malformed Output classifier as a schema. networknt compiles "null"
        // into a permissive schema rather than rejecting it, so the detector's compile-failure escape
        // hatch never engages and every plain-text output falls through to the not_json branch.
        String pid = project("codefact-explicit-null");

        repo.replace(pid, pipeline(NullNode.getInstance(), List.of(), List.of()));

        assertNull(rawColumn(pid, "output_schema"), "an explicit null clears the column, as SQL NULL");
        assertNull(repo.load(pid).callSites().get(0).outputSchema());
    }

    @Test
    void anAbsentSchemaKeepsThePlatformsCaptureAcrossAWipeAndWrite() {
        String pid = project("codefact-absent-keeps");
        repo.replace(pid, pipeline(null, List.of(), List.of()));
        repo.setCallSiteOutputSchema(pid, "cs", "{\"type\":\"object\"}");

        repo.replace(pid, pipeline(null, List.of(), List.of())); // still silent on the fact

        assertNotNull(rawColumn(pid, "output_schema"), "silence carried the capture across the wipe");
    }

    @Test
    void absentToolsAndCapabilitiesLandAsSqlNullNotAnEmptyJsonArray() {
        // Migration 0038 documents NULL as "the bundle declares none". An empty `[]` in the column
        // would read back identically through load() while meaning something different to any future
        // consumer that checks for absence.
        String pid = project("codefact-empty-null");

        repo.replace(pid, pipeline(null, List.of(), List.of()));

        assertNull(rawColumn(pid, "tools_json"), "no tools is SQL NULL, not '[]'");
        String caps = jdbc.sql("SELECT capabilities_json FROM pipeline_meta WHERE project_id = :pid")
                .param("pid", pid)
                .query(String.class)
                .optional()
                .orElse(null);
        assertNull(caps, "no capabilities is SQL NULL, not '[]'");
    }

    @Test
    void aSilentBundleClearsToolsBecauseTheBundleIsTheirOnlyWriter() throws Exception {
        // The deliberate asymmetry with output_schema: `tools` has exactly one writer, so silence is
        // a statement ("no tools") rather than an abstention. Pinned so the asymmetry is a decision
        // someone has to re-argue rather than something a later edit quietly flips.
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
