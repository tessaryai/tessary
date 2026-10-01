// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.PROJECT_ID;
import static ai.tessary.mcp.McpToolHarness.errorText;
import static ai.tessary.mcp.McpToolHarness.payload;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.span;
import static ai.tessary.mcp.McpToolHarness.structured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryDataset;
import ai.tessary.query.QueryDtos;
import ai.tessary.query.QueryRepository;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanKey;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.traces.SessionDtos;
import ai.tessary.traces.SessionReadService;
import ai.tessary.traces.TraceDtos;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * The plural substrate readers ({@code list_traces}, {@code list_spans}, {@code list_sessions}, {@code get_session})
 * at the dispatcher, with the repositories and services mocked: project scoping, argument mapping, paging, the
 * payload-scope rule, and verbatim rendering. The SQL is tested with the REST controllers.
 *
 * <p>Three contracts matter most: list rows carry previews and never payloads; an unscoped payload request is an
 * error, never a silent page of previews; and the cursor comes from the same {@code TracePageCodec} as REST, so a
 * token from one surface cannot resume the other from the wrong row.
 */
class McpSubstrateReadToolsTest {

    private TraceV2Repository traces;
    private SessionReadService sessions;
    private QueryService queries;
    private SpanRepository spans;
    private SpanPayloadRepository payloads;
    private McpToolHarness mcp;

    @BeforeEach
    void setup() {
        this.traces = mock(TraceV2Repository.class);
        this.sessions = mock(SessionReadService.class);
        this.queries = mock(QueryService.class);
        this.spans = mock(SpanRepository.class);
        this.payloads = mock(SpanPayloadRepository.class);
        this.mcp = registryWith()
                .query(queries)
                .spans(spans)
                .payloads(payloads)
                .traces(traces)
                .sessions(sessions)
                .build();
    }

    // ---- registration --------------------------------------------------------------------------

    /**
     * Sessions carry no rollup, so recency is the only order (§7.5); a {@code sort} argument would mean summing every
     * session first.
     */
    @Test
    void listSessions_offersNoSortArgument() {
        JsonNode properties = mcp.schemaOf("list_sessions").get("properties");
        Set<String> args = new java.util.HashSet<>();
        properties.fieldNames().forEachRemaining(args::add);
        assertEquals(Set.of("limit", "cursor"), args, "a sessions page takes paging and nothing else");
    }

    // ---- list_traces ---------------------------------------------------------------------------

    /**
     * Wire names map onto {@link TraceV2Repository.TraceQuery}; {@code call_site_id} becomes {@code callSite}, the
     * rename worth pinning.
     */
    @Test
    void listTraces_typedFiltersMapOntoTheRepositoryQuery() throws Exception {
        structured(mcp.callTool("list_traces", """
                {"model":"claude-sonnet-5","kind":"llm","call_site_id":"cs-1",
                 "status":"error","q":"broken","range":{"from":"2026-08-10T00:00:00Z","to":"2026-08-17T00:00:00Z"}}
                """));

        ArgumentCaptor<TraceV2Repository.TraceQuery> query =
                ArgumentCaptor.forClass(TraceV2Repository.TraceQuery.class);
        ArgumentCaptor<String> sort = ArgumentCaptor.forClass(String.class);
        verify(traces).list(eq(PROJECT_ID), query.capture(), sort.capture(), anyInt(), any(), any(), any());
        TraceV2Repository.TraceQuery q = query.getValue();
        assertEquals("claude-sonnet-5", q.model());
        assertEquals("llm", q.kind());
        assertEquals("cs-1", q.callSite());
        assertEquals("error", q.status());
        assertEquals("broken", q.q());
        assertEquals("2026-08-10T00:00:00Z", q.from());
        assertEquals("2026-08-17T00:00:00Z", q.to());
        // Newest-first is the one order whose keyset needs no NULLS-LAST branch.
        assertNull(sort.getValue(), "list_traces must not ask the repository for a rollup sort");
    }

    @Test
    void listTraces_clampsAnOversizedLimitToTheHundredRowCap() throws Exception {
        List<TraceV2Repository.Summary> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(summary("t-" + i, "2026-08-17T10:00:00Z", 0));
        }
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(rows);

        JsonNode body = structured(mcp.callTool("list_traces", "{\"limit\":500}"));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(traces).list(eq(PROJECT_ID), any(), any(), limit.capture(), any(), any(), any());
        assertEquals(101, limit.getValue().intValue(), "asked for 100 + the one that detects a next page");
        assertEquals(100, body.get("traces").size(), "and rendered only the 100");
        assertFalse(body.get("next_cursor").isNull(), "the extra row means there is another page");
    }

    /** Page two resumes from page one's last row, not the over-fetched row, which would skip itself. */
    @Test
    void listTraces_cursorResumesTheKeysetWhereThePageEnded() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(
                        summary("t-1", "2026-08-17T10:00:00Z", 0),
                        summary("t-2", "2026-08-17T09:00:00Z", 0),
                        summary("t-3", "2026-08-17T08:00:00Z", 0)));

        JsonNode first = structured(mcp.callTool("list_traces", "{\"limit\":2}"));
        String cursor = first.get("next_cursor").asText();

        structured(mcp.callTool("list_traces", "{\"limit\":2,\"cursor\":\"" + cursor + "\"}"));

        ArgumentCaptor<String> beforeSort = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> beforeStartedAt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> beforeId = ArgumentCaptor.forClass(String.class);
        verify(traces, org.mockito.Mockito.times(2))
                .list(
                        eq(PROJECT_ID),
                        any(),
                        any(),
                        anyInt(),
                        beforeSort.capture(),
                        beforeStartedAt.capture(),
                        beforeId.capture());
        assertEquals("t-2", beforeId.getAllValues().get(1), "resumes after the last rendered row, not after t-3");
        assertEquals("2026-08-17T09:00:00Z", beforeStartedAt.getAllValues().get(1));
        // An empty sort slot decodes to "no value", not "", which the keyset would cast to numeric.
        assertNull(beforeSort.getAllValues().get(1));
    }

    /**
     * An unreadable token restarts at the newest page, as {@code QueryRepository} does; an error would be one the
     * caller cannot act on.
     */
    @Test
    void listTraces_unreadableCursorSilentlyRestartsAtPageOne() throws Exception {
        structured(mcp.callTool("list_traces", "{\"cursor\":\"not-a-cursor\"}"));

        ArgumentCaptor<String> beforeStartedAt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> beforeId = ArgumentCaptor.forClass(String.class);
        verify(traces)
                .list(eq(PROJECT_ID), any(), any(), anyInt(), any(), beforeStartedAt.capture(), beforeId.capture());
        assertNull(beforeStartedAt.getValue());
        assertNull(beforeId.getValue());
    }

    /**
     * A row is the stored rollup plus previews; the absent payload fields are the assertion. A never-rolled-up trace
     * has no error count, so it is neither ok nor errored.
     */
    @Test
    void listTraces_rowsAreRollupRowsAndCarryNoPayload() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(
                        summary("t-1", "2026-08-17T10:00:00Z", 2), summary("t-2", "2026-08-17T09:00:00Z", null)));

        JsonNode body = structured(mcp.callTool("list_traces", "{\"limit\":2}"));
        JsonNode row = body.get("traces").get(0);
        assertTrue(body.get("next_cursor").isNull(), "exactly a page's worth means the page was the last one");
        JsonNode unrolled = body.get("traces").get(1);
        assertTrue(unrolled.get("status").isNull(), "no error count means no status");
        assertTrue(unrolled.get("error_count").isNull());

        assertEquals("t-1", row.get("id").asText());
        assertEquals(3, row.get("span_count").asInt());
        assertEquals(2, row.get("error_count").asInt());
        assertEquals("error", row.get("status").asText(), "a non-zero error count reads as error, not as ok");
        assertEquals(120, row.get("total_tokens").asInt());
        // Beside the total: a model with no rate ran, so a small figure is not a cheap turn.
        assertEquals(1, row.get("unpriced_spans").asInt());
        assertTrue(row.get("is_settled").asBoolean());
        assertEquals("user: this is broken again", row.get("input_preview").asText());
        for (String payloadField : List.of("input", "output", "attributes", "provided_usage", "spans")) {
            assertNull(row.get(payloadField), "a list row must not carry " + payloadField);
        }
    }

    // ---- list_spans ----------------------------------------------------------------------------

    /**
     * Every typed filter must be allow-listed on the spans dataset, read off {@link QueryDataset#filterFields()}, or
     * callers get a {@code 400 unknown field} for an argument the schema promised.
     */
    @Test
    void listSpans_everyTypedFilterIsAllowListedOnTheSpansDataset() {
        Set<String> nonFilterArgs = Set.of("range", "q", "mode", "fields", "limit", "cursor");
        JsonNode properties = mcp.schemaOf("list_spans").get("properties");
        List<String> typed = new ArrayList<>();
        properties.fieldNames().forEachRemaining(name -> {
            if (!nonFilterArgs.contains(name)) typed.add(name);
        });
        assertEquals(
                Set.of("trace_id", "call_site_id", "kind", "name", "status", "model_id", "session_id"),
                Set.copyOf(typed),
                "the advertised convenience filters");
        for (String field : typed) {
            assertTrue(
                    QueryDataset.SPANS.filterFields().contains(field),
                    field + " is advertised by list_spans but is not a filterable field on the spans dataset");
        }
    }

    /**
     * Typed arguments become the dataset's filter keys; the page cap is this surface's, because rows land in a
     * context window.
     */
    @Test
    void listSpans_mapsTypedArgsOntoTheSearchRequestAndClampsToTheMcpCap() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null));

        structured(mcp.callTool("list_spans", """
                {"trace_id":"t-1","call_site_id":"cs-1","kind":"llm","name":"chat","status":"error",
                 "model_id":"anthropic/claude-sonnet-5","session_id":"sess-1",
                 "q":"refund","mode":"semantic","limit":500,"cursor":"opaque",
                 "range":{"from":"2026-08-16T00:00:00Z","to":"2026-08-17T00:00:00Z"}}
                """));

        var req = ArgumentCaptor.forClass(QueryDtos.SearchRequest.class);
        verify(queries).search(eq(PROJECT_ID), req.capture());
        QueryDtos.SearchRequest sent = req.getValue();
        assertEquals("spans", sent.dataset());
        assertEquals("refund", sent.q());
        assertEquals("semantic", sent.mode());
        assertEquals("opaque", sent.cursor());
        assertEquals(100, Objects.requireNonNull(sent.limit()).intValue(), "clamped to the MCP page cap");
        assertEquals(
                "2026-08-16T00:00:00Z", Objects.requireNonNull(sent.range()).from());
        assertEquals(
                Map.of(
                        "trace_id", "t-1",
                        "call_site_id", "cs-1",
                        "kind", "llm",
                        "name", "chat",
                        "status", "error",
                        "model_id", "anthropic/claude-sonnet-5",
                        "session_id", "sess-1"),
                Objects.requireNonNull(sent.filters()));
    }

    /**
     * Payload fields are absent, not null: a null {@code input} beside {@code payload_available: true} would claim
     * the call had no input.
     */
    @Test
    void listSpans_defaultRowsCarryPreviewsAndAvailabilityButNoPayloadKeys() throws Exception {
        when(queries.search(eq(PROJECT_ID), any()))
                .thenReturn(searchPage("next-page", key("t-1", "s-1"), key("t-1", "s-2")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1"), span("t-1", "s-2")));
        when(payloads.existingKeys(eq(PROJECT_ID), any())).thenReturn(Set.of(new SpanKey("t-1", "s-1")));

        JsonNode body = structured(mcp.callTool("list_spans", "{}"));
        JsonNode rows = body.get("spans");
        // The cursor is QueryRepository's, passed through, so it resumes the same scan.
        assertEquals("next-page", body.get("next_cursor").asText());

        assertEquals(2, rows.size());
        assertEquals("t-1", rows.get(0).get("trace_id").asText());
        assertEquals("s-1", rows.get(0).get("span_id").asText());
        assertEquals("llm", rows.get(0).get("kind").asText());
        assertEquals("anthropic/claude-sonnet-5", rows.get(0).get("model_id").asText());
        assertEquals(
                "user: this is broken again", rows.get(0).get("input_preview").asText());
        assertTrue(rows.get(0).get("payload_available").asBoolean(), "a payload row exists for s-1");
        assertFalse(rows.get(1).get("payload_available").asBoolean(), "s-2's payload has aged out");
        for (String payloadField : List.of("input", "output", "attributes", "provided_usage")) {
            assertNull(rows.get(0).get(payloadField), "an un-asked-for page must not carry " + payloadField);
        }
        // One existence probe per page, never the payload text.
        verify(payloads).existingKeys(eq(PROJECT_ID), any());
        verify(payloads, org.mockito.Mockito.never()).listByKeys(any(), any());
    }

    /**
     * The rule this phase exists for: a refused payload request is an error, never a compact page, because an agent
     * handed previews would reason over a truncated prompt. The message carries the rule and the fix.
     */
    @Test
    void listSpans_unscopedPayloadRequestIsAnErrorNamingTheRule() throws Exception {
        String text = errorText(mcp.callTool("list_spans", "{\"fields\":[\"payload\"]}"));

        assertTrue(text.contains("trace_id"), text);
        assertTrue(text.contains("24h"), text);
        assertTrue(text.contains("payload_available"), text);
        // Checked before the page is chosen, so a refused call costs no query.
        verify(queries, org.mockito.Mockito.never()).search(any(), any());
    }

    @Test
    void listSpans_payloadRequestPinnedToOneTraceIsAllowed() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(payload("t-1", "s-1")));

        JsonNode row = structured(mcp.callTool("list_spans", "{\"trace_id\":\"t-1\",\"fields\":[\"payload\"]}"))
                .get("spans")
                .get(0);

        assertEquals(
                "user: this is broken again, and here is the whole prompt",
                row.get("input").asText());
        assertEquals("ok, here is the whole completion", row.get("output").asText());
        assertTrue(row.get("payload_available").asBoolean());
        // One keyed read per page, never one per row.
        verify(payloads).listByKeys(eq(PROJECT_ID), any());
    }

    @Test
    void listSpans_payloadRequestInsideTheWindowIsAllowed() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(payload("t-1", "s-1")));

        JsonNode body = structured(mcp.callTool("list_spans", """
                {"fields":["payload"],"range":{"from":"2026-08-16T01:00:00Z","to":"2026-08-17T00:00:00Z"}}
                """));

        assertEquals(
                "user: this is broken again, and here is the whole prompt",
                body.get("spans").get(0).get("input").asText());
    }

    static Stream<Arguments> refusedPayloadRequests() {
        return Stream.of(
                Arguments.of("wider than the window, refused with the width it saw", """
                        {"fields":["payload"],"range":{"from":"2026-08-10T00:00:00Z","to":"2026-08-17T00:00:00Z"}}
                        """, List.of("168h", "24h")),
                // A half-open range is unbounded. Reading the open end as "now" would make the rule depend on the
                // server clock.
                Arguments.of(
                        "an open-ended range",
                        "{\"fields\":[\"payload\"],\"range\":{\"from\":\"2026-08-17T00:00:00Z\"}}",
                        List.of("open at one end")),
                // Names which bound is unreadable; the query layer never parses them, so this rule is the first reader.
                Arguments.of(
                        "an unparseable bound, named",
                        "{\"fields\":[\"payload\"],\"range\":{\"from\":\"yesterday\",\"to\":\"today\"}}",
                        List.of("range.from")),
                Arguments.of(
                        "an unknown fields entry, a tool error rather than ignored",
                        "{\"trace_id\":\"t-1\",\"fields\":[\"attributes\"]}",
                        List.of("attributes", "payload")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedPayloadRequests")
    void listSpans_aPayloadRequestOutsideTheRuleIsAToolErrorNamingWhy(String why, String argsJson, List<String> named)
            throws Exception {
        String text = errorText(mcp.callTool("list_spans", argsJson));

        assertTrue(named.stream().allMatch(text::contains), why + ": " + text);
    }

    /**
     * Rows keep the retrieval's order: for semantic mode that order is the cosine ranking, and SQL returns an id set
     * in no order.
     */
    @Test
    void listSpans_rowsFollowTheRetrievalRankingNotTheHydrationOrder() throws Exception {
        when(queries.search(eq(PROJECT_ID), any()))
                .thenReturn(searchPage(null, key("t-1", "s-1"), key("t-2", "s-2"), key("t-3", "s-3")));
        when(spans.listByKeys(eq(PROJECT_ID), any()))
                .thenReturn(List.of(span("t-3", "s-3"), span("t-1", "s-1"), span("t-2", "s-2")));

        JsonNode rows = structured(mcp.callTool("list_spans", "{\"mode\":\"semantic\",\"q\":\"angry customer\"}"))
                .get("spans");

        assertEquals("s-1", rows.get(0).get("span_id").asText());
        assertEquals("s-2", rows.get(1).get("span_id").asText());
        assertEquals("s-3", rows.get(2).get("span_id").asText());
    }

    /** A span removed between search and hydration is dropped, not rendered half-populated. */
    @Test
    void listSpans_aSpanThatVanishedBetweenTheTwoReadsIsDropped() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1"), key("t-1", "gone")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));

        JsonNode rows = structured(mcp.callTool("list_spans", "{}")).get("spans");

        assertEquals(1, rows.size());
        assertEquals("s-1", rows.get(0).get("span_id").asText());
    }

    /** A query-layer rejection (bad mode, no vector index) is a clean tool error, not a {@code -32603}. */
    @Test
    void listSpans_queryLayerRejectionIsACleanToolError() throws Exception {
        when(queries.search(eq(PROJECT_ID), any()))
                .thenThrow(new TessaryException(QueryError.UNKNOWN_SEARCH_MODE, "fuzzy"));

        String text = errorText(mcp.callTool("list_spans", "{\"mode\":\"fuzzy\"}"));

        assertTrue(text.contains("fuzzy"), text);
    }

    // ---- list_sessions / get_session -----------------------------------------------------------

    @Test
    void listSessions_delegatesScopedWithTheClampedPageSizeAndRendersVerbatim() throws Exception {
        when(sessions.page(eq(PROJECT_ID), anyInt(), any(), eq(false)))
                .thenReturn(new SessionDtos.SessionsPage(
                        List.of(new SessionDtos.SessionListItem(
                                "sess-1",
                                "user-9",
                                "2026-08-17T09:00:00Z",
                                "2026-08-17T10:00:00Z",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                        "cursor-2"));

        JsonNode body = structured(mcp.callTool("list_sessions", "{\"limit\":500,\"cursor\":\"opaque-token\"}"));

        // The page size is clamped by the tool, not the service, and the cursor passes through untouched.
        verify(sessions).page(PROJECT_ID, 100, "opaque-token", false);
        assertEquals("sess-1", body.get("sessions").get(0).get("id").asText());
        assertEquals("user-9", body.get("sessions").get(0).get("user_id").asText());
        assertEquals("cursor-2", body.get("next_cursor").asText());
    }

    /**
     * Rendered verbatim, with both honesty flags: {@code unsettled_traces} and {@code traces_truncated}. Dropping
     * either reports a lower bound as final.
     */
    @Test
    void getSession_rendersTheDetailVerbatimIncludingBothHonestyFlags() throws Exception {
        when(sessions.detail(PROJECT_ID, "sess-1"))
                .thenReturn(Optional.of(new SessionDtos.SessionDetail(
                        "sess-1",
                        "user-9",
                        "2026-08-17T09:00:00Z",
                        "2026-08-17T10:00:00Z",
                        4,
                        1,
                        12L,
                        1L,
                        900L,
                        new BigDecimal("0.0042"),
                        2L,
                        true,
                        List.of(TraceDtos.item(summary("t-1", "2026-08-17T09:00:00Z", 0))))));

        JsonNode body = structured(mcp.callTool("get_session", "{\"id\":\"sess-1\"}"));

        verify(sessions).detail(PROJECT_ID, "sess-1");
        assertEquals("sess-1", body.get("id").asText());
        assertEquals(4, body.get("trace_count").asInt());
        assertEquals(1, body.get("unsettled_traces").asInt(), "one addend is still moving, so the totals are a floor");
        assertEquals(2, body.get("unpriced_spans").asInt());
        assertTrue(body.get("traces_truncated").asBoolean());
        assertEquals("t-1", body.get("traces").get(0).get("id").asText());
        // The session's traces are rollup rows: previews, never payloads.
        assertEquals(
                "user: this is broken again",
                body.get("traces").get(0).get("input_preview").asText());
        assertNull(body.get("traces").get(0).get("input"));
    }

    @Test
    void getSession_unknownIdIsACleanToolErrorNotAn32603() throws Exception {
        when(sessions.detail(eq(PROJECT_ID), any())).thenReturn(Optional.empty());

        String text = errorText(mcp.callTool("get_session", "{\"id\":\"nope\"}"));

        assertTrue(text.contains("session not found"), text);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static SpanKey key(String traceId, String spanId) {
        return new SpanKey(traceId, spanId);
    }

    /** A spans search page as {@link QueryRepository} returns it. */
    private static QueryRepository.SearchPage searchPage(@Nullable String nextCursor, SpanKey... keys) {
        List<QueryRepository.SearchRow> rows = new ArrayList<>(keys.length);
        for (SpanKey k : keys) {
            rows.add(new QueryRepository.SearchRow(
                    k.handle(),
                    "2026-08-17T10:00:00Z",
                    "2026-08-17T10:00:00Z",
                    Map.of("trace_id", k.traceId(), "span_id", k.spanId(), "kind", "llm")));
        }
        if (keys.length == 0) {
            rows.add(new QueryRepository.SearchRow(
                    "t-1:s-1",
                    "2026-08-17T10:00:00Z",
                    "2026-08-17T10:00:00Z",
                    Map.of("trace_id", "t-1", "span_id", "s-1")));
        }
        return new QueryRepository.SearchPage(List.copyOf(rows), nextCursor);
    }

    private static TraceV2Repository.Summary summary(String id, String startedAt, @Nullable Integer errorCount) {
        return new TraceV2Repository.Summary(
                id,
                "support turn",
                startedAt,
                "2026-08-17T10:00:02Z",
                2000L,
                "sess-1",
                "user-9",
                "thread-7",
                "cs-1",
                3,
                errorCount,
                100L,
                20L,
                null,
                null,
                null,
                120L,
                new BigDecimal("0.0003"),
                new BigDecimal("0.0003"),
                new BigDecimal("0.0006"),
                1,
                true,
                "user: this is broken again",
                "ok");
    }
}
