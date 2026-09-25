// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.model.Pipeline;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The pipeline-backed MCP tools: {@code get_project}, {@code list_call_sites} and {@code list_failure_modes}
 * render the project's imported {@code pipeline.yaml} for an agent. The {@link PipelineService} is mocked with a
 * real {@link Pipeline} parsed from its wire shape, so what is asserted is the MCP rendering (which fields reach
 * the agent, under which names) and the filter semantics of {@code list_failure_modes}.
 */
class McpPipelineToolsTest {

    private static final String PROJECT_ID = "proj-1";

    /**
     * Two call sites (one with observed traffic stats, one without) and two failure modes that differ on every
     * axis {@code list_failure_modes} filters by, so each filter keeps exactly one of them.
     */
    private static final String PIPELINE_JSON = """
            {
              "version": "0.3.0",
              "product_hint": "support bot",
              "packs": [
                {"id": "security", "name": "Security", "version": "1.2.0", "tier_hint": "included",
                 "enabled_by": "auto"}
              ],
              "runtime": {"judge_model": "judge-1", "judge_temperature": 0.2,
                          "severity_policy": {"high": "page"}, "redaction_state": "partial"},
              "call_sites": [
                {"id": "cs-a", "use_case": "answer", "provider": "openai", "model": "gpt-x",
                 "shape": "rag_answer", "intent": "answers questions", "sample_count": 12,
                 "dataset_path": "data/cs-a.jsonl",
                 "observed": {"error_rate": 0.1, "refusal_rate": 0.02, "p50_latency_ms": 300,
                              "p95_latency_ms": 900, "p95_tokens_in": 1200, "p95_tokens_out": 400,
                              "cost_estimate_usd": 0.5, "redaction_state": "none"}},
                {"id": "cs-b", "provider": "anthropic"}
              ],
              "failure_modes": [
                {"id": "fm-1", "name": "Leaks", "description": "leaks a secret", "severity": "high",
                 "scope": "single_call", "call_site_id": "cs-a", "chain_id": "ch-1", "layer": "A",
                 "pack_ids": ["security"], "compliance_tags": ["EU-AI-Act.Art-13"], "taxonomy_node_id": "t-1"},
                {"id": "fm-2", "name": "Rambles", "description": "answers at length", "severity": "low",
                 "scope": "trace", "call_site_id": "cs-b", "chain_id": "ch-2", "layer": "B",
                 "pack_ids": ["quality"], "compliance_tags": []}
              ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private McpToolRegistry registry;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() throws Exception {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, "org-1", "proj", "Proj", null, "2026-08-12T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        PipelineService pipelines = mock(PipelineService.class);
        when(pipelines.getPipeline(PROJECT_ID)).thenReturn(mapper.readValue(PIPELINE_JSON, Pipeline.class));

        this.registry = new McpToolRegistry(
                pipelines,
                projects,
                mock(QueryService.class),
                mock(SpanRepository.class),
                mock(SpanPayloadRepository.class),
                mock(TraceV2Repository.class),
                mock(SessionReadService.class),
                mock(FindingService.class),
                mock(CaseService.class));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    private static TenantContext ctx() {
        return new TenantContext("user-1", null, "org-1", PROJECT_ID, "member", "tok-1");
    }

    @SuppressWarnings("unchecked")
    private JsonNode call(String tool, String argsJson) throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}");
        JsonRpc.Response r =
                dispatcher.dispatch(new JsonRpc.Request("2.0", IntNode.valueOf(1), "tools/call", params), ctx());
        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
        Map<String, Object> result = (Map<String, Object>) Objects.requireNonNull(r.result());
        assertEquals(Boolean.FALSE, result.get("isError"), () -> "tool error: " + result.get("content"));
        return mapper.valueToTree(result.get("structuredContent"));
    }

    /**
     * {@code get_project} is the tool an agent starts from, so the pack roll-up and the judge runtime ride on it:
     * dropping either would leave the agent unable to tell which concern bundles are engaged without a second
     * call it has no tool for.
     */
    @Test
    void getProject_rollsUpThePacksAndTheJudgeRuntime() throws Exception {
        JsonNode project = call("get_project", "{}");

        assertEquals(PROJECT_ID, project.get("id").asText());
        assertEquals("0.3.0", project.get("version").asText());
        assertEquals("support bot", project.get("product_hint").asText());
        assertEquals(2, project.get("call_sites").asInt());
        assertEquals(2, project.get("failure_modes").asInt());
        assertEquals(1, project.get("packs").asInt());
        assertEquals(
                mapper.readTree("[{\"id\":\"security\",\"name\":\"Security\",\"version\":\"1.2.0\","
                        + "\"tier_hint\":\"included\",\"enabled_by\":\"auto\"}]"),
                project.get("packs_detail"));
        assertEquals(
                mapper.readTree("{\"judge_model\":\"judge-1\",\"judge_temperature\":0.2,"
                        + "\"severity_policy\":{\"high\":\"page\"},\"redaction_state\":\"partial\"}"),
                project.get("runtime"));
    }

    /**
     * Each call site carries its observed traffic stats when the pipeline has them, and no {@code observed} key
     * when it does not: a block of nulls would read as "measured, and all zero".
     */
    @Test
    void listCallSites_rendersObservedStatsOnlyForACallSiteThatHasThem() throws Exception {
        JsonNode sites = call("list_call_sites", "{}").get("call_sites");

        assertEquals(2, sites.size());
        JsonNode a = sites.get(0);
        assertEquals("cs-a", a.get("id").asText());
        assertEquals("answer", a.get("use_case").asText());
        assertEquals("openai", a.get("provider").asText());
        assertEquals("gpt-x", a.get("model").asText());
        assertEquals("rag_answer", a.get("shape").asText());
        assertEquals("answers questions", a.get("intent").asText());
        assertEquals(12, a.get("sample_count").asInt());
        assertEquals(0, a.get("source_spans").asInt());
        assertEquals("data/cs-a.jsonl", a.get("dataset_path").asText());
        assertEquals(
                mapper.readTree("{\"error_rate\":0.1,\"refusal_rate\":0.02,\"p50_latency_ms\":300,"
                        + "\"p95_latency_ms\":900,\"p95_tokens_in\":1200,\"p95_tokens_out\":400,"
                        + "\"cost_estimate_usd\":0.5,\"redaction_state\":\"none\"}"),
                a.get("observed"));
        JsonNode b = sites.get(1);
        assertEquals("cs-b", b.get("id").asText());
        assertEquals("anthropic", b.get("provider").asText());
        assertNull(b.get("observed"), "a call site with no traffic stats has no observed block");
    }

    /** Every failure-mode field reaches the agent under its wire name. */
    @Test
    void listFailureModes_withNoFilterRendersEveryModeInFull() throws Exception {
        JsonNode modes = call("list_failure_modes", "{}").get("failure_modes");

        assertEquals(List.of("fm-1", "fm-2"), ids(modes));
        assertEquals(
                mapper.readTree("{\"id\":\"fm-1\",\"name\":\"Leaks\",\"description\":\"leaks a secret\","
                        + "\"severity\":\"high\",\"scope\":\"single_call\",\"call_site_id\":\"cs-a\","
                        + "\"chain_id\":\"ch-1\",\"layer\":\"A\",\"pack_ids\":[\"security\"],"
                        + "\"compliance_tags\":[\"EU-AI-Act.Art-13\"],\"taxonomy_node_id\":\"t-1\"}"),
                modes.get(0));
    }

    /**
     * Each filter keeps only the modes that match it. A filter the handler forgot to apply would return both
     * modes, which an agent reads as "these are the failure modes of cs-a" when one of them belongs elsewhere.
     */
    @ParameterizedTest(name = "{0}={1} keeps {2}")
    @CsvSource({
        "call_site_id, cs-a, fm-1",
        "call_site_id, cs-b, fm-2",
        "chain_id, ch-1, fm-1",
        "chain_id, ch-2, fm-2",
        "scope, single_call, fm-1",
        "scope, trace, fm-2",
        "severity, high, fm-1",
        "severity, low, fm-2",
        "layer, A, fm-1",
        "layer, B, fm-2",
        "pack_id, security, fm-1",
        "pack_id, quality, fm-2",
        "compliance_tag, EU-AI-Act.Art-13, fm-1",
    })
    void listFailureModes_eachFilterKeepsOnlyTheModesThatMatchIt(String filter, String value, String kept)
            throws Exception {
        JsonNode modes = call("list_failure_modes", "{\"" + filter + "\":\"" + value + "\"}")
                .get("failure_modes");

        assertEquals(List.of(kept), ids(modes));
    }

    /**
     * The project guard on a handler invoked without a bound project. The dispatcher always hands a context,
     * but a context minted without a project (a user session reaching the handler) must never fall through to
     * a {@code findById(null)} that could resolve some other project.
     */
    @Test
    @SuppressWarnings("NullAway") // deliberate: a null context is what the guard exists for
    void aHandlerWithNoBoundProjectRefusesBeforeReadingAnything() {
        McpTool getProject = Objects.requireNonNull(registry.tool("get_project"));
        TenantContext noProject = new TenantContext("user-1", null, "org-1", null, "member", "tok-1");

        McpTool.ToolException nullCtx = assertThrows(
                McpTool.ToolException.class, () -> getProject.handler().apply(null, Map.of()));
        McpTool.ToolException nullProject = assertThrows(
                McpTool.ToolException.class, () -> getProject.handler().apply(noProject, Map.of()));

        assertEquals("no project bound to this token", nullCtx.getMessage());
        assertEquals("no project bound to this token", nullProject.getMessage());
    }

    /**
     * {@code strArg} tolerates a null argument map (a handler called with no arguments at all) as "no filter"
     * rather than throwing a NullPointerException that would surface as a -32603.
     */
    @Test
    @SuppressWarnings("NullAway") // deliberate: a null argument map is the input the guard handles
    void aHandlerCalledWithANullArgumentMapReadsItAsNoFilter() {
        McpTool listFailureModes = Objects.requireNonNull(registry.tool("list_failure_modes"));

        Object result = listFailureModes.handler().apply(ctx(), null);

        assertEquals(List.of("fm-1", "fm-2"), ids(mapper.valueToTree(result).get("failure_modes")));
    }

    private static List<String> ids(@Nullable JsonNode rows) {
        List<String> out = new ArrayList<>();
        Objects.requireNonNull(rows).forEach(r -> out.add(r.get("id").asText()));
        return out;
    }
}
