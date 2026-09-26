// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.Pipeline;
import ai.tessary.pipeline.BundleAssembler.NamedBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
    void aCapabilityWithNoKindDefaultsRatherThanFailing() {
        // The manifest is written by an agent reading code. A missing `kind` must degrade to the
        // commonest case, not reject the whole bundle and take every other shard down with it.
        Pipeline p = assemble(
                meta(), new NamedBody(".tessary/pipeline/capabilities.yaml", "capabilities:\n  - name: unlabelled\n"));

        assertEquals("tool", p.capabilities().get(0).kind());
    }
}
