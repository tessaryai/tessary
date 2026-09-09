// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.CallSite;
import ai.tessary.model.Chain;
import ai.tessary.model.Constraint;
import ai.tessary.model.FailureMode;
import ai.tessary.model.ImplicitInvariant;
import ai.tessary.model.Pipeline;
import ai.tessary.model.ProductProfile;
import ai.tessary.model.Progress;
import ai.tessary.model.TaxonomyNode;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Round-trip every column on the pipeline tables. The bug this guards against:
 * adding a field to {@link CallSite} / {@link FailureMode} / etc. without updating
 * the corresponding column or mapper, which silently drops data on import.
 */
@SpringBootTest
class PipelineRepositoryTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    PipelineRepository repo;

    @Autowired
    TenantService tenants;

    @Test
    void replaceAndLoad_preservesEveryField() {
        var fix = TenantFixture.bootstrap(tenants, "pipe-rt");

        Pipeline p = buildSamplePipeline();
        repo.replace(fix.project().id(), p);

        Pipeline back = repo.load(fix.project().id());
        assertEquals(p.version(), back.version());
        assertEquals(p.productHint(), back.productHint());
        assertEquals(p.productProfile().domain(), back.productProfile().domain());
        assertEquals(p.implicitInvariants().size(), back.implicitInvariants().size());
        assertEquals(p.taxonomy().size(), back.taxonomy().size());

        assertEquals(p.callSites().size(), back.callSites().size());
        CallSite cs = back.callSites().get(0);
        assertEquals("cs_summarize", cs.id());
        assertEquals("summarise the doc", cs.intent());
        assertEquals("openai", cs.provider());
        assertEquals("gpt-4", cs.model());
        assertEquals("you are a helpful summariser", cs.systemPrompt());
        assertEquals("the literal prompt template", cs.promptText());
        assertEquals("def summarize():\n    ...", cs.surroundingCode());
        assertEquals("src/app/summarize.py", cs.fileHint());
        assertEquals(Integer.valueOf(42), cs.lineHint());
        assertEquals("summarize", cs.shape());
        assertEquals("cli_agent", cs.invocation());
        assertEquals("high", cs.shapeConfidence());
        assertEquals(Integer.valueOf(12), cs.sampleCount());
        assertEquals(2, cs.constraints().size());
        assertEquals("schema", cs.constraints().get(0).kind());

        assertEquals(1, back.chains().size());
        Chain ch = back.chains().get(0);
        assertEquals(List.of("cs_summarize", "cs_followup"), ch.callSiteIds());

        assertEquals(1, back.failureModes().size());
        FailureMode fm = back.failureModes().get(0);
        assertEquals("cs_summarize::hallucinates_facts", fm.id());
        assertEquals("high", fm.severity());
        assertFalse(fm.graderDeferred());
        assertEquals("cs_summarize::hallucinates_facts::grader", fm.graderId());

        assertNotNull(back.progress());
        assertEquals(1, back.progress().sitesCompleted());
        assertEquals(1, back.progress().sitesTotal());
        assertEquals(0, back.progress().deferredFailureCount());
    }

    @Test
    void load_returnsEmptyWhenNothingPersisted() {
        var fix = TenantFixture.bootstrap(tenants, "pipe-empty");
        Pipeline empty = repo.load(fix.project().id());
        assertNotNull(empty);
        assertTrue(empty.callSites().isEmpty());
        assertTrue(empty.failureModes().isEmpty());
        assertFalse(repo.exists(fix.project().id()));
    }

    @Test
    void replaceIsAtomic_overwritesPreviousImport() {
        var fix = TenantFixture.bootstrap(tenants, "pipe-replace");

        Pipeline first = buildSamplePipeline();
        repo.replace(fix.project().id(), first);
        assertEquals(1, repo.load(fix.project().id()).callSites().size());

        // Re-import with a different pipeline → must replace, not accumulate.
        Pipeline second = new Pipeline(
                "0.3.0",
                "v2",
                List.of(), // packs
                null, // productProfile
                List.of(), // implicitInvariants
                List.of(), // invariantCoverage
                null, // runtime
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null // progress
                ,
                List.of());
        repo.replace(fix.project().id(), second);

        Pipeline back = repo.load(fix.project().id());
        assertEquals("v2", back.productHint());
        assertTrue(back.callSites().isEmpty());
        assertTrue(back.failureModes().isEmpty());
    }

    @Test
    void replaceIsProjectScoped_doesNotLeakAcrossProjects() {
        var a = TenantFixture.bootstrap(tenants, "pipe-iso-a");
        var b = TenantFixture.bootstrap(tenants, "pipe-iso-b");

        repo.replace(a.project().id(), buildSamplePipeline());
        // Project B still empty.
        Pipeline bPipeline = repo.load(b.project().id());
        assertTrue(bPipeline.callSites().isEmpty(), "import to project A must not leak into project B");
    }

    private static Pipeline buildSamplePipeline() {
        return new Pipeline(
                "0.3.0",
                "v1",
                List.of(), // packs
                new ProductProfile("docs", List.of(), null, List.of(), List.of(), List.of(), List.of()),
                List.of(new ImplicitInvariant(
                        "no_pii",
                        "never echo user pii",
                        "high",
                        List.of("src/foo.py: pii redaction call"),
                        "all_call_sites",
                        List.of(),
                        List.of())),
                List.of(), // invariantCoverage
                null, // runtime
                List.of(new CallSite(
                        "cs_summarize",
                        "doc_summary",
                        "cli_agent",
                        "openai",
                        "gpt-4",
                        "you are a helpful summariser",
                        "the literal prompt template",
                        "def summarize():\n    ...",
                        "src/app/summarize.py",
                        42,
                        "summarize",
                        "high",
                        "summarise the doc",
                        List.of(
                                new Constraint("schema", "must be valid JSON", "deterministic"),
                                new Constraint("length", "≤ 200 words", "judge")),
                        12,
                        List.of(), // sourceSpans
                        null, // datasetPath
                        null, // observed
                        List.of(new CallSite.ExpectedSpan(
                                "name", "summarize*", "span", "high", "inferred")) // expectedSpans
                        ,
                        null,
                        List.of())),
                List.of(new Chain(
                        "chain_docs",
                        "summary-then-followup",
                        List.of("cs_summarize", "cs_followup"),
                        "trace_confirmed",
                        "high",
                        "from trace 2025-05-01 evidence",
                        List.of() // ensembleSpanIds
                        )),
                List.of(new FailureMode(
                        "cs_summarize::hallucinates_facts",
                        "hallucinates_facts",
                        "fabricates information not in the doc",
                        "high",
                        "single_call",
                        "cs_summarize",
                        null,
                        "B",
                        List.of(), // packIds
                        List.of(), // complianceTags
                        "tax::faithfulness",
                        false, // graderDeferred
                        "cs_summarize::hallucinates_facts::grader" // graderId
                        )),
                List.of(new TaxonomyNode(
                        "tax::faithfulness",
                        "Faithfulness",
                        "Answer must reflect the source",
                        null,
                        List.of("cs_summarize"),
                        List.of(),
                        null,
                        List.of())),
                new Progress(1, 1, 0),
                List.of());
    }
}
