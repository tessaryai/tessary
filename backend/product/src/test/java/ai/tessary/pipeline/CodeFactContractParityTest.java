// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.CallSite;
import ai.tessary.model.Pipeline;
import ai.tessary.pipeline.BundleAssembler.NamedBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Parity for the code-tracked facts, read from the vendored plugin contract. {@link BundleAssembler} drops unknown
 * YAML keys so a newer plugin cannot break import, which also hides a misspelled key from both sides.
 *
 * <p>Parses the {@code parity-anchor} blocks in {@code contract/output_format.md} and asserts every documented key
 * binds to a consumed field. Reading the document, not a copy, is the point. The public plugins repo runs no PR CI,
 * so enforcement lives here; {@code scripts/check-vendored-plugin.sh} checks the copy is current.
 */
class CodeFactContractParityTest {

    private final BundleAssembler assembler =
            new BundleAssembler(new ObjectMapper(new YAMLFactory()).findAndRegisterModules());

    /** {@code <!-- parity-anchor: NAME begin --> ```yaml … ``` <!-- parity-anchor: NAME end -->} */
    private static String anchor(String name) throws IOException {
        String doc = Files.readString(vendoredContract());
        Pattern p = Pattern.compile(
                "<!--\\s*parity-anchor:\\s*" + Pattern.quote(name) + "\\s*begin\\s*-->\\s*```yaml\\n"
                        + "(.*?)```\\s*<!--\\s*parity-anchor:\\s*" + Pattern.quote(name) + "\\s*end\\s*-->",
                Pattern.DOTALL);
        Matcher m = p.matcher(doc);
        assertTrue(
                m.find(),
                "parity anchor '" + name + "' is missing from the vendored contract. Either the plugin "
                        + "removed it (in which case this platform-side consumer needs the same decision) or "
                        + "the vendored copy is stale — re-run scripts/sync-evals-contract.sh.");
        return m.group(1);
    }

    /** The repo-root {@code contract/} directory, found by walking up so it resolves from any module directory. */
    private static Path vendoredContract() {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contract/output_format.md");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "contract/output_format.md not found above " + Paths.get("").toAbsolutePath());
    }

    private Pipeline assembleFromContract() throws IOException {
        return assembler
                .assemble(List.of(
                        new NamedBody(".tessary/pipeline/meta.yaml", "version: 0.15.0\n"),
                        new NamedBody(".tessary/pipeline/call_sites/support.answer.yaml", anchor("call_site")),
                        new NamedBody(".tessary/pipeline/capabilities.yaml", anchor("capabilities"))))
                .pipeline();
    }

    @Test
    void everyDocumentedCallSiteKeyIsConsumed() throws IOException {
        CallSite cs = assembleFromContract().callSites().get(0);

        var schema = cs.outputSchema();
        assertNotNull(schema, "output_schema: the documented key must bind, not drop silently");
        assertEquals(
                "string", schema.path("properties").path("answer").path("type").asText());

        List<CallSite.ToolSpec> tools = cs.tools();
        assertEquals(2, tools.size(), "tools: both documented entries bind");
        assertEquals("search_docs", tools.get(0).name());
        assertEquals("full-text search over the handbook", tools.get(0).description());
        assertEquals("src/tools/search.py:41", tools.get(0).source(), "source: snake_case key as documented");
        assertNotNull(tools.get(0).inputSchema(), "input_schema: snake_case key as documented");
        assertEquals(
                "string",
                java.util.Objects.requireNonNull(tools.get(0).inputSchema())
                        .path("properties")
                        .path("query")
                        .path("type")
                        .asText());
    }

    @Test
    void everyDocumentedCapabilityKeyIsConsumed() throws IOException {
        var caps = assembleFromContract().capabilities();

        assertEquals(2, caps.size());
        assertEquals("search_docs", caps.get(0).name());
        assertEquals("tool", caps.get(0).kind());
        assertEquals("full-text search", caps.get(0).description());
        assertEquals("src/tools/search.py:41", caps.get(0).source());
        assertEquals(
                List.of("support.answer"), caps.get(0).callSiteIds(), "call_site_ids: snake_case key as documented");
        assertEquals("skill", caps.get(1).kind());
    }
}
