// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.model.Pipeline;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PipelineError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.BundleAssembler.AssembledBundle;
import ai.tessary.pipeline.BundleAssembler.NamedBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The sharded bundle parser: each optional pipeline shard lands on its own field, and a bundle that is
 * wrong in a way the plugin can fix is refused with a code naming what to fix rather than half-imported.
 */
class BundleAssemblerTest {

    private final BundleAssembler assembler =
            new BundleAssembler(new ObjectMapper(new YAMLFactory()).findAndRegisterModules());

    private static final NamedBody META = new NamedBody(".tessary/pipeline/meta.yaml", "version: 0.8.0\n");

    private static NamedBody file(String path, String body) {
        return new NamedBody(path, body);
    }

    @Test
    void everyPipelineShardLandsOnItsOwnField() {
        AssembledBundle bundle = assembler.assemble(List.of(
                file(".tessary/pipeline/meta.yaml", """
                        version: 0.8.0
                        product_hint: summariser
                        commit_sha: ""
                        source_commit_sha: abc123
                        repo: {owner: acme, name: web}
                        runtime: {judge_model: claude-sonnet-5, max_concurrency: 4}
                        progress: {sites_completed: 1, sites_total: 3}
                        """),
                file(".tessary/pipeline/packs.yaml", "packs:\n  - {id: quality, name: Quality, version: 1.0.0}\n"),
                file(".tessary/pipeline/product_profile.yaml", "product_profile: {domain: summarisation}\n"),
                file(".tessary/pipeline/invariants.yaml", """
                        implicit_invariants:
                          - {name: cites_sources, description: every claim cites, confidence: high}
                        invariant_coverage:
                          - {invariant: cites_sources}
                        """),
                file(".tessary/pipeline/chains.yaml", "chains:\n  - {id: ch1, name: summarise then check}\n"),
                file(".tessary/pipeline/taxonomy.yaml", "taxonomy:\n  - {id: tax::faith, name: Faithfulness}\n"),
                file(".tessary/pipeline/capabilities.yaml", "capabilities:\n  - {name: search, kind: tool}\n"),
                file(".tessary/pipeline/notes.yaml", "anything: goes\n")));

        Pipeline p = bundle.pipeline();
        assertEquals("abc123", bundle.commitSha(), "a blank commit_sha falls through to source_commit_sha");
        assertEquals("acme", bundle.repoOwner());
        assertEquals("web", bundle.repoName());
        assertEquals("summariser", p.productHint());
        assertEquals("quality", p.packs().get(0).id());
        assertEquals("summarisation", p.productProfile().domain());
        assertEquals("cites_sources", p.implicitInvariants().get(0).name());
        assertEquals("cites_sources", p.invariantCoverage().get(0).invariant());
        assertEquals("ch1", p.chains().get(0).id());
        assertEquals("tax::faith", p.taxonomy().get(0).id());
        assertEquals("search", p.capabilities().get(0).name());
        assertEquals("claude-sonnet-5", p.runtime().judgeModel());
        assertEquals(3, p.progress().sitesTotal());
    }

    static Stream<Arguments> refusedBundles() {
        return Stream.of(
                duplicate("packs"),
                duplicate("product_profile"),
                duplicate("invariants"),
                duplicate("chains"),
                duplicate("taxonomy"),
                duplicate("capabilities"),
                Arguments.of(
                        "a call site with no id",
                        List.of(META, file(".tessary/pipeline/call_sites/cs.yaml", "intent: summarise\n")),
                        PipelineError.MISSING_ID,
                        new Object[] {"pipeline/call_sites/cs.yaml"}),
                Arguments.of(
                        "a list shard holding a map",
                        List.of(META, file(".tessary/pipeline/packs.yaml", "packs: {id: quality}\n")),
                        PipelineError.EXPECTED_YAML_LIST,
                        new Object[] {"OBJECT"}),
                Arguments.of(
                        "a shard that is not YAML",
                        List.of(META, file(".tessary/pipeline/chains.yaml", "chains: [unclosed\n")),
                        PipelineError.MALFORMED_YAML,
                        new Object[] {"pipeline/chains.yaml"}),
                Arguments.of(
                        "shards but no meta",
                        List.of(file(".tessary/pipeline/call_sites/cs.yaml", "id: cs\n")),
                        PipelineError.MISSING_META,
                        new Object[] {}),
                Arguments.of(
                        "a runtime section of the wrong shape",
                        List.of(file(".tessary/pipeline/meta.yaml", "runtime: {max_concurrency: lots}\n")),
                        PipelineError.MALFORMED_META_FIELD,
                        new Object[] {"runtime"}),
                Arguments.of(
                        "a progress section of the wrong shape",
                        List.of(file(".tessary/pipeline/meta.yaml", "progress: {sites_total: many}\n")),
                        PipelineError.MALFORMED_META_FIELD,
                        new Object[] {"progress"}));
    }

    /** The same shard twice (two folders both rooted at a pipeline/): which one wins is undefined, so neither does. */
    private static Arguments duplicate(String shard) {
        String body = "note: the second copy\n";
        return Arguments.of(
                "two " + shard + " shards",
                List.of(META, file("a/pipeline/" + shard + ".yaml", body), file("b/pipeline/" + shard + ".yml", body)),
                PipelineError.DUPLICATE_FILE,
                new Object[] {"pipeline/" + shard + ".yaml"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedBundles")
    void aBundleThePluginMustFixIsRefusedWithTheCodeNamingTheFix(
            String why, List<NamedBody> files, ErrorCode expected, Object[] args) {
        TessaryException e = assertThrows(TessaryException.class, () -> assembler.assemble(files), why);

        assertEquals(expected, e.error(), why);
        assertEquals(expected.render(args), e.getMessage(), why);
    }
}
