// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.model.CallSite;
import ai.tessary.evals.model.Pipeline;
import ai.tessary.evals.pipeline.BundleAssembler.NamedBody;
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
 * Contract parity for the code-tracked facts, read straight out of the vendored plugin contract.
 *
 * <p>The two repos ship independently and the {@code .tessary/} bundle is the only thing between
 * them. {@link BundleAssembler} drops unknown YAML keys by design so a newer plugin can't break
 * import — which is also exactly what makes a misspelled key invisible from both sides: a field the
 * plugin writes and the platform silently ignores looks, from either end, like a field nobody wrote.
 *
 * <p>This test parses the {@code parity-anchor} blocks out of {@code contract/output_format.md} — the
 * vendored copy of the plugin's own documentation — and asserts every documented key binds to a field
 * the platform actually consumes. Reading the document rather than holding a copy of it is the point:
 * a hand-copied fixture is one more thing that can drift, and its "copied verbatim" comment is a
 * promise no one checks. Here the promise is the mechanism.
 *
 * <p>The plugins repo is public and deliberately runs no PR CI, so the plugin side cannot enforce this
 * itself. Enforcement lives here, in the private repo, at vendoring time — see
 * {@code scripts/check-vendored-plugin.sh} for the companion freshness check that catches a vendored
 * copy falling behind the plugin's {@code main}.
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

    /**
     * The repo-root {@code contract/} directory. Walks up from the working directory rather than
     * hardcoding a relative depth, so this resolves the same whether the module is run from the repo
     * root, from {@code backend/}, or from {@code backend/app/}.
     */
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

    @Test
    void theAnchorIsRealYamlCarryingTheFactsItClaimsTo() throws IOException {
        // Guards the failure mode this whole approach could hide: if the anchor were emptied, renamed
        // into placeholders, or reduced to a stub, every assertion above would still pass vacuously on
        // a call site that simply declares nothing. The anchor has to actually exercise the contract.
        String callSite = anchor("call_site");
        assertTrue(callSite.contains("output_schema:"), "the call-site anchor must exercise output_schema");
        assertTrue(callSite.contains("tools:"), "the call-site anchor must exercise tools");
        assertFalse(callSite.contains("<"), "an anchor is a worked example, not a placeholder template");
        assertFalse(anchor("capabilities").contains("<"), "likewise for the capabilities anchor");
    }
}
