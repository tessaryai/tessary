// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalog;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogLimits;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogMeasure;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogView;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryResult;
import ai.tessary.metrics.MetricsQueryDtos.MetricsRow;
import ai.tessary.metrics.MetricsQueryDtos.MetricsTopN;
import ai.tessary.metrics.MetricsQueryDtos.MetricsWindow;
import ai.tessary.metrics.MetricsQueryRepository.Raw;
import ai.tessary.metrics.MetricsQueryRepository.Result;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

/** The service's assembly of raw rows into the wire shape: gap fill, key order, labels, top N and compare. */
class MetricsQueryServiceTest {

    private static final String PID = "proj-1";
    private static final String FROM = "2026-10-11T00:00:00Z";
    private static final String TO = "2026-10-18T00:00:00Z";

    private final MetricsQueryRepository repo = mock(MetricsQueryRepository.class);
    private final MetricsLabelRepository labels = mock(MetricsLabelRepository.class);
    private final MetricsQueryService service =
            new MetricsQueryService(repo, labels, new MetricsGate(3, 10), new MetricsProperties(8000, 3, "64MB"));

    /** The bug: a number tile that carries a top_n block or a fold flag it never asked for. */
    @Test
    @DisplayName("a number query returns one row with its companions and no top N")
    void aNumberQueryReturnsOneRowAndNoTopN() {
        answer(List.of(raw(
                null,
                null,
                1,
                1930,
                0,
                vals("priced_traces", 1702.0, "unsettled_calls", 3.0, "cost_usd", 88.2, "unpriced_calls", 14.0))));

        MetricsQueryResult r = service.query(PID, req("llm_calls", List.of("cost_usd"), null, null, null, null));

        assertEquals(
                List.of("cost_usd", "unpriced_calls", "unsettled_calls", "priced_traces"),
                List.copyOf(r.rows().get(0).values().keySet()),
                "values follow the asked measures, then companions, whatever order the columns came back in");
        assertEquals("llm_calls", r.view());
        assertNull(r.dimension());
        assertEquals(
                List.of(new MetricsRow(
                        null,
                        null,
                        null,
                        1930,
                        vals(
                                "cost_usd",
                                88.2,
                                "unpriced_calls",
                                14.0,
                                "unsettled_calls",
                                3.0,
                                "priced_traces",
                                1702.0))),
                r.rows());
        assertNull(r.topN());
        assertFalse(r.truncated());
        assertNull(r.previousRows());
        verifyNoInteractions(labels);
    }

    /** The bug: a fake zero on an average, which draws an empty day as the best day of the week. */
    @Test
    @DisplayName("missing buckets are zero for counts and null for percentiles and ratios")
    void missingBucketsAreZeroForCountsAndNullForPercentilesAndRatios() {
        answer(List.of(
                raw(
                        at("2026-10-11T00:00:00Z"),
                        null,
                        1,
                        4,
                        0,
                        vals(
                                "latency_p95_ms",
                                800.0,
                                "usd_per_trace",
                                0.5,
                                "latency_n",
                                4.0,
                                "unterminated_traces",
                                0.0,
                                "rootless_traces",
                                0.0,
                                "unpriced_traces",
                                0.0,
                                "unsettled_traces",
                                0.0,
                                "priced_traces",
                                4.0)),
                raw(
                        at("2026-10-13T00:00:00Z"),
                        null,
                        1,
                        2,
                        0,
                        vals(
                                "latency_p95_ms",
                                300.0,
                                "usd_per_trace",
                                0.25,
                                "latency_n",
                                2.0,
                                "unterminated_traces",
                                0.0,
                                "rootless_traces",
                                0.0,
                                "unpriced_traces",
                                0.0,
                                "unsettled_traces",
                                0.0,
                                "priced_traces",
                                2.0))));

        MetricsQueryResult r = service.query(
                PID,
                new MetricsQueryRequest(
                        "traces",
                        List.of("latency_p95_ms", "usd_per_trace"),
                        null,
                        null,
                        "2026-10-11T00:00:00Z",
                        "2026-10-14T00:00:00Z",
                        "day",
                        null,
                        null));

        assertEquals(new MetricsWindow("2026-10-11T00:00:00Z", "2026-10-14T00:00:00Z", "day", 3), r.window());
        assertEquals(
                List.of(
                        new MetricsRow(
                                "2026-10-11T00:00:00Z",
                                null,
                                null,
                                4,
                                vals(
                                        "latency_p95_ms",
                                        800.0,
                                        "usd_per_trace",
                                        0.5,
                                        "latency_n",
                                        4.0,
                                        "unterminated_traces",
                                        0.0,
                                        "rootless_traces",
                                        0.0,
                                        "unpriced_traces",
                                        0.0,
                                        "unsettled_traces",
                                        0.0,
                                        "priced_traces",
                                        4.0)),
                        new MetricsRow(
                                "2026-10-12T00:00:00Z",
                                null,
                                null,
                                0,
                                vals(
                                        "latency_p95_ms",
                                        null,
                                        "usd_per_trace",
                                        null,
                                        "latency_n",
                                        0.0,
                                        "unterminated_traces",
                                        0.0,
                                        "rootless_traces",
                                        0.0,
                                        "unpriced_traces",
                                        0.0,
                                        "unsettled_traces",
                                        0.0,
                                        "priced_traces",
                                        0.0)),
                        new MetricsRow(
                                "2026-10-13T00:00:00Z",
                                null,
                                null,
                                2,
                                vals(
                                        "latency_p95_ms",
                                        300.0,
                                        "usd_per_trace",
                                        0.25,
                                        "latency_n",
                                        2.0,
                                        "unterminated_traces",
                                        0.0,
                                        "rootless_traces",
                                        0.0,
                                        "unpriced_traces",
                                        0.0,
                                        "unsettled_traces",
                                        0.0,
                                        "priced_traces",
                                        2.0))),
                r.rows());
    }

    /** The bug: a bucket of unsettled traces reads blank while an empty bucket reads 0, so the two disagree. */
    @Test
    @DisplayName("a present row whose sum is SQL NULL shows 0 for a zero-gap measure and keeps null for a ratio")
    void aPresentRowWhoseSumIsSqlNullShowsZeroForAZeroGapMeasure() {
        answer(List.of(raw(
                null,
                null,
                1,
                1,
                0,
                vals(
                        "cost_usd", null,
                        "usd_per_trace", null,
                        "unpriced_calls", 0.0,
                        "unsettled_calls", 1.0,
                        "priced_traces", 0.0))));

        MetricsQueryResult r =
                service.query(PID, req("llm_calls", List.of("cost_usd", "usd_per_trace"), null, null, null, null));

        Map<String, Double> values = r.rows().get(0).values();
        assertEquals(0.0, values.get("cost_usd"));
        assertTrue(values.containsKey("usd_per_trace"));
        assertNull(values.get("usd_per_trace"));
        assertEquals(1.0, values.get("unsettled_calls"));
    }

    /** The bug: a line that vanishes on a day its key has no rows, or keys re-ranked per bucket so colors swap. */
    @Test
    @DisplayName("a series with a dimension fills every key in every bucket, in rank order")
    void seriesWithDimensionFillsEveryKeyInEveryBucketInRankOrder() {
        answer(List.of(
                raw(at("2026-10-11T00:00:00Z"), "gpt-4o", 2, 3, 3, vals("calls", 3.0)),
                raw(at("2026-10-12T00:00:00Z"), "claude", 1, 9, 3, vals("calls", 9.0)),
                raw(at("2026-10-12T00:00:00Z"), "gpt-4o", 2, 1, 3, vals("calls", 1.0)),
                raw(at("2026-10-12T00:00:00Z"), "__other__", 3, 2, 3, vals("calls", 2.0))));

        MetricsQueryResult r = service.query(
                PID,
                new MetricsQueryRequest(
                        "llm_calls",
                        List.of("calls"),
                        "model",
                        null,
                        "2026-10-11T00:00:00Z",
                        "2026-10-13T00:00:00Z",
                        "day",
                        2,
                        null));

        assertEquals(
                List.of(
                        new MetricsRow("2026-10-11T00:00:00Z", "claude", "claude", 0, vals("calls", 0.0)),
                        new MetricsRow("2026-10-11T00:00:00Z", "gpt-4o", "gpt-4o", 3, vals("calls", 3.0)),
                        new MetricsRow("2026-10-11T00:00:00Z", "__other__", "Other (1 more)", 0, vals("calls", 0.0)),
                        new MetricsRow("2026-10-12T00:00:00Z", "claude", "claude", 9, vals("calls", 9.0)),
                        new MetricsRow("2026-10-12T00:00:00Z", "gpt-4o", "gpt-4o", 1, vals("calls", 1.0)),
                        new MetricsRow("2026-10-12T00:00:00Z", "__other__", "Other (1 more)", 2, vals("calls", 2.0))),
                r.rows());
        assertEquals(new MetricsTopN(2, 3), r.topN());
        assertTrue(r.truncated());
        verifyNoInteractions(labels);
    }

    /** The bug: a call site shown by its opaque id, the sentinel shown raw, or the fold not saying how many it holds. */
    @Test
    @DisplayName("labels are the use case, Unattributed, Other with its count, or the key")
    void labelsAreUseCaseUnattributedOtherOrTheKey() {
        when(labels.callSiteLabels(eq(PID), any())).thenReturn(Map.of("cs-chat", "Chat support"));
        answer(
                List.of(
                        raw(null, "cs-chat", 1, 5, 12, vals("traces", 5.0)),
                        raw(null, "__unattributed__", 2, 4, 12, vals("traces", 4.0)),
                        raw(null, "cs-raw", 3, 3, 12, vals("traces", 3.0)),
                        raw(null, "__other__", 4, 9, 12, vals("traces", 9.0))),
                List.of(raw(null, "cs-gone", 1, 2, 1, vals("traces", 2.0))));

        MetricsQueryResult r = service.query(
                PID,
                new MetricsQueryRequest(
                        "traces", List.of("traces"), "call_site", null, FROM, TO, null, 3, "previous_window"));

        assertEquals(
                List.of("Chat support", "Unattributed", "cs-raw", "Other (9 more)"),
                r.rows().stream().map(MetricsRow::label).toList());
        assertEquals(
                List.of("cs-gone"),
                requireRows(r.previousRows()).stream().map(MetricsRow::label).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(labels).callSiteLabels(eq(PID), keys.capture());
        assertEquals(
                Set.of("cs-chat", "__unattributed__", "cs-raw", "__other__", "cs-gone"), Set.copyOf(keys.getValue()));
    }

    /** The bug: the previous window shifted by the wrong length, so every delta compares unequal spans of time. */
    @Test
    @DisplayName("compare runs the same query over the window just before")
    void compareRunsTheSameQueryOverTheWindowBefore() {
        answer(
                List.of(raw(null, null, 1, 10, 0, vals("cases", 10.0))),
                List.of(raw(null, null, 1, 7, 0, vals("cases", 7.0))));

        MetricsQueryResult r = service.query(PID, req("cases", List.of("cases"), null, null, null, "previous_window"));

        ArgumentCaptor<MetricsStatement> current = ArgumentCaptor.forClass(MetricsStatement.class);
        ArgumentCaptor<MetricsStatement> previous = ArgumentCaptor.forClass(MetricsStatement.class);
        verify(repo).run(current.capture(), previous.capture(), eq(8000L), eq("64MB"));
        assertEquals(current.getValue().sql(), previous.getValue().sql());
        assertEquals(
                "2026-10-11T00:00:00.000000000Z", current.getValue().params().get("from"));
        assertEquals(
                "2026-10-04T00:00:00.000000000Z", previous.getValue().params().get("from"));
        assertEquals(
                "2026-10-11T00:00:00.000000000Z", previous.getValue().params().get("to"));
        assertEquals(List.of(new MetricsRow(null, null, null, 7, vals("cases", 7.0))), r.previousRows());
        assertEquals(10, r.rows().get(0).n());
    }

    /** The bug: a table that hides that it was cut, so the top five read as the whole project. */
    @Test
    @DisplayName("top N reports how many keys it shows, how many exist, and that it was truncated")
    void topNReportsShownTotalKeysAndTruncated() {
        List<Raw> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) rows.add(raw(null, "m" + i, i, 10 - i, 12, vals("calls", 10.0 - i)));
        rows.add(raw(null, "__other__", 6, 7, 12, vals("calls", 7.0)));
        answer(rows);

        MetricsQueryResult r = service.query(PID, req("llm_calls", List.of("calls"), "model", 5, null, null));

        assertEquals(new MetricsTopN(5, 12), r.topN());
        assertTrue(r.truncated());
        assertEquals("Other (7 more)", r.rows().get(5).label());

        answer(List.of(raw(null, "m1", 1, 3, 2, vals("calls", 3.0)), raw(null, "m2", 2, 1, 2, vals("calls", 1.0))));
        MetricsQueryResult all = service.query(PID, req("llm_calls", List.of("calls"), "model", 5, null, null));
        assertEquals(new MetricsTopN(2, 2), all.topN());
        assertFalse(all.truncated());
    }

    /** The bug: an empty window drawn as five zero-height keys that do not exist. */
    @Test
    @DisplayName("an empty grouped result is empty, not filled")
    void anEmptyGroupedResultIsEmptyNotFilled() {
        answer(List.of());

        MetricsQueryResult table = service.query(PID, req("llm_calls", List.of("calls"), "model", null, null, null));
        assertEquals(List.of(), table.rows());
        assertEquals(new MetricsTopN(0, 0), table.topN());
        assertFalse(table.truncated());

        MetricsQueryResult series = service.query(PID, req("llm_calls", List.of("calls"), "model", null, "day", null));
        assertEquals(List.of(), series.rows());
        assertEquals(7, series.window().buckets());
    }

    /** The bug: a timed-out widget answers 500, or a real fault is disguised as a timeout. */
    @Test
    @DisplayName("a cancelled statement becomes TIMED_OUT and any other failure is rethrown as it was")
    void aCancelledStatementBecomesTimedOutAndAnotherFailureIsRethrown() {
        when(repo.run(any(), any(), anyLong(), anyString()))
                .thenThrow(new QueryTimeoutException(
                        "cancelled", new SQLException("canceling statement due to statement timeout", "57014")));
        TessaryException timedOut = assertThrows(
                TessaryException.class,
                () -> service.query(PID, req("llm_calls", List.of("calls"), null, null, null, null)));
        assertEquals(MetricsError.TIMED_OUT, timedOut.error());

        DataAccessResourceFailureException lost = new DataAccessResourceFailureException("connection lost");
        doThrow(lost).when(repo).run(any(), any(), anyLong(), anyString());
        DataAccessResourceFailureException thrown = assertThrows(
                DataAccessResourceFailureException.class,
                () -> service.query(PID, req("llm_calls", List.of("calls"), null, null, null, null)));
        assertSame(lost, thrown);
    }

    /** The bug: an editor option the engine rejects, or a limit the editor does not know about. */
    @Test
    @DisplayName("the catalog lists every view, dimension, measure, granularity and limit")
    void catalogListsEveryViewDimensionMeasureAndLimit() {
        MetricsCatalog c = service.catalog();

        assertEquals(
                List.of("traces", "llm_calls", "tool_calls", "cases"),
                c.views().stream().map(MetricsCatalogView::name).toList());
        for (MetricsView v : MetricsView.values()) {
            MetricsCatalogView cv = c.views().get(v.ordinal());
            assertEquals(v.label(), cv.label());
            assertEquals(v.description(), cv.description());
            assertEquals(
                    v.dimensions().stream().map(MetricsDimension::wire).toList(),
                    cv.dimensions().stream().map(d -> d.name()).toList());
            assertEquals(
                    v.measures().stream().map(MetricsMeasure::wire).toList(),
                    cv.measures().stream().map(MetricsCatalogMeasure::name).toList());
        }
        MetricsCatalogView llm = c.views().get(1);
        assertEquals(
                new MetricsCatalogMeasure(
                        "cost_usd",
                        "Spend",
                        "usd",
                        "zero",
                        List.of("unpriced_calls", "unsettled_calls", "priced_traces")),
                llm.measures().get(2));
        assertEquals(
                new MetricsCatalogMeasure(
                        "usd_per_trace",
                        "Spend per priced trace",
                        "usd",
                        "none",
                        List.of("unpriced_calls", "unsettled_calls", "priced_traces")),
                llm.measures().get(3));
        MetricsCatalogView traces = c.views().get(0);
        assertEquals("Trace name", traces.dimensions().get(1).label());
        assertTrue(traces.dimensions().get(1).highCardinality());
        assertFalse(traces.dimensions().get(0).highCardinality());
        assertEquals("ms", traces.measures().get(7).unit());
        assertEquals("count", traces.measures().get(0).unit());
        assertEquals("tokens", llm.measures().get(1).unit());
        assertEquals(List.of("auto", "hour", "day", "week"), c.granularities());
        assertEquals(new MetricsCatalogLimits(90, 14, 5, 100, 4, 3, 50, 12, 20), c.limits());
    }

    /** The bug: a negative elapsed time from a misordered clock read, or a bucket count on a number tile. */
    @Test
    @DisplayName("elapsed_ms is never negative and a query without an axis has no granularity and no buckets")
    void elapsedMsIsNonNegativeAndWindowBucketsIsZeroWithoutAnAxis() {
        answer(List.of(raw(null, null, 1, 0, 0, vals("traces", 0.0))));

        MetricsQueryResult r = service.query(PID, req("traces", List.of("traces"), null, null, null, null));

        assertTrue(r.elapsedMs() >= 0, "elapsed " + r.elapsedMs());
        assertEquals(new MetricsWindow(FROM, TO, null, 0), r.window());
    }

    private void answer(List<Raw> rows) {
        when(repo.run(any(), any(), anyLong(), anyString())).thenReturn(new Result(rows, null));
    }

    private void answer(List<Raw> rows, List<Raw> previous) {
        when(repo.run(any(), any(), anyLong(), anyString())).thenReturn(new Result(rows, previous));
    }

    private static List<MetricsRow> requireRows(@Nullable List<MetricsRow> rows) {
        return Objects.requireNonNull(rows, "previous_rows");
    }

    private static MetricsQueryRequest req(
            String view,
            List<String> measures,
            @Nullable String dimension,
            @Nullable Integer topN,
            @Nullable String granularity,
            @Nullable String compare) {
        return new MetricsQueryRequest(view, measures, dimension, null, FROM, TO, granularity, topN, compare);
    }

    private static Raw raw(
            @Nullable Instant bucket, @Nullable String key, long rank, long n, long totalKeys, Map<String, Double> v) {
        return new Raw(bucket, key, rank, n, totalKeys, v);
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static Map<String, Double> vals(@Nullable Object... kv) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Double) kv[i + 1]);
        return out;
    }
}
