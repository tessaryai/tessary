// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.query.QueryDataset;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import ai.tessary.query.QueryRepository;
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
 * Unit-level behaviour of the aggregation-first query MCP tools: the four tools delegate to
 * {@link QueryService} scoped to the token's project, shape results like the HTTP Query API, and turn
 * {@link QueryService}'s {@link TessaryException} (bad dataset / range / search mode) into a clean tool
 * error rather than a {@code -32603} internal error. The {@link QueryService} is mocked — correctness
 * of the query SQL itself is covered by {@code QueryApiIntegrationTest}; this test pins the MCP wrapper
 * contract (project scoping, arg mapping, view shaping, error mapping).
 */
class McpQueryToolsTest {

    private static final String PROJECT_ID = "proj-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private QueryService queryService;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        this.queryService = mock(QueryService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, "org-1", "proj", "Proj", null, "2026-06-13T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        // The query tools never use these, but the registry needs them to register the other tools.
        PipelineService pipeline = mock(PipelineService.class);
        SpanRepository spans = mock(SpanRepository.class);
        SpanPayloadRepository payloads = mock(SpanPayloadRepository.class);
        TraceV2Repository traces = mock(TraceV2Repository.class);
        FindingService behaviorDrift = mock(FindingService.class);
        var registry = new McpToolRegistry(
                pipeline,
                projects,
                queryService,
                spans,
                payloads,
                traces,
                mock(SessionReadService.class),
                behaviorDrift,
                mock(CaseService.class));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    /** A project-scoped MCP-token context, as AuthFilter mints for an {@code tsy_}. */
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

    /**
     * The {@code structuredContent} payload is the typed {@code *View} record; re-serialize it through
     * Jackson to assert the on-the-wire JSON an MCP client actually sees (snake_case shape parity with
     * the HTTP Query API).
     */
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

    // ---- registration --------------------------------------------------------------------------

    /**
     * Every dataset {@link QueryDataset} declares must be reachable, and the enum must be DERIVED from it
     * rather than restated.
     *
     * <p>This is the regression this test exists for. The enum was a hand-written three-element list shared by
     * all four tools; {@code QueryDataset} grew {@code metric_rollups} (the pre-aggregated usage/cost rollups,
     * served by {@code QueryService} and exposed over REST) and the literal here did not move. Because the
     * schema publishes the list as an {@code enum} beside {@code additionalProperties: false}, no agent could
     * even attempt it — "what did this project spend per day" was unanswerable over MCP with the rows sitting
     * in the table. Asserting containment against a hard-coded name would reproduce exactly the bug, so this
     * walks the enum: a fifth dataset added to {@code QueryDataset} passes with no edit here, and one dropped
     * from the schema fails.
     *
     * <p>{@code query_search} is the deliberate exception and gets its own test below: its enum is narrower
     * than "every dataset" on two different grounds and asserting both here would blur them.
     */
    @Test
    void datasetEnumIsDerivedFromQueryDataset() throws Exception {
        for (String toolName : List.of("query_count", "query_timeseries", "query_facets")) {
            List<String> values = datasetEnumOf(toolName);
            for (QueryDataset d : QueryDataset.values()) {
                assertTrue(
                        values.contains(d.wireName()),
                        toolName + " must advertise every queryable dataset; missing " + d.wireName() + " from "
                                + values);
            }
            assertEquals(QueryDataset.values().length, values.size(), toolName + " advertises exactly them: " + values);
        }
    }

    /**
     * {@code query_search}'s enum is narrowed on two independent grounds, and the distinction matters because
     * only one of them is a capability.
     *
     * <p>{@code metric_rollups} is out because it cannot be searched at all — {@link
     * QueryDataset#supportsSearch()} is false, a rollup row has no text to match and no page to keyset. {@code
     * spans} is out by decision: {@code list_spans} searches the same rows with more filters, and
     * two tools answering one question means the model picks between them arbitrarily. So the expectation is
     * derived from the enum rather than hard-coded — a fifth searchable dataset reaches {@code query_search}
     * with no edit here — and the spans exclusion is then pinned on its own, because it is the one the next
     * reader will mistake for a bug.
     *
     * <p>What the service accepts is deliberately NOT asserted here: {@code QueryService} still searches spans
     * for REST. This is a schema narrowing only.
     */
    @Test
    void searchDatasetEnumExcludesSpansAndUnsearchableDatasets() throws Exception {
        List<String> searchable = datasetEnumOf("query_search");
        for (QueryDataset d : QueryDataset.values()) {
            boolean advertised = d.supportsSearch() && d != QueryDataset.SPANS;
            assertEquals(
                    advertised,
                    searchable.contains(d.wireName()),
                    "query_search should advertise " + d.wireName() + " iff it is searchable and not spans;"
                            + " enum was " + searchable);
        }
        assertFalse(searchable.contains("spans"), "spans search lives on list_spans now: " + searchable);
        assertFalse(searchable.contains("metric_rollups"), "a rollup row has no text to search: " + searchable);
        assertEquals(List.of("tool_calls", "classifier_events"), searchable, "the whole narrowed contract");
    }

    /** The concrete case the above generalises: spans is searchable, and the tool for it is not query_search. */
    @Test
    void describeDatasetPointsSpansSearchAtListSpans() throws Exception {
        JsonNode described = structured(callTool("describe_dataset", "{\"dataset\":\"spans\"}"))
                .get("datasets")
                .get(0);

        assertTrue(described.get("searchable").asBoolean(), "the dataset does support search");
        assertEquals("list_spans", described.get("searched_by").asText());
    }

    // ---- describe_dataset ----------------------------------------------------------------------

    /**
     * {@code describe_dataset} must describe every dataset {@link QueryDataset} declares, and every field of
     * every entry must equal what the enum's own accessors say.
     *
     * <p>Written by walking the enum on purpose, for the same reason the tool exists. The four query tools
     * used to recite each dataset's facet/filter fields in their descriptions, and the recitation drifted from
     * the enum — a hand-written expectation here would be a fourth copy of the same vocabulary and would drift
     * the same way, passing while the tool lied. Walking means a fifth dataset added to {@code QueryDataset}
     * is covered with no edit to this test, and a hand-written list smuggled into the handler fails it.
     */
    @Test
    void describeDatasetCoversEveryQueryDatasetDerivedFromTheEnum() throws Exception {
        JsonNode datasets = structured(callTool("describe_dataset", "{}")).get("datasets");
        assertNotNull(datasets, "describe_dataset returns a datasets array");
        assertEquals(QueryDataset.values().length, datasets.size(), "one entry per dataset: " + datasets);

        for (QueryDataset d : QueryDataset.values()) {
            JsonNode entry = null;
            for (JsonNode e : datasets) {
                if (d.wireName().equals(e.get("dataset").asText())) entry = e;
            }
            assertNotNull(entry, "describe_dataset must describe " + d.wireName() + "; got " + datasets);
            JsonNode described = Objects.requireNonNull(entry);
            assertEquals(List.copyOf(d.facetFields()), stringsOf(described.get("facet_fields")), d.wireName());
            assertEquals(List.copyOf(d.filterFields()), stringsOf(described.get("filter_fields")), d.wireName());
            assertEquals(d.supportsSearch(), described.get("searchable").asBoolean(), d.wireName());
            assertEquals(d.timeColumn(), described.get("time_column").asText(), d.wireName());
            if (d.measureColumn() == null) {
                assertTrue(described.get("measure").isNull(), d.wireName() + " counts rows, so measure is null");
            } else {
                assertEquals(d.measureColumn(), described.get("measure").asText(), d.wireName());
            }
        }
    }

    /**
     * The one hand-written expectation, and it is the regression: {@code call_site_id} is facetable on
     * {@code spans}. Faceting it under an {@code environment_id} filter is the coverage read ("which call
     * sites have telemetry, and how much") that grader synthesis grounds on, so an agent that cannot learn
     * the field exists cannot ask the question. Pinned by name here precisely because the derived test above
     * would still pass if the field disappeared from both the enum and the tool together.
     */
    @Test
    void describeDatasetNamesCallSiteIdFacetableOnSpans() throws Exception {
        JsonNode datasets = structured(callTool("describe_dataset", "{\"dataset\":\"spans\"}"))
                .get("datasets");
        assertEquals(1, datasets.size(), "asking for one dataset describes one: " + datasets);
        JsonNode spans = datasets.get(0);
        assertEquals("spans", spans.get("dataset").asText());
        assertTrue(
                stringsOf(spans.get("facet_fields")).contains("call_site_id"),
                "spans must advertise call_site_id as facetable: " + spans.get("facet_fields"));
    }

    /**
     * The regression decisions 8 and 8b fix: {@code spans} and {@code tool_calls} range, page and bucket
     * on {@code started_at} (the producer's own timing), and {@code classifier_events} on
     * {@code subject_started_at} (migration {@code 0012}, the span or trace it judged) — a late-arriving
     * backfill no longer lands in the wrong bucket on any of the three.
     */
    @Test
    void describeDatasetReportsStartedAtAsTheEventClockForSpansAndToolCalls() throws Exception {
        JsonNode datasets = structured(callTool("describe_dataset", "{}")).get("datasets");
        Map<String, String> timeColumnByDataset = new java.util.HashMap<>();
        for (JsonNode d : datasets) {
            timeColumnByDataset.put(
                    d.get("dataset").asText(), d.get("time_column").asText());
        }
        assertEquals("started_at", timeColumnByDataset.get("spans"));
        assertEquals("started_at", timeColumnByDataset.get("tool_calls"));
        assertEquals("subject_started_at", timeColumnByDataset.get("classifier_events"));
        assertEquals("bucket_start", timeColumnByDataset.get("metric_rollups"));
    }

    private static List<String> stringsOf(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode v : Objects.requireNonNull(array)) out.add(v.asText());
        return out;
    }

    private JsonNode inputSchemaOf(String toolName) {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        JsonNode tools = mapper.valueToTree(Objects.requireNonNull(result.get("tools")));
        for (JsonNode t : tools) {
            if (toolName.equals(t.get("name").asText())) return t.get("inputSchema");
        }
        throw new AssertionError(toolName + " is not listed");
    }

    private List<String> datasetEnumOf(String toolName) {
        JsonNode datasets =
                inputSchemaOf(toolName).get("properties").get("dataset").get("enum");
        assertNotNull(datasets, toolName + " advertises a dataset enum");
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode v : Objects.requireNonNull(datasets)) values.add(v.asText());
        return values;
    }

    // ---- happy paths (project-scoped delegation + view shaping) --------------------------------

    @Test
    void queryCount_delegatesScopedAndShapesView() throws Exception {
        when(queryService.count(eq(PROJECT_ID), any(CountRequest.class))).thenReturn(7L);
        JsonNode structured = structured(
                callTool(
                        "query_count",
                        "{\"dataset\":\"spans\",\"range\":{\"from\":\"2026-06-10T00:00:00Z\"},\"filters\":{\"kind\":\"tool\"}}"));
        assertEquals(7, structured.get("count").asLong());
    }

    @Test
    void queryTimeseries_shapesBuckets() throws Exception {
        when(queryService.timeseries(eq(PROJECT_ID), any(TimeseriesRequest.class)))
                .thenReturn(List.of(new QueryRepository.Bucket("2026-06-10T00:00:00Z", 3L)));
        JsonNode structured = structured(
                callTool(
                        "query_timeseries",
                        "{\"dataset\":\"spans\",\"interval\":\"hour\",\"range\":{\"from\":\"2026-06-10T00:00:00Z\",\"to\":\"2026-06-11T00:00:00Z\"}}"));
        JsonNode buckets = structured.get("buckets");
        assertEquals(1, buckets.size());
        // snake_case wire parity with the HTTP Query API (TimeseriesBucket.bucket_start).
        assertEquals("2026-06-10T00:00:00Z", buckets.get(0).get("bucket_start").asText());
        assertEquals(3, buckets.get(0).get("count").asLong());
    }

    @Test
    void queryFacets_passesTopNAndShapesView() throws Exception {
        when(queryService.facets(eq(PROJECT_ID), any(FacetsRequest.class)))
                .thenReturn(List.of(new QueryRepository.Facet("tool", 5L), new QueryRepository.Facet("llm", 2L)));
        JsonNode structured =
                structured(callTool("query_facets", "{\"dataset\":\"spans\",\"field\":\"kind\",\"top_n\":10}"));
        assertEquals("kind", structured.get("field").asText());
        JsonNode facets = structured.get("facets");
        assertEquals(2, facets.size());
        assertEquals("tool", facets.get(0).get("value").asText());
    }

    @Test
    void querySearch_shapesRowsAndCursor() throws Exception {
        var page = new QueryRepository.SearchPage(
                List.of(new QueryRepository.SearchRow(
                        "obs-1", "2026-06-10T00:00:00Z", "2026-06-10T00:00:00Z", Map.of("name", "chat"))),
                "e1|2026-06-10T00:00:00Z|obs-1");
        when(queryService.search(eq(PROJECT_ID), any(SearchRequest.class))).thenReturn(page);
        JsonNode structured =
                structured(callTool("query_search", "{\"dataset\":\"tool_calls\",\"q\":\"hello\",\"limit\":1}"));
        JsonNode rows = structured.get("rows");
        assertEquals(1, rows.size());
        assertEquals("obs-1", rows.get(0).get("id").asText());
        // snake_case wire parity (SearchView.next_cursor); the versioned e1| cursor is opaque and passes
        // through the MCP wrapper untouched.
        assertEquals(
                "e1|2026-06-10T00:00:00Z|obs-1", structured.get("next_cursor").asText());
    }

    // ---- error mapping: TessaryException -> clean tool error, never -32603 -----------------------

    @Test
    void badDatasetYieldsCleanToolError_not32603() throws Exception {
        when(queryService.count(eq(PROJECT_ID), any(CountRequest.class)))
                .thenThrow(new TessaryException(QueryError.UNKNOWN_DATASET, "not_a_dataset"));
        String text = errorText(callTool("query_count", "{\"dataset\":\"not_a_dataset\"}"));
        assertTrue(text.contains("not_a_dataset"), text);
    }

    @Test
    void timeseriesMissingRangeYieldsCleanToolError() throws Exception {
        when(queryService.timeseries(eq(PROJECT_ID), any(TimeseriesRequest.class)))
                .thenThrow(new TessaryException(QueryError.INVALID_RANGE));
        String text =
                errorText(callTool("query_timeseries", "{\"dataset\":\"spans\",\"interval\":\"hour\",\"range\":{}}"));
        assertTrue(text.contains("range"), text);
    }

    @Test
    void searchSemanticModeYieldsCleanToolError() throws Exception {
        // The service rejects any mode other than 'keyword' with UNKNOWN_SEARCH_MODE (production behavior,
        // since QueryService no longer has a semantic branch to fall into) — the mock here
        // stands in for that, and the tool surface still turns it into a clean error mentioning the mode.
        when(queryService.search(eq(PROJECT_ID), any(SearchRequest.class)))
                .thenThrow(new TessaryException(QueryError.UNKNOWN_SEARCH_MODE, "semantic"));
        String text = errorText(callTool("query_search", "{\"dataset\":\"tool_calls\",\"mode\":\"semantic\"}"));
        assertTrue(text.contains("semantic"), text);
    }

    // ---- arg validation ------------------------------------------------------------------------

    @Test
    void missingRequiredDatasetIsToolError() throws Exception {
        String text = errorText(callTool("query_count", "{}"));
        assertTrue(text.contains("dataset"), text);
    }

    @Test
    void nonObjectFiltersIsToolError() throws Exception {
        String text = errorText(callTool("query_count", "{\"dataset\":\"spans\",\"filters\":\"oops\"}"));
        assertTrue(text.contains("filters"), text);
    }

    /**
     * Every argument-shape check names the argument and the shape it wanted. A wrongly-typed argument that
     * slipped through would reach the service as a {@code ClassCastException} (a -32603 the agent cannot act on)
     * or, worse, be silently dropped so the call answers a different question than the one asked.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "query_count | {\"dataset\":5} | argument dataset must be a string",
                "query_facets | {\"dataset\":\"spans\",\"field\":\"kind\",\"top_n\":\"10\"}"
                        + " | argument top_n must be an integer",
                "query_count | {\"dataset\":\"spans\",\"range\":\"yesterday\"}"
                        + " | argument range must be an object with optional from/to",
                "query_count | {\"dataset\":\"spans\",\"range\":{\"from\":5}} | range.from must be a string",
                "query_count | {\"dataset\":\"spans\",\"filters\":{\"kind\":5}}"
                        + " | filters entries must be string field->value pairs",
                "get_finding_evidence | {\"finding_id\":\"f-1\",\"count_only\":\"yes\"}"
                        + " | argument count_only must be a boolean",
                "get_trace | {\"trace_id\":\"t-1\",\"fields\":\"payload\"}"
                        + " | argument fields must be an array of strings, e.g. [\"payload\"]",
            })
    void aWronglyTypedArgumentIsAToolErrorNamingIt(String tool, String argsJson, String expected) throws Exception {
        assertEquals(expected, errorText(callTool(tool, argsJson)));
    }

    /**
     * A JSON number with a fraction ({@code 10.0}, which a model often emits) is accepted as the integer it
     * denotes rather than refused or dropped: the facet read still asks for the top ten.
     */
    @Test
    void aNonIntegerJsonNumberIsReadAsItsIntegerValue() throws Exception {
        when(queryService.facets(PROJECT_ID, new FacetsRequest("spans", "kind", null, Map.of(), 10)))
                .thenReturn(List.of(new QueryRepository.Facet("tool", 5L)));

        JsonNode structured =
                structured(callTool("query_facets", "{\"dataset\":\"spans\",\"field\":\"kind\",\"top_n\":10.0}"));

        assertEquals(1, structured.get("facets").size(), "top_n=10.0 must reach the service as 10");
    }

    /**
     * {@code describe_dataset} names an unknown dataset as a tool error carrying the query API's own message,
     * not a -32603, so the agent can correct the name.
     */
    @Test
    void describeDatasetOfAnUnknownDatasetIsACleanToolError() throws Exception {
        String expected = new TessaryException(QueryError.UNKNOWN_DATASET, "nope").getMessage();

        assertEquals(expected, errorText(callTool("describe_dataset", "{\"dataset\":\"nope\"}")));
    }

    /** A facet over a field the dataset does not allow is the service's message as a tool error, not a -32603. */
    @Test
    void facetsOverAnUnknownFieldIsACleanToolError() throws Exception {
        TessaryException refused = new TessaryException(QueryError.UNKNOWN_FIELD, "input", "spans");
        when(queryService.facets(eq(PROJECT_ID), any(FacetsRequest.class))).thenThrow(refused);

        String text = errorText(callTool("query_facets", "{\"dataset\":\"spans\",\"field\":\"input\"}"));

        assertEquals(refused.getMessage(), text);
    }
}
