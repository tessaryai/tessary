// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.model.Pipeline;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An {@link McpToolRegistry} behind a real {@link McpDispatcher}, with every collaborator a bare mock unless a test
 * hands in its own, plus the request and response helpers the MCP tool tests share.
 */
record McpToolHarness(McpToolRegistry registry, McpDispatcher dispatcher) {

    static final String PROJECT_ID = "proj-1";
    static final String ORG_ID = "org-1";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static Builder registryWith() {
        return new Builder();
    }

    static final class Builder {
        private PipelineService pipeline = mock(PipelineService.class);
        private QueryService query = mock(QueryService.class);
        private SpanRepository spans = mock(SpanRepository.class);
        private SpanPayloadRepository payloads = mock(SpanPayloadRepository.class);
        private TraceV2Repository traces = mock(TraceV2Repository.class);
        private SessionReadService sessions = mock(SessionReadService.class);
        private FindingService findings = mock(FindingService.class);
        private CaseService cases = mock(CaseService.class);

        Builder pipeline(Pipeline served) {
            this.pipeline = mock(PipelineService.class);
            when(pipeline.getPipeline(PROJECT_ID)).thenReturn(served);
            return this;
        }

        Builder query(QueryService query) {
            this.query = query;
            return this;
        }

        Builder spans(SpanRepository spans) {
            this.spans = spans;
            return this;
        }

        Builder payloads(SpanPayloadRepository payloads) {
            this.payloads = payloads;
            return this;
        }

        Builder traces(TraceV2Repository traces) {
            this.traces = traces;
            return this;
        }

        Builder sessions(SessionReadService sessions) {
            this.sessions = sessions;
            return this;
        }

        Builder findings(FindingService findings) {
            this.findings = findings;
            return this;
        }

        Builder cases(CaseService cases) {
            this.cases = cases;
            return this;
        }

        McpToolHarness build() {
            ProjectRepository projects = mock(ProjectRepository.class);
            Project project = new Project(
                    PROJECT_ID, ORG_ID, "proj", "Proj", null, "2026-08-12T00:00:00Z", null, null, true, null);
            when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            var registry =
                    new McpToolRegistry(pipeline, projects, query, spans, payloads, traces, sessions, findings, cases);
            return new McpToolHarness(registry, new McpDispatcher(registry, MAPPER));
        }
    }

    static TenantContext ctx() {
        return new TenantContext("user-1", null, ORG_ID, PROJECT_ID, "member", "tok-1");
    }

    static JsonRpc.Request req(int id, String method, @Nullable JsonNode params) {
        return new JsonRpc.Request("2.0", IntNode.valueOf(id), method, params);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> callTool(String name, String argsJson) throws Exception {
        JsonNode params = MAPPER.readTree("{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}");
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/call", params), ctx());
        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
        return (Map<String, Object>) Objects.requireNonNull(r.result());
    }

    /** Re-serialized through Jackson to assert the wire JSON a client sees. */
    static JsonNode structured(Map<String, Object> result) {
        assertEquals(Boolean.FALSE, result.get("isError"), () -> "tool error: " + result.get("content"));
        return MAPPER.valueToTree(Objects.requireNonNull(result.get("structuredContent")));
    }

    static String errorText(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("isError"), "expected isError=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        return Objects.requireNonNull(content.get(0).get("text")).toString();
    }

    JsonNode listedTools() {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "tools/list should not be a JSON-RPC error");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Objects.requireNonNull(r.result());
        return MAPPER.valueToTree(Objects.requireNonNull(result.get("tools")));
    }

    Set<String> listToolNames() {
        List<String> names = new ArrayList<>();
        for (JsonNode t : listedTools()) names.add(t.get("name").asText());
        return Set.copyOf(names);
    }

    JsonNode schemaOf(String toolName) {
        for (JsonNode t : listedTools()) {
            if (toolName.equals(t.get("name").asText())) return t.get("inputSchema");
        }
        throw new AssertionError("tool not registered: " + toolName);
    }

    static SpanRow span(String traceId, String id) {
        return span(traceId, id, "2026-08-17T09:59:59Z");
    }

    static SpanRow span(String traceId, String id, String startedAt) {
        Instant started = Instant.parse(startedAt);
        String endedAt = started.plusSeconds(1).toString();
        return new SpanRow(
                PROJECT_ID,
                traceId,
                id,
                null, // parentSpanId — a root
                traceId + "." + id, // path
                "sess-1",
                "user-9",
                null, // projectVersionId
                "cs-1",
                "chat turn",
                "llm",
                "chat claude-sonnet-5",
                false,
                "ok",
                null, // level
                null, // errorType
                null, // errorMessage
                startedAt,
                endedAt,
                1000L,
                120L,
                "claude-sonnet-5",
                "anthropic/claude-sonnet-5",
                100L, // inputTokens
                20L, // outputTokens
                null, // cacheReadTokens
                null, // cacheWriteTokens
                null, // reasoningTokens
                "0.000300000000",
                "0.000300000000",
                null,
                null,
                SpanRow.CostSource.INFERRED,
                "litellm-2026-08-12",
                "user: this is broken again",
                "ok",
                SpanRow.ResolverState.DONE,
                SpanRow.ResolverState.RESOLVED,
                endedAt,
                false,
                1, // depth (generated)
                120L, // totalTokens (generated)
                "0.000600000000", // totalCost (generated)
                started.plusSeconds(2).toString());
    }

    static SpanPayloadRow payload(String traceId, String spanId) {
        return new SpanPayloadRow(
                PROJECT_ID,
                traceId,
                spanId,
                "user: this is broken again, and here is the whole prompt",
                "ok, here is the whole completion",
                "{\"gen_ai.system\":\"anthropic\"}",
                "{\"input_tokens\":100}",
                "2026-08-17T10:00:00Z");
    }
}
