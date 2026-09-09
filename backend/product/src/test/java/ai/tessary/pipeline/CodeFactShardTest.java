// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.CallSite;
import ai.tessary.model.Capability;
import ai.tessary.model.Pipeline;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.BundleAssembler.NamedBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bundle's <b>code-tracked facts</b> — the declarations that describe the product's source rather
 * than its traffic, and which the platform therefore keeps in sync as the code changes: a call site's
 * declared {@code output_schema} and {@code tools}, and the product-level capability manifest.
 *
 * <p>All three are optional and inert until a writer exists. What these tests pin is that a bundle
 * which omits them parses exactly as before (the whole point of shipping the shape first), and that a
 * bundle which carries them round-trips without the arbitrary-shape parts being flattened or dropped.
 */
class CodeFactShardTest {

    private final BundleAssembler assembler =
            new BundleAssembler(new ObjectMapper(new YAMLFactory()).findAndRegisterModules());

    private static final String META = "version: 0.3.0\n";

    private Pipeline assemble(NamedBody... files) {
        return assembler.assemble(java.util.Arrays.asList(files)).pipeline();
    }

    private static NamedBody meta() {
        return new NamedBody(".tessary/pipeline/meta.yaml", META);
    }

    @Test
    void aBundleWithoutCodeFactsParsesExactlyAsBefore() {
        Pipeline p = assemble(
                meta(), new NamedBody(".tessary/pipeline/call_sites/support.answer.yaml", "id: support.answer\n"));

        CallSite cs = p.callSites().get(0);
        assertNull(cs.outputSchema(), "no declaration is not an empty declaration");
        assertEquals(List.of(), cs.tools(), "absent tools normalize to empty, never null");
        assertEquals(List.of(), p.capabilities(), "a bundle with no capabilities shard declares none");
    }

    @Test
    void anOutputSchemaSurvivesAsAnArbitraryTree() {
        Pipeline p = assemble(meta(), new NamedBody(".tessary/pipeline/call_sites/support.answer.yaml", """
                        id: support.answer
                        output_schema:
                          type: object
                          properties:
                            answer:
                              type: string
                            citations:
                              type: array
                              items:
                                type: string
                          required: [answer]
                        """));

        var schema = p.callSites().get(0).outputSchema();
        assertNotNull(schema);
        // Nested structure must survive intact — the Malformed Output built-in compiles this as a
        // JSON Schema, so a flattened or stringified version is worthless to it.
        assertEquals(
                "string",
                schema.path("properties")
                        .path("citations")
                        .path("items")
                        .path("type")
                        .asText());
        assertEquals("answer", schema.path("required").get(0).asText());
    }

    @Test
    void anExplicitNullSchemaIsDistinctFromAnAbsentOne() {
        // The contract makes these mean opposite things: absent = "this bundle does not carry the
        // fact" (keep what the platform captured), explicit null = "the code declares no structured
        // output" (clear the capture). Jackson binds YAML `null` on a JsonNode property to NullNode,
        // NOT to Java null — so a `!= null` check sees the two as identical and the assertion below is
        // the only thing standing between the contract and a silently-ignored declaration.
        Pipeline explicitNull = assemble(
                meta(),
                new NamedBody(
                        ".tessary/pipeline/call_sites/support.answer.yaml",
                        "id: support.answer\noutput_schema: null\n"));
        Pipeline absent = assemble(
                meta(), new NamedBody(".tessary/pipeline/call_sites/support.answer.yaml", "id: support.answer\n"));

        var declared = explicitNull.callSites().get(0).outputSchema();
        assertNull(absent.callSites().get(0).outputSchema(), "absent stays Java null");
        assertTrue(
                declared == null || declared.isNull(),
                "an explicit null must be distinguishable from absent, or 'the code declares none' "
                        + "cannot be expressed at all — got " + declared);
    }

    @Test
    void toolSchemasCarryTheirArgumentContract() {
        Pipeline p = assemble(meta(), new NamedBody(".tessary/pipeline/call_sites/agent.step.yaml", """
                        id: agent.step
                        tools:
                          - name: search_docs
                            description: full-text search over the handbook
                            source: src/agent/tools/search.py:41
                            input_schema:
                              type: object
                              properties:
                                query: {type: string}
                              required: [query]
                          - name: escalate
                        """));

        List<CallSite.ToolSpec> tools = p.callSites().get(0).tools();
        assertEquals(2, tools.size());
        assertEquals("search_docs", tools.get(0).name());
        assertEquals("src/agent/tools/search.py:41", tools.get(0).source());
        assertEquals(
                "string",
                java.util.Objects.requireNonNull(tools.get(0).inputSchema())
                        .path("properties")
                        .path("query")
                        .path("type")
                        .asText());
        // A tool declared by name alone is legal — a manifest that can only express fully-specified
        // tools would silently omit the ones we know least about, which are the interesting ones.
        assertEquals("escalate", tools.get(1).name());
        assertNull(tools.get(1).inputSchema());
    }

    @Test
    void theCapabilityManifestParses() {
        Pipeline p = assemble(meta(), new NamedBody(".tessary/pipeline/capabilities.yaml", """
                        capabilities:
                          - name: search_docs
                            kind: tool
                            description: full-text search
                            source: src/agent/tools/search.py:41
                            call_site_ids: [agent.step]
                          - name: refund-policy
                            kind: skill
                          - name: github
                            kind: mcp_server
                        """));

        List<Capability> caps = p.capabilities();
        assertEquals(3, caps.size());
        assertEquals(List.of("agent.step"), caps.get(0).callSiteIds());
        assertEquals("skill", caps.get(1).kind());
        assertEquals(List.of(), caps.get(1).callSiteIds(), "an unbound capability is product-wide, not broken");
        assertEquals("mcp_server", caps.get(2).kind());
    }

    @Test
    void aCapabilityWithNoKindDefaultsRatherThanFailing() {
        // The manifest is written by an agent reading code. A missing `kind` must degrade to the
        // commonest case, not reject the whole bundle and take every other shard down with it.
        Pipeline p = assemble(
                meta(), new NamedBody(".tessary/pipeline/capabilities.yaml", "capabilities:\n  - name: unlabelled\n"));

        assertEquals("tool", p.capabilities().get(0).kind());
    }

    @Test
    void twoCapabilityShardsAreARejectedBundle() {
        // Same duplicate-file discipline as every other singleton shard: two manifests means one of
        // them is silently losing, and the bundle is the source of truth.
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> assemble(
                        meta(),
                        new NamedBody(".tessary/pipeline/capabilities.yaml", "capabilities: []\n"),
                        new NamedBody("pipeline/capabilities.yml", "capabilities: []\n")));
        String message = String.valueOf(e.getMessage());
        assertTrue(message.contains("capabilities") || message.contains("DUPLICATE"), message);
    }
}
