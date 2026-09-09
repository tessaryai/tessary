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
import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.CapabilityService.CapabilitySet;
import ai.tessary.query.QueryDataset;
import ai.tessary.query.QueryDtos;
import ai.tessary.query.QueryRepository;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanKey;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionDtos;
import ai.tessary.traces.SessionReadService;
import ai.tessary.traces.TraceDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The plural substrate readers — {@code list_traces}, {@code list_spans}, {@code list_sessions},
 * {@code get_session} — pinned at the dispatcher, with the repositories, {@link QueryService} and
 * {@link SessionReadService} mocked. What is under test is the MCP wrapper: project scoping, argument mapping
 * onto the existing seams, the paging convention, the payload-scope rule, and verbatim rendering. The SQL
 * behind them is the REST controllers' and the query layer's own, and is tested there.
 *
 * <p><b>Three contracts here are worth more than the rest of the file.</b>
 *
 * <ol>
 *   <li>{@link #listTraces_rowsAreRollupRowsAndCarryNoPayload()} — a list row carries the stored previews and
 *       never the conversation. A reader that got full payloads for fifty traces would have spent its context
 *       before deciding which trace it cared about, which is the whole reason lists and gets are different
 *       tools.
 *   <li>{@link #listSpans_unscopedPayloadRequestIsAnErrorNamingTheRule()} — the one place a list DOES return
 *       payloads, it refuses loudly when the page is not scoped. Returning previews instead would hand an
 *       agent a truncation it has no way to detect.
 *   <li>{@link #listTraces_cursorResumesTheKeysetWhereThePageEnded()} — the cursor is minted by the same
 *       {@code TracePageCodec} the REST list uses. If the two ever drift, a token one surface issues resumes
 *       the other from the wrong row while looking perfectly valid, and nothing errors.
 * </ol>
 */
class McpSubstrateReadToolsTest {

    private static final String PROJECT_ID = "proj-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private TraceV2Repository traces;
    private SessionReadService sessions;
    private QueryService queries;
    private SpanRepository spans;
    private SpanPayloadRepository payloads;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        this.traces = mock(TraceV2Repository.class);
        this.sessions = mock(SessionReadService.class);
        this.queries = mock(QueryService.class);
        this.spans = mock(SpanRepository.class);
        this.payloads = mock(SpanPayloadRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, "org-1", "proj", "Proj", null, "2026-08-17T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        // Every capability on: these tests exercise the tools, not the gate (McpCapabilityGateTest owns that).
        Map<Capability, Boolean> allOn = new EnumMap<>(Capability.class);
        for (Capability c : Capability.values()) allOn.put(c, true);
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.resolve(any())).thenReturn(new CapabilitySet(allOn));
        when(capabilities.isEnabled(any(), any())).thenReturn(true);

        var registry = new McpToolRegistry(
                mock(PipelineService.class),
                projects,
                queries,
                spans,
                payloads,
                traces,
                sessions,
                capabilities,
                mock(FindingService.class),
                mock(CaseService.class));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    // ---- registration --------------------------------------------------------------------------

    @Test
    void allFourSubstrateReadersAreListed() {
        List<String> names = toolNames();
        assertTrue(
                names.containsAll(List.of("list_traces", "list_spans", "list_sessions", "get_session")),
                names.toString());
    }

    /**
     * The §7.5 contract, carried onto the new surface: sessions carry no rollup, so there is nothing to order
     * them by but recency. A {@code sort} argument here would mean summing every session in the project
     * before this page could be chosen — the read shape the v2 substrate exists to make impossible — so its
     * absence is asserted rather than described, exactly as {@code SessionsControllerTest} asserts it for REST.
     */
    @Test
    void listSessions_offersNoSortArgument() {
        JsonNode properties = schemaOf("list_sessions").get("properties");
        Set<String> args = new java.util.HashSet<>();
        properties.fieldNames().forEachRemaining(args::add);
        assertEquals(Set.of("limit", "cursor"), args, "a sessions page takes paging and nothing else");
    }

    // ---- list_traces ---------------------------------------------------------------------------

    @Test
    void listTraces_isScopedToTheTokensProjectAndOverFetchesByOne() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(summary("t-1", "2026-08-17T10:00:00Z", 0)));

        structured(callTool("list_traces", "{}"));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(traces).list(eq(PROJECT_ID), any(), any(), limit.capture(), any(), any(), any());
        // 50 is the default page; the 51st row is what tells the codec there is another page, so it is asked
        // for and never rendered.
        assertEquals(51, limit.getValue().intValue());
    }

    /**
     * The snake_case wire names map onto {@link TraceV2Repository.TraceQuery}'s fields, and {@code range}
     * becomes its two bounds. {@code call_site_id} is the rename worth pinning: the query field is
     * {@code callSite}, and an agent that learned {@code call_site_id} everywhere else must not have to
     * discover a different spelling here.
     */
    @Test
    void listTraces_typedFiltersMapOntoTheRepositoryQuery() throws Exception {
        structured(callTool("list_traces", """
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
        // No sort argument is offered, so none is passed: newest-first is the one ordering whose keyset needs
        // no NULLS-LAST branch in the cursor. "Which traces cost the most" is a query_* question.
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

        JsonNode body = structured(callTool("list_traces", "{\"limit\":500}"));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(traces).list(eq(PROJECT_ID), any(), any(), limit.capture(), any(), any(), any());
        assertEquals(101, limit.getValue().intValue(), "asked for 100 + the one that detects a next page");
        assertEquals(100, body.get("traces").size(), "and rendered only the 100");
        assertFalse(body.get("next_cursor").isNull(), "the extra row means there is another page");
    }

    @Test
    void listTraces_lastPageCarriesNoNextCursor() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(
                        List.of(summary("t-1", "2026-08-17T10:00:00Z", 0), summary("t-2", "2026-08-17T09:00:00Z", 0)));

        JsonNode body = structured(callTool("list_traces", "{\"limit\":2}"));

        assertEquals(2, body.get("traces").size());
        assertTrue(body.get("next_cursor").isNull(), "exactly a page's worth means the page was the last one");
    }

    /**
     * The cursor is opaque to the caller and meaningful to the keyset: page two resumes from the LAST ROW OF
     * PAGE ONE, not from the over-fetched row, which is page two's first row and would skip itself.
     */
    @Test
    void listTraces_cursorResumesTheKeysetWhereThePageEnded() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(
                        summary("t-1", "2026-08-17T10:00:00Z", 0),
                        summary("t-2", "2026-08-17T09:00:00Z", 0),
                        summary("t-3", "2026-08-17T08:00:00Z", 0)));

        JsonNode first = structured(callTool("list_traces", "{\"limit\":2}"));
        String cursor = first.get("next_cursor").asText();

        structured(callTool("list_traces", "{\"limit\":2,\"cursor\":\"" + cursor + "\"}"));

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
        // Nothing was sorted, so the sort slot of the cursor is empty and must decode to "no value" — not to
        // the string "", which the keyset would try to cast to numeric.
        assertNull(beforeSort.getAllValues().get(1));
    }

    /**
     * A token this server cannot read is not an error. Degrading to the newest page is the only failure mode a
     * feed can absorb quietly — the posture {@code QueryRepository} already takes — and the alternative is a
     * tool call that fails for a reason the caller cannot act on.
     */
    @Test
    void listTraces_unreadableCursorSilentlyRestartsAtPageOne() throws Exception {
        structured(callTool("list_traces", "{\"cursor\":\"not-a-cursor\"}"));

        ArgumentCaptor<String> beforeStartedAt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> beforeId = ArgumentCaptor.forClass(String.class);
        verify(traces)
                .list(eq(PROJECT_ID), any(), any(), anyInt(), any(), beforeStartedAt.capture(), beforeId.capture());
        assertNull(beforeStartedAt.getValue());
        assertNull(beforeId.getValue());
    }

    /**
     * A row is the rollup the worker already wrote, plus the stored previews — and nothing else.
     *
     * <p>The absent fields are the assertion. {@code input} / {@code output} / {@code attributes} are what
     * {@code get_span} and {@code get_trace} exist for; a list that carried them would hand a caller fifty
     * whole conversations for a question it has not asked yet.
     */
    @Test
    void listTraces_rowsAreRollupRowsAndCarryNoPayload() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(summary("t-1", "2026-08-17T10:00:00Z", 2)));

        JsonNode row = structured(callTool("list_traces", "{}")).get("traces").get(0);

        assertEquals("t-1", row.get("id").asText());
        assertEquals(3, row.get("span_count").asInt());
        assertEquals(2, row.get("error_count").asInt());
        assertEquals("error", row.get("status").asText(), "a non-zero error count reads as error, not as ok");
        assertEquals(120, row.get("total_tokens").asInt());
        // Load-bearing beside the total: some span ran a model we hold no rate for, so a small figure is not a
        // cheap turn.
        assertEquals(1, row.get("unpriced_spans").asInt());
        assertTrue(row.get("is_settled").asBoolean());
        assertEquals("user: this is broken again", row.get("input_preview").asText());
        for (String payloadField : List.of("input", "output", "attributes", "provided_usage", "spans")) {
            assertNull(row.get(payloadField), "a list row must not carry " + payloadField);
        }
    }

    /**
     * A trace that has never rolled up has no error count, so it is neither ok nor errored. Saying "ok" would
     * be an invention, and this is the row shape most likely to be "helpfully" coalesced by a later edit.
     */
    @Test
    void listTraces_anUnrolledTraceHasNoStatusRatherThanAHealthyOne() throws Exception {
        when(traces.list(eq(PROJECT_ID), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(summary("t-1", "2026-08-17T10:00:00Z", null)));

        JsonNode row = structured(callTool("list_traces", "{}")).get("traces").get(0);

        assertTrue(row.get("status").isNull(), "no error count means no status");
        assertTrue(row.get("error_count").isNull());
    }

    // ---- list_spans ----------------------------------------------------------------------------

    /**
     * Every typed filter {@code list_spans} advertises must be a field the spans dataset already allow-lists.
     *
     * <p>Read off {@link QueryDataset#filterFields()} rather than restated, so this fails the moment someone
     * adds a convenience argument the query firewall will reject — which would otherwise reach a caller as a
     * {@code 400 unknown field} from a tool whose own schema promised the argument.
     */
    @Test
    void listSpans_everyTypedFilterIsAllowListedOnTheSpansDataset() {
        Set<String> nonFilterArgs = Set.of("range", "q", "mode", "fields", "limit", "cursor");
        JsonNode properties = schemaOf("list_spans").get("properties");
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
     * The typed arguments become the spans dataset's own filter keys, and the page bound is this surface's,
     * not the query API's: {@link QueryService} defaults to 100 and permits 1000 because a REST page lands in
     * a table, whereas these rows land in a context window.
     */
    @Test
    void listSpans_mapsTypedArgsOntoTheSearchRequestAndClampsToTheMcpCap() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null));

        structured(callTool("list_spans", """
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

    @Test
    void listSpans_defaultPageIsFiftyAndCarriesTheSearchPagesOwnCursor() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage("next-page"));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));

        JsonNode body = structured(callTool("list_spans", "{}"));

        var req = ArgumentCaptor.forClass(QueryDtos.SearchRequest.class);
        verify(queries).search(eq(PROJECT_ID), req.capture());
        assertEquals(50, Objects.requireNonNull(req.getValue().limit()).intValue());
        // The keyset and its token are QueryRepository's; this tool must pass the page's cursor through rather
        // than mint one of its own, or a token from here would not resume the same scan.
        assertEquals("next-page", body.get("next_cursor").asText());
    }

    /**
     * A default row is the typed columns, the stored previews, and {@code payload_available} — and the payload
     * fields are ABSENT, not null. Absent says "not requested"; a null {@code input} beside
     * {@code payload_available: true} would say the call had no input, which would be a lie about a row whose
     * text is one {@code get_span} away.
     */
    @Test
    void listSpans_defaultRowsCarryPreviewsAndAvailabilityButNoPayloadKeys() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1"), key("t-1", "s-2")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1"), span("t-1", "s-2")));
        // s-1 still has its payload; s-2's has aged out.
        when(payloads.existingKeys(eq(PROJECT_ID), any())).thenReturn(Set.of(new SpanKey("t-1", "s-1")));

        JsonNode rows = structured(callTool("list_spans", "{}")).get("spans");

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
        // The cheap existence probe, once for the page — never the payload text a caller did not ask for.
        verify(payloads).existingKeys(eq(PROJECT_ID), any());
        verify(payloads, org.mockito.Mockito.never()).listByKeys(any(), any());
    }

    /**
     * <b>The rule this phase exists for.</b> A payload request the scope rule refuses is an ERROR, never a
     * compact page: an agent that asked for full text and silently received 200-character previews reasons
     * over a truncated prompt believing it has the whole one, and nothing in the response says otherwise. The
     * message has to carry the rule and the fix, because the caller's only move is to re-ask.
     */
    @Test
    void listSpans_unscopedPayloadRequestIsAnErrorNamingTheRule() throws Exception {
        String text = errorText(callTool("list_spans", "{\"fields\":[\"payload\"]}"));

        assertTrue(text.contains("trace_id"), text);
        assertTrue(text.contains("24h"), text);
        assertTrue(text.contains("payload_available"), text);
        // Nothing was read: the rule is checked before the page is chosen, so a refused call costs no query.
        verify(queries, org.mockito.Mockito.never()).search(any(), any());
    }

    @Test
    void listSpans_payloadRequestPinnedToOneTraceIsAllowed() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(payload("t-1", "s-1")));

        JsonNode row = structured(callTool("list_spans", "{\"trace_id\":\"t-1\",\"fields\":[\"payload\"]}"))
                .get("spans")
                .get(0);

        assertEquals(
                "user: this is broken again, and here is the whole prompt",
                row.get("input").asText());
        assertEquals("ok, here is the whole completion", row.get("output").asText());
        assertTrue(row.get("payload_available").asBoolean());
        // ONE keyed read for the whole page, the shape get_trace uses — never a query per row.
        verify(payloads).listByKeys(eq(PROJECT_ID), any());
    }

    @Test
    void listSpans_payloadRequestInsideTheWindowIsAllowed() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));
        when(payloads.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(payload("t-1", "s-1")));

        JsonNode body = structured(callTool("list_spans", """
                {"fields":["payload"],"range":{"from":"2026-08-16T01:00:00Z","to":"2026-08-17T00:00:00Z"}}
                """));

        assertEquals(
                "user: this is broken again, and here is the whole prompt",
                body.get("spans").get(0).get("input").asText());
    }

    @Test
    void listSpans_payloadRequestWiderThanTheWindowIsRefusedWithTheWidthItSaw() throws Exception {
        String text = errorText(callTool("list_spans", """
                {"fields":["payload"],"range":{"from":"2026-08-10T00:00:00Z","to":"2026-08-17T00:00:00Z"}}
                """));

        assertTrue(text.contains("168h"), text);
        assertTrue(text.contains("24h"), text);
    }

    /**
     * A half-open range looks scoped and is not: {@code from} with no {@code to} means "everything since",
     * which grows without bound. Reading the open end as "now" would make the same call allowed or refused
     * depending on the server clock, so the rule stays decidable from the arguments the caller can see.
     */
    @Test
    void listSpans_payloadRequestWithAnOpenEndedRangeIsRefused() throws Exception {
        String text = errorText(
                callTool("list_spans", "{\"fields\":[\"payload\"],\"range\":{\"from\":\"2026-08-17T00:00:00Z\"}}"));

        assertTrue(text.contains("open at one end"), text);
    }

    /**
     * A payload request whose window cannot be measured says which bound is unreadable. The query layer never
     * parses these bounds — it binds the string and casts in SQL — so this rule is the first thing that has to
     * read them, and "invalid range" alone would leave the caller guessing which end it meant.
     */
    @Test
    void listSpans_payloadRequestWithAnUnparseableBoundNamesTheBound() throws Exception {
        String text = errorText(callTool(
                "list_spans", "{\"fields\":[\"payload\"],\"range\":{\"from\":\"yesterday\",\"to\":\"today\"}}"));

        assertTrue(text.contains("range.from"), text);
    }

    @Test
    void listSpans_unknownFieldsEntryIsAToolErrorRatherThanIgnored() throws Exception {
        String text = errorText(callTool("list_spans", "{\"trace_id\":\"t-1\",\"fields\":[\"attributes\"]}"));

        assertTrue(text.contains("attributes"), text);
        assertTrue(text.contains("payload"), text);
    }

    /**
     * Rows come back in the order the RETRIEVAL chose, not the order the span table returned them. For
     * semantic mode that order is the answer — it is the cosine ranking — and SQL has no inherent order over
     * an id set, so a hydration that rendered rows as they arrived would silently re-sort a similarity search.
     */
    @Test
    void listSpans_rowsFollowTheRetrievalRankingNotTheHydrationOrder() throws Exception {
        when(queries.search(eq(PROJECT_ID), any()))
                .thenReturn(searchPage(null, key("t-1", "s-1"), key("t-2", "s-2"), key("t-3", "s-3")));
        // The database answered in a different order, as it is entitled to.
        when(spans.listByKeys(eq(PROJECT_ID), any()))
                .thenReturn(List.of(span("t-3", "s-3"), span("t-1", "s-1"), span("t-2", "s-2")));

        JsonNode rows = structured(callTool("list_spans", "{\"mode\":\"semantic\",\"q\":\"angry customer\"}"))
                .get("spans");

        assertEquals("s-1", rows.get(0).get("span_id").asText());
        assertEquals("s-2", rows.get(1).get("span_id").asText());
        assertEquals("s-3", rows.get(2).get("span_id").asText());
    }

    /**
     * A span the retention sweep removed between the search and the hydration is dropped, not rendered from
     * the search row's few projected columns. A half-populated row would read as a span with no model, no
     * status and no preview — an invention — where a shorter page is simply the truth.
     */
    @Test
    void listSpans_aSpanThatVanishedBetweenTheTwoReadsIsDropped() throws Exception {
        when(queries.search(eq(PROJECT_ID), any())).thenReturn(searchPage(null, key("t-1", "s-1"), key("t-1", "gone")));
        when(spans.listByKeys(eq(PROJECT_ID), any())).thenReturn(List.of(span("t-1", "s-1")));

        JsonNode rows = structured(callTool("list_spans", "{}")).get("spans");

        assertEquals(1, rows.size());
        assertEquals("s-1", rows.get(0).get("span_id").asText());
    }

    /** A query-layer rejection (bad mode, no vector index) is a clean tool error, not a {@code -32603}. */
    @Test
    void listSpans_queryLayerRejectionIsACleanToolError() throws Exception {
        when(queries.search(eq(PROJECT_ID), any()))
                .thenThrow(new TessaryException(QueryError.UNKNOWN_SEARCH_MODE, "fuzzy"));

        String text = errorText(callTool("list_spans", "{\"mode\":\"fuzzy\"}"));

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

        JsonNode body = structured(callTool("list_sessions", "{\"limit\":500}"));

        // The service is handed an already-clamped page size: the cap is the tool's policy, not the service's.
        verify(sessions).page(PROJECT_ID, 100, null, false);
        assertEquals("sess-1", body.get("sessions").get(0).get("id").asText());
        assertEquals("user-9", body.get("sessions").get(0).get("user_id").asText());
        assertEquals("cursor-2", body.get("next_cursor").asText());
    }

    @Test
    void listSessions_passesTheCursorThroughUntouched() throws Exception {
        callTool("list_sessions", "{\"cursor\":\"opaque-token\"}");
        verify(sessions).page(PROJECT_ID, 50, "opaque-token", false);
    }

    /**
     * The detail is rendered exactly as {@link SessionReadService} assembled it, and the two honesty devices
     * survive the wire: {@code unsettled_traces} says how many addends of the totals are still moving, and
     * {@code traces_truncated} says the trace list is not all of them. A surface that drops either reports a
     * lower bound as a final figure.
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

        JsonNode body = structured(callTool("get_session", "{\"id\":\"sess-1\"}"));

        verify(sessions).detail(PROJECT_ID, "sess-1");
        assertEquals("sess-1", body.get("id").asText());
        assertEquals(4, body.get("trace_count").asInt());
        assertEquals(1, body.get("unsettled_traces").asInt(), "one addend is still moving, so the totals are a floor");
        assertEquals(2, body.get("unpriced_spans").asInt());
        assertTrue(body.get("traces_truncated").asBoolean());
        assertEquals("t-1", body.get("traces").get(0).get("id").asText());
        // The session's traces are the same rollup rows list_traces returns — previews, never payloads.
        assertEquals(
                "user: this is broken again",
                body.get("traces").get(0).get("input_preview").asText());
        assertNull(body.get("traces").get(0).get("input"));
    }

    @Test
    void getSession_unknownIdIsACleanToolErrorNotAn32603() throws Exception {
        when(sessions.detail(eq(PROJECT_ID), any())).thenReturn(Optional.empty());

        String text = errorText(callTool("get_session", "{\"id\":\"nope\"}"));

        assertTrue(text.contains("session not found"), text);
    }

    @Test
    void getSession_missingIdIsACleanToolError() throws Exception {
        String text = errorText(callTool("get_session", "{}"));
        assertTrue(text.contains("id"), text);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static SpanKey key(String traceId, String spanId) {
        return new SpanKey(traceId, spanId);
    }

    /**
     * A search page over the spans dataset, shaped as {@link QueryRepository} returns it: the composite handle
     * plus the projection, whose {@code trace_id} / {@code span_id} are what the hydration reads.
     */
    private static QueryRepository.SearchPage searchPage(@Nullable String nextCursor, SpanKey... keys) {
        List<QueryRepository.SearchRow> rows = new ArrayList<>(keys.length);
        for (SpanKey k : keys) {
            rows.add(new QueryRepository.SearchRow(
                    k.handle(),
                    "2026-08-17T10:00:00Z",
                    Map.of("trace_id", k.traceId(), "span_id", k.spanId(), "kind", "llm")));
        }
        if (keys.length == 0) {
            rows.add(new QueryRepository.SearchRow(
                    "t-1:s-1", "2026-08-17T10:00:00Z", Map.of("trace_id", "t-1", "span_id", "s-1")));
        }
        return new QueryRepository.SearchPage(List.copyOf(rows), nextCursor);
    }

    private static SpanRow span(String traceId, String spanId) {
        return new SpanRow(
                PROJECT_ID,
                traceId,
                spanId,
                null, // parentSpanId — a root
                traceId + "." + spanId,
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
                "2026-08-17T09:59:59Z",
                "2026-08-17T10:00:00Z",
                1000L,
                120L,
                "claude-sonnet-5",
                "anthropic/claude-sonnet-5",
                100L,
                20L,
                null,
                null,
                null,
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
                "2026-08-17T10:00:00Z",
                false,
                1, // depth (generated)
                120L, // totalTokens (generated)
                "0.000600000000", // totalCost (generated)
                "2026-08-17T10:00:01Z");
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
                "2026-08-17T10:00:00Z");
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
        assertEquals(Boolean.FALSE, result.get("isError"), String.valueOf(result.get("content")));
        return mapper.valueToTree(Objects.requireNonNull(result.get("structuredContent")));
    }

    private static String errorText(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("isError"), "expected isError=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        return Objects.requireNonNull(content.get(0).get("text")).toString();
    }

    private List<String> toolNames() {
        JsonNode tools = listedTools();
        List<String> names = new ArrayList<>();
        for (JsonNode t : tools) names.add(t.get("name").asText());
        return names;
    }

    private JsonNode schemaOf(String toolName) {
        for (JsonNode t : listedTools()) {
            if (toolName.equals(t.get("name").asText())) return t.get("inputSchema");
        }
        throw new AssertionError("tool not registered: " + toolName);
    }

    private JsonNode listedTools() {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        return mapper.valueToTree(Objects.requireNonNull(result.get("tools")));
    }
}
