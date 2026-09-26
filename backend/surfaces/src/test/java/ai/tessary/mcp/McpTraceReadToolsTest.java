// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanKey;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The wire contract of {@code get_span} and {@code get_trace}, which step outside the query firewall to return typed
 * columns plus {@code span_payload} text. Repositories are mocked; pinned here are composite identity, project
 * scoping by PK prefix, the rollup on {@code get_trace}, payload absence as nulls, and clean tool errors.
 *
 * <p>The load-bearing test is {@link #getSpan_withoutTraceIdExplainsTheNewIdentity()}: an old plugin's {@code
 * get_span(id)} must read as "update your plugin", not "that span was deleted".
 */
class McpTraceReadToolsTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    private final ObjectMapper mapper = new ObjectMapper();
    private SpanRepository spans;
    private SpanPayloadRepository payloads;
    private TraceV2Repository traces;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        this.spans = mock(SpanRepository.class);
        this.payloads = mock(SpanPayloadRepository.class);
        this.traces = mock(TraceV2Repository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, "org-1", "proj", "Proj", null, "2026-06-13T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        // Unused by these tools, but the registry needs them.
        PipelineService pipeline = mock(PipelineService.class);
        QueryService query = mock(QueryService.class);
        FindingService behaviorDrift = mock(FindingService.class);
        var registry = new McpToolRegistry(
                pipeline,
                projects,
                query,
                spans,
                payloads,
                traces,
                mock(SessionReadService.class),
                behaviorDrift,
                mock(CaseService.class));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    private TenantContext ctx() {
        return new TenantContext("user-1", null, "org-1", PROJECT_ID, "member", "tok-1");
    }

    private JsonRpc.Request req(int id, String method, @Nullable JsonNode params) {
        return new JsonRpc.Request("2.0", IntNode.valueOf(id), method, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String name, String argsJson) throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}");
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/call", params), ctx());
        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
        return (Map<String, Object>) Objects.requireNonNull(r.result());
    }

    private JsonNode structured(Map<String, Object> result) {
        assertEquals(Boolean.FALSE, result.get("isError"));
        return mapper.valueToTree(Objects.requireNonNull(result.get("structuredContent")));
    }

    private static String errorText(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("isError"), "expected isError=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        return Objects.requireNonNull(content.get(0).get("text")).toString();
    }

    private static SpanRow span(String traceId, String id, @Nullable String startedAt) {
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
                startedAt == null ? "2026-07-23T17:42:44Z" : startedAt,
                "2026-07-23T17:42:45Z",
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
                "2026-07-23T17:42:45Z",
                false,
                1, // depth (generated)
                120L, // totalTokens (generated)
                "0.000600000000", // totalCost (generated)
                "2026-07-23T17:42:46Z");
    }

    private static SpanPayloadRow payload(String traceId, String spanId) {
        return new SpanPayloadRow(
                PROJECT_ID,
                traceId,
                spanId,
                "user: this is broken again, and here is the whole prompt",
                "ok, here is the whole completion",
                "{\"gen_ai.system\":\"anthropic\"}",
                "{\"input_tokens\":100}",
                "2026-07-23T17:42:45Z");
    }

    private static TraceV2Row rolledUpTrace() {
        return rolledUpTrace(3);
    }

    private static TraceV2Row rolledUpTrace(int spanCount) {
        return new TraceV2Row(
                PROJECT_ID,
                TRACE_ID,
                "sess-1",
                null,
                "thread-7",
                "support turn",
                "user-9",
                null,
                "ok",
                "2026-07-23T17:42:44Z",
                "2026-07-23T17:42:46Z",
                2000L,
                spanCount,
                0, // errorCount
                100L,
                20L,
                null,
                null,
                null,
                120L, // totalTokens
                "0.000300000000",
                "0.000300000000",
                "0.000600000000",
                1, // unpricedSpans
                "user: this is broken again",
                "ok",
                "cs-1",
                null,
                "2026-07-23T17:42:50Z",
                "2026-07-23T17:42:46Z",
                true, // isSettled
                true,
                "2026-07-23T17:42:46Z",
                false);
    }

    @Test
    void getSpan_returnsTypedColumnsAndFullPayload() throws Exception {
        when(spans.findById(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.of(span(TRACE_ID, SPAN_ID, null)));
        when(payloads.find(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.of(payload(TRACE_ID, SPAN_ID)));

        JsonNode s =
                structured(callTool("get_span", "{\"trace_id\":\"" + TRACE_ID + "\",\"span_id\":\"" + SPAN_ID + "\"}"));

        assertEquals(TRACE_ID, s.get("trace_id").asText());
        assertEquals(SPAN_ID, s.get("span_id").asText());
        // The raw text query_search withholds.
        assertEquals(
                "user: this is broken again, and here is the whole prompt",
                s.get("input").asText());
        assertEquals("ok, here is the whole completion", s.get("output").asText());
        assertEquals("{\"gen_ai.system\":\"anthropic\"}", s.get("attributes").asText());
        // The producer's usage receipt rides beside the typed numbers.
        assertEquals("{\"input_tokens\":100}", s.get("provided_usage").asText());
        assertEquals("cs-1", s.get("call_site_id").asText());
    }

    /** Typed buckets and cost_source, which the v1 view lacked. */
    @Test
    void getSpan_carriesTypedBucketsAndCostSource() throws Exception {
        when(spans.findById(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.of(span(TRACE_ID, SPAN_ID, null)));
        when(payloads.find(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.empty());

        JsonNode s =
                structured(callTool("get_span", "{\"trace_id\":\"" + TRACE_ID + "\",\"span_id\":\"" + SPAN_ID + "\"}"));

        assertEquals(100, s.get("input_tokens").asLong());
        assertEquals(20, s.get("output_tokens").asLong());
        assertEquals(120, s.get("total_tokens").asLong());
        assertTrue(s.get("cache_read_tokens").isNull(), "an unreported bucket is null, never 0");
        assertEquals("inferred", s.get("cost_source").asText());
        assertEquals("litellm-2026-08-12", s.get("price_book_version").asText());
        assertEquals("anthropic/claude-sonnet-5", s.get("model_id").asText());
        assertEquals("claude-sonnet-5", s.get("provided_model_name").asText());
    }

    /**
     * A payload aged out of retention leaves the span readable with null text, which is not "the span had no input"
     * and not a not-found.
     */
    @Test
    void getSpan_purgedPayloadIsNullsNotNotFound() throws Exception {
        when(spans.findById(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.of(span(TRACE_ID, SPAN_ID, null)));
        when(payloads.find(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.empty());

        JsonNode s =
                structured(callTool("get_span", "{\"trace_id\":\"" + TRACE_ID + "\",\"span_id\":\"" + SPAN_ID + "\"}"));

        assertEquals(SPAN_ID, s.get("span_id").asText());
        assertTrue(s.get("input").isNull());
        assertTrue(s.get("output").isNull());
        assertTrue(s.get("attributes").isNull());
        assertEquals("llm", s.get("kind").asText());
    }

    /**
     * The migration error: an old plugin sending {@code {"id": ...}} learns the new identity rule and which argument
     * to add.
     */
    @Test
    void getSpan_withoutTraceIdExplainsTheNewIdentity() throws Exception {
        String text = errorText(callTool("get_span", "{\"id\":\"" + SPAN_ID + "\"}"));
        assertTrue(text.contains("trace_id"), text);
        assertTrue(text.contains("(trace_id, span_id)"), text);
        assertTrue(text.contains("unique only within its trace"), text);
        assertFalse(text.contains("not found"), "must not read as data loss: " + text);
    }

    /** {@code id} is a synonym for {@code span_id} once the trace is named. */
    @Test
    void getSpan_acceptsLegacyIdArgumentAlongsideTraceId() throws Exception {
        when(spans.findById(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.of(span(TRACE_ID, SPAN_ID, null)));
        when(payloads.find(PROJECT_ID, TRACE_ID, SPAN_ID)).thenReturn(Optional.empty());

        JsonNode s = structured(callTool("get_span", "{\"trace_id\":\"" + TRACE_ID + "\",\"id\":\"" + SPAN_ID + "\"}"));
        assertEquals(SPAN_ID, s.get("span_id").asText());
    }

    @Test
    void getSpan_missingSpanIdIsToolError() throws Exception {
        String text = errorText(callTool("get_span", "{\"trace_id\":\"" + TRACE_ID + "\"}"));
        assertTrue(text.contains("span_id"), text);
    }

    /** Cross-tenant is a primary-key miss: the project is the first column of the read. */
    @Test
    void getSpan_crossTenantIsNotFound() throws Exception {
        when(spans.findById(PROJECT_ID, "other-trace", SPAN_ID)).thenReturn(Optional.empty());
        String text = errorText(callTool("get_span", "{\"trace_id\":\"other-trace\",\"span_id\":\"" + SPAN_ID + "\"}"));
        assertTrue(text.contains("other-trace"), text);
    }

    /** {@code fields=["payload"]} opts into full payloads; the skeleton is the default. */
    @Test
    void getTrace_returnsRollupRowAndOrderedSpans() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace()));
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt()))
                .thenReturn(List.of(
                        span(TRACE_ID, "aaa1", "2026-07-23T17:42:44Z"),
                        span(TRACE_ID, "bbb2", "2026-07-23T17:42:45Z")));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(payload(TRACE_ID, "aaa1")));

        JsonNode s = structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\",\"fields\":[\"payload\"]}"));

        assertEquals(TRACE_ID, s.get("trace_id").asText());
        assertEquals("sess-1", s.get("session_id").asText());
        // The worker's rollup; the caller never sums spans.
        assertEquals(3, s.get("span_count").asInt());
        assertEquals(120, s.get("total_tokens").asLong());
        assertEquals("0.000600000000", s.get("total_cost").asText());
        assertTrue(s.get("is_settled").asBoolean());
        // Beside the total: a model with no rate ran.
        assertEquals(1, s.get("unpriced_spans").asInt());

        JsonNode spanViews = s.get("spans");
        assertEquals(2, spanViews.size());
        assertEquals("aaa1", spanViews.get(0).get("span_id").asText());
        assertEquals("bbb2", spanViews.get(1).get("span_id").asText());
        // Payloads join in memory from one read, and a span without one still lists.
        assertEquals(
                "user: this is broken again, and here is the whole prompt",
                spanViews.get(0).get("input").asText());
        assertTrue(spanViews.get(0).get("payload_available").asBoolean());
        assertTrue(spanViews.get(1).get("input").isNull());
        assertFalse(spanViews.get(1).get("payload_available").asBoolean());
    }

    /**
     * Skeleton by default: no {@code input}, {@code output}, {@code attributes} or {@code provided_usage} key at all,
     * since absence means "not asked for", while typed columns, previews and {@code payload_available} match full
     * mode. All payload keys are checked, so a partial leak fails.
     */
    @Test
    void getTrace_skeletonByDefaultOmitsPayloadText() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace()));
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt()))
                .thenReturn(List.of(span(TRACE_ID, "aaa1", "2026-07-23T17:42:44Z")));
        when(payloads.existingKeys(eq(PROJECT_ID), any())).thenReturn(Set.of(new SpanKey(TRACE_ID, "aaa1")));

        JsonNode s = structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}"));

        JsonNode span0 = s.get("spans").get(0);
        assertFalse(span0.has("input"), "no payload was requested — input must be ABSENT, not null");
        assertFalse(span0.has("output"), "no payload was requested — output must be ABSENT, not null");
        assertFalse(span0.has("attributes"), "no payload was requested — attributes must be ABSENT, not null");
        assertFalse(span0.has("provided_usage"), "no payload was requested — provided_usage must be ABSENT");
        // A skeleton, not a blank row.
        assertTrue(span0.get("payload_available").asBoolean());
        assertEquals("user: this is broken again", span0.get("input_preview").asText());
        assertEquals("aaa1", span0.get("span_id").asText());
        assertEquals(100, span0.get("input_tokens").asLong());
        verify(payloads, org.mockito.Mockito.never()).listByKeys(any(), any());
    }

    /** An unsettled trace reports null counters: zero would say "used nothing" when the rollup has not run. */
    @Test
    void getTrace_unrolledTraceReportsNullsNotZeros() throws Exception {
        TraceV2Row fresh = TraceV2Row.of(
                PROJECT_ID,
                TRACE_ID,
                null,
                null,
                "support turn",
                null,
                null,
                "2026-07-23T17:42:44Z",
                "2026-07-23T17:42:44Z");
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(fresh));
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt()))
                .thenReturn(List.of(span(TRACE_ID, SPAN_ID, null)));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of());

        JsonNode s = structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}"));

        assertTrue(s.get("span_count").isNull());
        assertTrue(s.get("total_tokens").isNull());
        assertTrue(s.get("total_cost").isNull());
        assertFalse(s.get("is_settled").asBoolean());
        assertEquals(1, s.get("spans").size(), "the spans themselves are there regardless");
    }

    /** A trace whose spans aged out still resolves from its rollup row. */
    @Test
    void getTrace_traceWithNoRemainingSpansStillResolves() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace()));
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt())).thenReturn(List.of());
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of());

        JsonNode s = structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}"));
        assertEquals(TRACE_ID, s.get("trace_id").asText());
        assertEquals(0, s.get("spans").size());
        assertEquals(3, s.get("span_count").asInt(), "the rollup remembers what the retention sweep removed");
    }

    /**
     * The D10 cap: a trace over {@code TRACE_SPAN_CAP} renders its oldest 200 spans and says so. Oldest-first,
     * because later spans are unintelligible without the instructions and first turn; the flag, because otherwise 200
     * reads as the whole trace.
     */
    @Test
    void getTrace_capsSpansOldestFirstAndSaysThePageIsPartial() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace(250)));
        // The read is bounded at cap + 1, so a 250-span trace returns 201 rows; the extra one answers
        // spans_truncated.
        List<SpanRow> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(span(TRACE_ID, String.format(Locale.ROOT, "s-%03d", i), "2026-07-23T17:00:00Z"));
        }
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt())).thenReturn(many);
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of());

        JsonNode s = structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}"));

        assertEquals(200, s.get("spans").size());
        assertTrue(s.get("spans_truncated").asBoolean());
        // listByTrace orders started_at ascending.
        assertEquals("s-000", s.get("spans").get(0).get("span_id").asText());
        assertEquals("s-199", s.get("spans").get(199).get("span_id").asText());
        // The rollup's count is the total the cap is measured against.
        assertEquals(250, s.get("span_count").asInt());
    }

    /**
     * The read itself is bounded as a SQL LIMIT, asserted as an argument because no output can show it: reading a
     * six-figure agent loop whole to render 200 spans risks an OOM.
     */
    @Test
    void getTrace_boundsTheReadItselfAtCapPlusOne() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace(3)));
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt())).thenReturn(List.of());
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of());

        callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}");

        verify(spans).listByTrace(PROJECT_ID, TRACE_ID, 201);
    }

    /** Payloads are read only for the rendered spans, not the whole trace. */
    @Test
    void getTrace_readsPayloadsOnlyForTheSpansItRenders() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace(250)));
        List<SpanRow> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(span(TRACE_ID, String.format(Locale.ROOT, "s-%03d", i), "2026-07-23T17:00:00Z"));
        }
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt())).thenReturn(many);
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of());

        structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\",\"fields\":[\"payload\"]}"));

        ArgumentCaptor<List<SpanKey>> keys = ArgumentCaptor.forClass(List.class);
        verify(payloads).listByKeys(eq(PROJECT_ID), keys.capture());
        assertEquals(200, keys.getValue().size(), "one keyed read, and only for the rendered page");
    }

    /** The skeleton-mode existence probe is keyed to the rendered page too. */
    @Test
    void getTrace_skeletonModeProbesExistenceOnlyForTheSpansItRenders() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace(250)));
        List<SpanRow> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(span(TRACE_ID, String.format(Locale.ROOT, "s-%03d", i), "2026-07-23T17:00:00Z"));
        }
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt())).thenReturn(many);
        when(payloads.existingKeys(eq(PROJECT_ID), any())).thenReturn(Set.of());

        structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}"));

        ArgumentCaptor<List<SpanKey>> keys = ArgumentCaptor.forClass(List.class);
        verify(payloads).existingKeys(eq(PROJECT_ID), keys.capture());
        assertEquals(200, keys.getValue().size(), "one existence probe, and only for the rendered page");
        verify(payloads, org.mockito.Mockito.never()).listByKeys(any(), any());
    }

    /** The flag is always present, so its absence never has to be read. */
    @Test
    void getTrace_shortTraceReportsNotTruncated() throws Exception {
        when(traces.findById(PROJECT_ID, TRACE_ID)).thenReturn(Optional.of(rolledUpTrace()));
        when(spans.listByTrace(eq(PROJECT_ID), eq(TRACE_ID), anyInt()))
                .thenReturn(List.of(span(TRACE_ID, SPAN_ID, null)));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of());

        JsonNode s = structured(callTool("get_trace", "{\"trace_id\":\"" + TRACE_ID + "\"}"));

        assertFalse(s.get("spans_truncated").asBoolean());
    }

    @Test
    void getTrace_unknownTraceIsCleanToolError() throws Exception {
        when(traces.findById(PROJECT_ID, "trace-x")).thenReturn(Optional.empty());
        String text = errorText(callTool("get_trace", "{\"trace_id\":\"trace-x\"}"));
        assertTrue(text.contains("trace-x"), text);
    }
}
