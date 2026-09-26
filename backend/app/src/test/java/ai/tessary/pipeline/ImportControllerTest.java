// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import ai.tessary.auth.TenantContext;
import ai.tessary.model.Pipeline;
import ai.tessary.open.errors.PipelineError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sharded {@code .tessary/} import surface (v0.4+): default upsert with a full diff, repeat uploads count as
 * updated, replace removes absent graders and orphans curation entries, missing {@code pipeline/meta.yaml} is a 400,
 * sidecars are ignored, and both the {@code .tessary/} prefix and root-relative paths are accepted.
 */
@SpringBootTest
class ImportControllerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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

    @Autowired
    ImportController controller;

    @Autowired
    OrgMembershipRepository memberships;

    @Autowired
    JdbcClient jdbc;

    final ObjectMapper mapper = new ObjectMapper();
    MockMvc mvc;

    @BeforeEach
    void setup() {
        this.mvc =
                MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

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

    /**
     * A minimal bundle with one call site, one failure mode, and a grader shard the import must skip: current plugins
     * still ship one.
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

    /**
     * Regression: v0.4 bundles ship sidecars (.synth-lock.yaml, report.md, index.html, datasets/*.jsonl,
     * .tessary/packs/) that must be dropped, not parsed as shards.
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
        // Multipart needs at least one part; send a noise file the import drops.
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

    @Test
    void importDirectory_replaceMode_removesWhatTheBundleNoLongerDeclares() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-replace");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "replace").plaintext();
        okMultipart(fix, token, null, bundle(".tessary/"));

        JsonNode data = okMultipart(fix, token, "REPLACE", file(".tessary/pipeline/meta.yaml", META_YAML));

        assertEquals("replace", data.get("mode").asText());
        assertEquals(1, data.get("callSites").get("removed").asInt());
        assertEquals(1, data.get("failureModes").get("removed").asInt());
        assertEquals(
                0, pipelineService.getPipeline(fix.project().id()).callSites().size());
    }

    /** A bundle that names its commit binds the project to it, so a later PR can be diffed against it. */
    @Test
    void importDirectory_bindsThePipelineToTheCommitAndRepoItDeclares() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "import-commit");
        String token =
                mcpTokens.issue(fix.project().id(), fix.user().id(), "commit").plaintext();
        String meta = META_YAML + "commit_sha: 0a1b2c3d\nrepo:\n  owner: acme\n  name: summariser\n";

        okMultipart(fix, token, null, file(".tessary/pipeline/meta.yaml", meta));

        Map<String, Object> row = jdbc.sql(
                        "SELECT synced_commit_sha, repo_owner, repo_name FROM pipeline_meta WHERE project_id = :pid")
                .param("pid", fix.project().id())
                .query()
                .singleRow();
        assertEquals(Map.of("synced_commit_sha", "0a1b2c3d", "repo_owner", "acme", "repo_name", "summariser"), row);
    }

    /** Only an owner (or the plugin's project token) may overwrite the pipeline; a member session may not. */
    @Test
    void importDirectory_aMemberSessionIsForbiddenAndChangesNothing() {
        var fix = TenantFixture.bootstrap(tenants, "import-member");
        Principal member = tenants.upsertUserFromWorkos(
                "user_import_member_" + System.nanoTime(),
                "import-member+" + System.nanoTime() + "@example.com",
                "m",
                null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), member.id(), OrgMembership.MEMBER, Instant.now().toString()));
        TenantContext session = new TenantContext(member.id(), member.email(), null, null, null, null);

        ResponseStatusException e = assertThrows(
                ResponseStatusException.class,
                () -> controller.importDirectory(
                        session, fix.org().slug(), fix.project().slug(), "upsert", bundle(".tessary/")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertEquals(
                0, pipelineService.getPipeline(fix.project().id()).callSites().size());
    }

    /** A part whose bytes cannot be read is a named 400, not a 500 or a silently dropped shard. */
    @Test
    void importDirectory_aPartThatCannotBeReadIsRefusedByName() {
        var fix = TenantFixture.bootstrap(tenants, "import-unreadable");
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        MultipartFile unreadable =
                new MockMultipartFile("files", ".tessary/pipeline/meta.yaml", "application/x-yaml", new byte[] {1}) {
                    @Override
                    public byte[] getBytes() throws IOException {
                        throw new IOException("temp file gone");
                    }
                };

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.importDirectory(
                        owner, fix.org().slug(), fix.project().slug(), "upsert", new MultipartFile[] {unreadable}));

        assertEquals(PipelineError.FILE_READ_FAILED, e.error());
        assertEquals(PipelineError.FILE_READ_FAILED.render(".tessary/pipeline/meta.yaml"), e.getMessage());
    }

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
