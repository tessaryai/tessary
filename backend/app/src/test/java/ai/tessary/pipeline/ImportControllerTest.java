// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import ai.tessary.model.Pipeline;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Covers the sharded {@code .tessary/} import surface (v0.4+ layout).
 *
 * <p>Each upload is a multipart bundle containing the shards
 * {@code pipeline/meta.yaml}, {@code pipeline/call_sites/*.yaml},
 * {@code pipeline/failure_modes/*.yaml}, {@code graders/*.yaml}, plus optional
 * sidecars (packs, taxonomy, product_profile, datasets, report.md, etc.).
 *
 * <p>The cases below pin:
 * <ul>
 *   <li>default mode = upsert, fresh project, full diff exposed</li>
 *   <li>repeat upload counts as updated, not added</li>
 *   <li>replace removes absent graders + marks curation entries orphan</li>
 *   <li>upsert keeps absent graders</li>
 *   <li>missing {@code pipeline/meta.yaml} → 400 (meta is required)</li>
 *   <li>sidecar files (.synth-lock.yaml, datasets/*.jsonl, report.md) ignored</li>
 *   <li>{@code .tessary/} root prefix and root-relative paths both accepted</li>
 * </ul>
 */
@SpringBootTest
class ImportControllerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        // Enable auth so this test exercises the real, authenticated request path -- say so
        // directly rather than configuring a fake external-provider key as an indirect toggle.
        // See TestAuthDisabledInitializer's javadoc.
        r.add("tessary.auth.disabled", () -> "false");
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService mcpTokens;

    @Autowired
    PipelineService pipelineService;

    @Autowired
    AuthFilter authFilter;

    final ObjectMapper mapper = new ObjectMapper();
    MockMvc mvc;

    @BeforeEach
    void setup() {
        this.mvc =
                MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    // ================================================================== shard fixtures

    private static final String META_YAML = """
        version: "0.8.0"
        product_hint: "summariser"
        runtime:
          judge_model: claude-sonnet-4-6
          judge_temperature: 0.0
          max_concurrency: 8
        progress:
          sites_completed: 1
          sites_total: 3
          deferred_failure_count: 2
        """;

    private static final String PACKS_YAML = """
        packs:
          - id: quality
            name: Quality
            version: 1.0.0
            tier_hint: included
            enabled_by: auto
            contributes_compliance_tags: []
            content_digest: deadbeef
        """;

    private static final String PRODUCT_PROFILE_YAML = """
        product_profile:
          domain: "document summarisation"
          user_types: []
          business_model: "B2B SaaS"
          data_sensitivity: []
          regulatory_context: []
          brand_voice_signals: []
          notable_dependencies: []
        """;

    private static final String TAXONOMY_YAML = """
        taxonomy:
          - id: tax::faithfulness
            name: Faithfulness
            description: "Answer reflects source."
        """;

    private static final String CALL_SITE_YAML = """
        id: cs_summarize
        use_case: doc_summary
        provider: openai
        model: gpt-4
        shape: summarize
        shape_confidence: high
        intent: "summarise the document"
        constraints: []
        sample_count: 7
        """;

    private static final String FAILURE_MODES_YAML = """
        failure_modes:
          - id: cs_summarize::hallucinates
            name: hallucinates
            description: "fabricates facts"
            severity: high
            scope: single_call
            call_site_id: cs_summarize
            layer: B
            taxonomy_node_id: tax::faithfulness
            grader_deferred: false
            grader_id: cs_summarize::hallucinates::grader
        """;

    /** A v0.7 deferred failure: medium/low severity, no grader synthesised yet. */
    private static final String DEFERRED_FAILURE_MODE_YAML = """
        failure_modes:
          - id: cs_summarize::verbose
            name: verbose
            description: "rambles past the length budget"
            severity: low
            scope: single_call
            call_site_id: cs_summarize
            layer: B
            taxonomy_node_id: tax::faithfulness
            grader_deferred: true
            grader_id: null
        """;

    /** v0.8 quality-dimensions shard (one judgment axis per call site). */
    private static final String QUALITY_DIMENSIONS_YAML = """
        quality_dimensions:
          - id: cs_summarize::clarity
            call_site_id: cs_summarize
            scope: single_call
            name: clarity
            description: "how clearly the summary reads"
            why_it_matters: "unclear summaries erode trust"
            rubric_levels:
              "5": "crystal clear"
              "3": "mostly clear"
              "1": "incomprehensible"
            grader_id: cs_summarize::clarity::grader
        """;

    /** v0.8 score grader (kind=score) bijective with the clarity quality dimension. */
    private static final String SCORE_GRADER_YAML = """
        id: cs_summarize::clarity::grader
        name: "clarity"
        quality_dimension_id: cs_summarize::clarity
        call_site_id: cs_summarize
        scope: single_call
        kind: score
        judge_prompt: "judge how clear the summary is"
        score_scale:
          min: 1
          max: 5
        rubric_levels:
          "5": "crystal clear"
          "3": "mostly clear"
          "1": "incomprehensible"
        self_tests:
          - sample_output: "a lucid summary"
            expected_level: 5
            category: clear_high
            rationale: "top anchor"
          - sample_output: "word salad"
            expected_level: 1
            category: clear_low
            rationale: "bottom anchor"
          - sample_output: "a bit muddled"
            expected_level: 3
            category: near_miss
            rationale: "mid"
        confidence: high
        rationale: "tracks summary quality"
        """;

    private static final String GRADER_YAML_1 = """
        id: cs_summarize::hallucinates::grader
        name: "no hallucination"
        failure_mode_id: cs_summarize::hallucinates
        call_site_id: cs_summarize
        scope: single_call
        taxonomy_node_id: tax::faithfulness
        kind: llm_judge
        applies_when: "answer claims a fact"
        judge_prompt: "judge factual grounding"
        rubric: "PASS: every claim cited."
        self_tests:
          - sample_output: "cites"
            expected_verdict: pass
            category: clear_pass
            rationale: "well-grounded"
          - sample_output: "no citation"
            expected_verdict: fail
            category: clear_fail
            rationale: "fabricated"
          - sample_output: "ambiguous"
            expected_verdict: fail
            category: near_miss
            rationale: "borderline"
        confidence: high
        rationale: "high-impact"
        """;

    /** A grader carrying NO self_tests at all (the post-v7 plugin shape). Must ingest cleanly. */
    private static final String GRADER_YAML_NO_SELF_TESTS = """
        id: cs_summarize::nostests::grader
        name: "no self tests"
        failure_mode_id: cs_summarize::nostests
        call_site_id: cs_summarize
        scope: single_call
        taxonomy_node_id: tax::faithfulness
        kind: llm_judge
        judge_prompt: "judge factual grounding"
        rubric: "PASS: every claim cited."
        confidence: high
        rationale: "no inline self-tests"
        """;

    private static final String GRADER_YAML_2 = """
        id: cs_summarize::tone::grader
        name: "neutral tone"
        failure_mode_id: cs_summarize::tone
        call_site_id: cs_summarize
        scope: single_call
        taxonomy_node_id: tax::tone
        kind: llm_judge
        applies_when: null
        judge_prompt: "is the tone neutral"
        rubric: "PASS: neutral. FAIL: editorialised."
        self_tests:
          - sample_output: "the doc says X"
            expected_verdict: pass
            category: clear_pass
            rationale: "neutral"
          - sample_output: "AMAZING insight"
            expected_verdict: fail
            category: clear_fail
            rationale: "editorialised"
          - sample_output: "noteworthy and interesting"
            expected_verdict: fail
            category: near_miss
            rationale: "slipping toward editorial"
        confidence: medium
        rationale: "secondary check"
        """;

    /**
     * A full minimal bundle: one call site, one failure mode — and a grader shard that must be IGNORED.
     * The grader file is deliberately still here even though this repo synthesises, runs or scores no
     * grader: every repo written by a current plugin ships one, so "the import skips it rather than
     * failing" is exactly what these tests have to keep proving.
     */
    private MockMultipartFile[] bundle(String prefix, MockMultipartFile... extra) {
        var base = new MockMultipartFile[] {
            file(prefix + "pipeline/meta.yaml", META_YAML),
            file(prefix + "pipeline/call_sites/cs_summarize.yaml", CALL_SITE_YAML),
            file(prefix + "pipeline/failure_modes/cs_summarize.yaml", FAILURE_MODES_YAML),
            file(prefix + "graders/cs_summarize__hallucinates__grader.yaml", GRADER_YAML_1),
        };
        if (extra.length == 0) return base;
        var combined = new MockMultipartFile[base.length + extra.length];
        System.arraycopy(base, 0, combined, 0, base.length);
        System.arraycopy(extra, 0, combined, base.length, extra.length);
        return combined;
    }

    // ================================================================== happy paths

    @Test
    void importDirectory_defaultMode_isUpsertAndPopulatesDiff() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-default");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "default").plaintext();

        JsonNode data = okMultipart(fix, token, null, bundle(".tessary/"));

        assertEquals("upsert", data.get("mode").asText());
        assertEquals(true, data.get("metaReplaced").asBoolean());
        assertEquals(1, data.get("callSites").get("added").asInt());
        assertEquals(0, data.get("callSites").get("updated").asInt());
        assertEquals(0, data.get("callSites").get("removed").asInt());
        assertEquals(1, data.get("failureModes").get("added").asInt());

        Pipeline back = pipelineService.getPipeline(fix.project().id());
        assertEquals(1, back.failureModes().size());
        assertEquals("0.8.0", back.version());
    }

    @Test
    void importDirectory_roundTripsProgressAndDeferral() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-v07-fields");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "v07").plaintext();

        okMultipart(
                fix,
                token,
                null,
                bundle(
                        ".tessary/",
                        file(".tessary/pipeline/failure_modes/_deferred.yaml", DEFERRED_FAILURE_MODE_YAML)));
        Pipeline back = pipelineService.getPipeline(fix.project().id());

        // meta.progress survives the round-trip.
        assertNotNull(back.progress());
        assertEquals(1, back.progress().sitesCompleted());
        assertEquals(3, back.progress().sitesTotal());
        assertEquals(2, back.progress().deferredFailureCount());

        // Per-failure-mode deferral state survives the round-trip.
        var deferred = back.failureModes().stream()
                .filter(fm -> "cs_summarize::verbose".equals(fm.id()))
                .findFirst()
                .orElseThrow();
        assertTrue(deferred.graderDeferred());
        assertNull(deferred.graderId());

        var graded = back.failureModes().stream()
                .filter(fm -> "cs_summarize::hallucinates".equals(fm.id()))
                .findFirst()
                .orElseThrow();
        assertFalse(graded.graderDeferred());
        assertEquals("cs_summarize::hallucinates::grader", graded.graderId());
    }

    @Test
    void importDirectory_acceptsRootRelativePaths() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-rooted");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "root").plaintext();

        // Some browsers strip the root dir entirely from webkitRelativePath.
        okMultipart(fix, token, null, bundle(""));
        assertEquals(
                1, pipelineService.getPipeline(fix.project().id()).callSites().size());
    }

    @Test
    void importDirectory_upsertTwice_secondImportShowsUpdated() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-twice");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "twice").plaintext();

        okMultipart(fix, token, null, bundle(".tessary/"));
        JsonNode again = okMultipart(fix, token, null, bundle(".tessary/"));

        assertEquals(0, again.get("callSites").get("added").asInt());
        assertEquals(1, again.get("callSites").get("updated").asInt());
        assertEquals(0, again.get("callSites").get("removed").asInt());
    }

    @Test
    void importDirectory_isProjectScoped() throws Exception {
        var a = TenantFixture.bootstrap(tenants, "import-iso-a");
        var b = TenantFixture.bootstrap(tenants, "import-iso-b");
        String tokenA =
                mcpTokens.issue(a.project().id(), a.user().id(), "tok-a").plaintext();
        okMultipart(a, tokenA, null, bundle(".tessary/"));
        assertEquals(
                1, pipelineService.getPipeline(a.project().id()).callSites().size());
        assertEquals(
                0, pipelineService.getPipeline(b.project().id()).callSites().size());
    }

    // ================================================================== ignored siblings

    /**
     * Regression — the v0.4 bundle ships sidecars (.synth-lock.yaml, report.md,
     * index.html, datasets/*.jsonl, .tessary/packs/) alongside the
     * shards. The classifier must drop them silently instead of routing to the
     * grader/shard parsers and choking on the wrong shape.
     */
    @Test
    void importDirectory_ignoresKnownSidecars() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-sidecar-noise");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "noise").plaintext();

        String lockYaml = """
            version: 1
            synthesized_at: 2026-05-18T12:00:00Z
            inputs_digest: deadbeefcafebabe
            graders:
              cs_summarize__hallucinates__grader: 9a3f5b2e7c1d4f80
            """;
        okMultipart(
                fix,
                token,
                null,
                bundle(
                        ".tessary/",
                        file(".tessary/.synth-lock.yaml", lockYaml),
                        file(".tessary/report.md", "# report"),
                        file(".tessary/index.html", "<!doctype html><html></html>"),
                        file(".tessary/datasets/cs_summarize.jsonl", "{\"trace_id\":\"abc\"}\n"),
                        file(".tessary/packs/quality/pack.yaml", "id: quality\n")));

        Pipeline back = pipelineService.getPipeline(fix.project().id());
        assertEquals(1, back.callSites().size(), "only the real shard should land in the DB");
    }

    // ================================================================== error cases

    @Test
    void importDirectory_missingMeta_400() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-no-meta");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "noMeta").plaintext();
        mvc.perform(multipart(url(fix))
                        .file(file(".tessary/graders/cs_summarize__hallucinates__grader.yaml", GRADER_YAML_1))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importDirectory_emptyUpload_400() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-dir-empty");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "empty").plaintext();
        // Need at least one part for multipart to be recognised; send a noise
        // file that the classifier will drop.
        mvc.perform(multipart(url(fix))
                        .file(new MockMultipartFile(
                                "files", "README.md", "text/markdown", "# noise\n".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importDirectory_doubleMetaShard_400() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-dup-meta");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "dup").plaintext();
        mvc.perform(multipart(url(fix))
                        .file(file("a/pipeline/meta.yaml", META_YAML))
                        .file(file("b/pipeline/meta.yaml", META_YAML))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importDirectory_unknownMode_400() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-mode-bad");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "modeBad").plaintext();
        var req = multipart(url(fix) + "?mode=nope")
                .file(file(".tessary/pipeline/meta.yaml", META_YAML))
                .header("Authorization", "Bearer " + token);
        mvc.perform(req).andExpect(status().isBadRequest());
    }

    // ================================================================== helpers

    private JsonNode okMultipart(
            TenantFixture.Setup fix, String token, @Nullable String mode, MockMultipartFile... files) throws Exception {
        String u = url(fix) + (mode == null ? "" : "?mode=" + mode);
        var req = multipart(u).header("Authorization", "Bearer " + token);
        for (MockMultipartFile f : files) req = req.file(f);
        MvcResult res = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).get("data");
    }

    private static MockMultipartFile file(String path, String body) {
        return new MockMultipartFile("files", path, "application/x-yaml", body.getBytes(StandardCharsets.UTF_8));
    }

    private static String url(TenantFixture.Setup fix) {
        return "/api/orgs/" + fix.org().slug() + "/projects/" + fix.project().slug() + "/import";
    }
}
