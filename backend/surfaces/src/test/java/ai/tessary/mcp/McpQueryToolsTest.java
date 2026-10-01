// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.PROJECT_ID;
import static ai.tessary.mcp.McpToolHarness.errorText;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.structured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryDataset;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import ai.tessary.query.QueryRepository;
import ai.tessary.query.QueryService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The query MCP tools at the wrapper: project scoping, argument mapping, HTTP-parity view shaping, and {@link
 * TessaryException} as a clean tool error rather than {@code -32603}. The SQL is covered by {@code
 * QueryApiIntegrationTest}.
 */
class McpQueryToolsTest {

    private QueryService queryService;
    private McpToolHarness mcp;

    @BeforeEach
    void setup() {
        this.queryService = mock(QueryService.class);
        this.mcp = registryWith().query(queryService).build();
    }

    // ---- registration --------------------------------------------------------------------------

    /**
     * The regression this test exists for: the dataset enum was a hand-written list, {@code QueryDataset} grew {@code
     * metric_rollups}, and the schema's {@code enum} beside {@code additionalProperties: false} made per-day spend
     * unanswerable over MCP. Walking the enum means a new dataset passes with no edit here and a dropped one fails.
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
     * {@code query_search} excludes {@code metric_rollups} because it cannot be searched, and {@code spans} by
     * decision, because {@code list_spans} searches the same rows with more filters. The spans exclusion is pinned on
     * its own because the next reader will mistake it for a bug. The service still searches spans for REST.
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

    @Test
    void describeDatasetPointsSpansSearchAtListSpans() throws Exception {
        JsonNode described = structured(mcp.callTool("describe_dataset", "{\"dataset\":\"spans\"}"))
                .get("datasets")
                .get(0);

        assertTrue(described.get("searchable").asBoolean(), "the dataset does support search");
        assertEquals("list_spans", described.get("searched_by").asText());
    }

    // ---- describe_dataset ----------------------------------------------------------------------

    /**
     * Every dataset and every field must equal the enum's accessors. Walked, not hand-written: the tools' recited
     * field lists once drifted from the enum, and a hand-written expectation would drift the same way.
     */
    @Test
    void describeDatasetCoversEveryQueryDatasetDerivedFromTheEnum() throws Exception {
        JsonNode datasets = structured(mcp.callTool("describe_dataset", "{}")).get("datasets");
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
     * The regression: {@code call_site_id} is facetable on {@code spans}, the coverage read synthesis grounds on.
     * Pinned by name because the derived test passes if the field vanishes from both enum and tool.
     */
    @Test
    void describeDatasetNamesCallSiteIdFacetableOnSpans() throws Exception {
        JsonNode datasets = structured(mcp.callTool("describe_dataset", "{\"dataset\":\"spans\"}"))
                .get("datasets");
        assertEquals(1, datasets.size(), "asking for one dataset describes one: " + datasets);
        JsonNode spans = datasets.get(0);
        assertEquals("spans", spans.get("dataset").asText());
        assertTrue(
                stringsOf(spans.get("facet_fields")).contains("call_site_id"),
                "spans must advertise call_site_id as facetable: " + spans.get("facet_fields"));
    }

    /**
     * Decisions 8 and 8b: {@code spans} and {@code tool_calls} use {@code started_at}, {@code classifier_events} uses
     * {@code subject_started_at} (0012), so a late backfill lands in the right bucket.
     */
    @Test
    void describeDatasetReportsStartedAtAsTheEventClockForSpansAndToolCalls() throws Exception {
        JsonNode datasets = structured(mcp.callTool("describe_dataset", "{}")).get("datasets");
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

    private List<String> datasetEnumOf(String toolName) {
        JsonNode datasets =
                mcp.schemaOf(toolName).get("properties").get("dataset").get("enum");
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
                mcp.callTool(
                        "query_count",
                        "{\"dataset\":\"spans\",\"range\":{\"from\":\"2026-06-10T00:00:00Z\"},\"filters\":{\"kind\":\"tool\"}}"));
        assertEquals(7, structured.get("count").asLong());
    }

    @Test
    void queryTimeseries_shapesBuckets() throws Exception {
        when(queryService.timeseries(eq(PROJECT_ID), any(TimeseriesRequest.class)))
                .thenReturn(List.of(new QueryRepository.Bucket("2026-06-10T00:00:00Z", 3L)));
        JsonNode structured = structured(
                mcp.callTool(
                        "query_timeseries",
                        "{\"dataset\":\"spans\",\"interval\":\"hour\",\"range\":{\"from\":\"2026-06-10T00:00:00Z\",\"to\":\"2026-06-11T00:00:00Z\"}}"));
        JsonNode buckets = structured.get("buckets");
        assertEquals(1, buckets.size());
        assertEquals("2026-06-10T00:00:00Z", buckets.get(0).get("bucket_start").asText());
        assertEquals(3, buckets.get(0).get("count").asLong());
    }

    @Test
    void queryFacets_passesTopNAndShapesView() throws Exception {
        when(queryService.facets(eq(PROJECT_ID), any(FacetsRequest.class)))
                .thenReturn(List.of(new QueryRepository.Facet("tool", 5L), new QueryRepository.Facet("llm", 2L)));
        JsonNode structured =
                structured(mcp.callTool("query_facets", "{\"dataset\":\"spans\",\"field\":\"kind\",\"top_n\":10}"));
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
                structured(mcp.callTool("query_search", "{\"dataset\":\"tool_calls\",\"q\":\"hello\",\"limit\":1}"));
        JsonNode rows = structured.get("rows");
        assertEquals(1, rows.size());
        assertEquals("obs-1", rows.get(0).get("id").asText());
        // The versioned e1| cursor passes through untouched.
        assertEquals(
                "e1|2026-06-10T00:00:00Z|obs-1", structured.get("next_cursor").asText());
    }

    // ---- error mapping: TessaryException -> clean tool error, never -32603 -----------------------

    @Test
    void badDatasetYieldsCleanToolError_not32603() throws Exception {
        when(queryService.count(eq(PROJECT_ID), any(CountRequest.class)))
                .thenThrow(new TessaryException(QueryError.UNKNOWN_DATASET, "not_a_dataset"));
        String text = errorText(mcp.callTool("query_count", "{\"dataset\":\"not_a_dataset\"}"));
        assertTrue(text.contains("not_a_dataset"), text);
    }

    @Test
    void timeseriesMissingRangeYieldsCleanToolError() throws Exception {
        when(queryService.timeseries(eq(PROJECT_ID), any(TimeseriesRequest.class)))
                .thenThrow(new TessaryException(QueryError.INVALID_RANGE));
        String text = errorText(
                mcp.callTool("query_timeseries", "{\"dataset\":\"spans\",\"interval\":\"hour\",\"range\":{}}"));
        assertTrue(text.contains("range"), text);
    }

    @Test
    void searchSemanticModeYieldsCleanToolError() throws Exception {
        // The service rejects any mode but 'keyword'; the tool still returns a clean error naming it.
        when(queryService.search(eq(PROJECT_ID), any(SearchRequest.class)))
                .thenThrow(new TessaryException(QueryError.UNKNOWN_SEARCH_MODE, "semantic"));
        String text = errorText(mcp.callTool("query_search", "{\"dataset\":\"tool_calls\",\"mode\":\"semantic\"}"));
        assertTrue(text.contains("semantic"), text);
    }

    // ---- arg validation ------------------------------------------------------------------------

    @Test
    void missingRequiredDatasetIsToolError() throws Exception {
        String text = errorText(mcp.callTool("query_count", "{}"));
        assertTrue(text.contains("dataset"), text);
    }

    @Test
    void nonObjectFiltersIsToolError() throws Exception {
        String text = errorText(mcp.callTool("query_count", "{\"dataset\":\"spans\",\"filters\":\"oops\"}"));
        assertTrue(text.contains("filters"), text);
    }

    /**
     * Each shape check names the argument and the shape wanted; a mistyped argument would otherwise surface as a
     * {@code ClassCastException} or be dropped, answering a different question.
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
        assertEquals(expected, errorText(mcp.callTool(tool, argsJson)));
    }

    /** A fractional JSON number such as {@code 10.0}, common from models, reads as its integer value. */
    @Test
    void aNonIntegerJsonNumberIsReadAsItsIntegerValue() throws Exception {
        when(queryService.facets(PROJECT_ID, new FacetsRequest("spans", "kind", null, Map.of(), 10)))
                .thenReturn(List.of(new QueryRepository.Facet("tool", 5L)));

        JsonNode structured =
                structured(mcp.callTool("query_facets", "{\"dataset\":\"spans\",\"field\":\"kind\",\"top_n\":10.0}"));

        assertEquals(1, structured.get("facets").size(), "top_n=10.0 must reach the service as 10");
    }

    /** An unknown dataset is a tool error carrying the query API's message, so the agent can correct it. */
    @Test
    void describeDatasetOfAnUnknownDatasetIsACleanToolError() throws Exception {
        String expected = new TessaryException(QueryError.UNKNOWN_DATASET, "nope").getMessage();

        assertEquals(expected, errorText(mcp.callTool("describe_dataset", "{\"dataset\":\"nope\"}")));
    }

    /** An unallowed facet field is the service's message as a tool error. */
    @Test
    void facetsOverAnUnknownFieldIsACleanToolError() throws Exception {
        TessaryException refused = new TessaryException(QueryError.UNKNOWN_FIELD, "input", "spans");
        when(queryService.facets(eq(PROJECT_ID), any(FacetsRequest.class))).thenThrow(refused);

        String text = errorText(mcp.callTool("query_facets", "{\"dataset\":\"spans\",\"field\":\"input\"}"));

        assertEquals(refused.getMessage(), text);
    }
}
