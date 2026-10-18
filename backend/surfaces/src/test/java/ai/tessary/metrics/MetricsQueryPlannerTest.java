// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.metrics.MetricsQueryDtos.MetricsFilter;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.MetricsStatement.Column;
import ai.tessary.metrics.MetricsStatement.Shape;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SQL the planner emits for each shape, compared whole after collapsing whitespace, and its bound parameters. */
class MetricsQueryPlannerTest {

    private static final String PID = "proj-1";
    private static final String FROM = "2026-10-11T00:00:00Z";
    private static final String TO = "2026-10-18T00:00:00Z";
    private static final OffsetDateTime FROM_AT = OffsetDateTime.ofInstant(Instant.parse(FROM), ZoneOffset.UTC);
    private static final OffsetDateTime TO_AT = OffsetDateTime.ofInstant(Instant.parse(TO), ZoneOffset.UTC);

    private static final String ROOT = """
            LEFT JOIN LATERAL (
                SELECT s.started_at, s.ended_at
                  FROM span s
                 WHERE s.project_id = t.project_id AND s.trace_id = t.id
                   AND s.parent_span_id IS NULL AND s.is_deleted IS NOT TRUE
                 ORDER BY s.started_at ASC, s.id ASC
                 LIMIT 1) root ON TRUE
            """;

    private static final String LATENCY_Q = "percentile_cont(ARRAY[0.5, 0.95, 0.99]) WITHIN GROUP (ORDER BY"
            + " EXTRACT(EPOCH FROM (root.ended_at - root.started_at))::float8 * 1000) FILTER (WHERE root.ended_at IS"
            + " NOT NULL AND root.ended_at >= root.started_at)";

    /** The bug: a number tile that sums container spans, drops its companions, or binds instants as text. */
    @Test
    @DisplayName("a number is one aggregate row with its companions and timestamptz bounds")
    void numberShapeIsOneAggregateRowWithCompanions() {
        MetricsStatement st = plan(req("llm_calls", List.of("cost_usd")).compare("previous_window"));

        assertEquals(Shape.TOTAL, st.shape());
        assertSql("""
                SELECT sum(s.total_cost) FILTER (WHERE t.is_settled) AS cost_usd,
                       count(*) FILTER (WHERE s.cost_source = 'unpriced' AND s.total_tokens IS NOT NULL) AS unpriced_calls,
                       count(*) FILTER (WHERE NOT t.is_settled) AS unsettled_calls,
                       count(DISTINCT s.trace_id COLLATE "C") FILTER (WHERE t.is_settled AND s.total_cost IS NOT NULL) AS priced_traces,
                       count(*) AS n
                  FROM span s
                  JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id
                 WHERE s.project_id = :pid AND s.kind = 'llm' AND NOT s.is_deleted
                   AND s.started_at >= :from AND s.started_at < :to
                """, st);
        assertEquals(Map.of("pid", PID, "from", FROM_AT, "to", TO_AT), st.params());
    }

    /** The bug: a trace join on a plain call count, which turns an index-only span scan into a trace probe per row. */
    @Test
    @DisplayName("the trace join appears only when a settled measure is asked")
    void theTraceJoinAppearsOnlyWhenASettledMeasureIsAsked() {
        MetricsStatement calls = plan(req("llm_calls", List.of("calls")));
        assertFalse(calls.sql().contains("JOIN trace"), calls.sql());
        assertSql("""
                SELECT count(*) FILTER (WHERE s.total_tokens IS NOT NULL) AS calls, count(*) AS n
                  FROM span s
                 WHERE s.project_id = :pid AND s.kind = 'llm' AND NOT s.is_deleted
                   AND s.started_at >= :from AND s.started_at < :to
                """, calls);

        assertTrue(plan(req("llm_calls", List.of("calls", "tokens"))).sql().contains("JOIN trace t"));
    }

    /** The bug: three sorts for one latency family, or no lateral join so the root's interval is unreadable. */
    @Test
    @DisplayName("traces latency adds the root lateral and one quantile array, however many quantiles are asked")
    void tracesLatencyAddsTheRootLateralAndOneQuantileArray() {
        MetricsStatement st = plan(req("traces", List.of("latency_p95_ms")));
        assertSql(
                "SELECT " + LATENCY_Q + " AS latency_q,"
                        + " count(*) FILTER (WHERE root.ended_at IS NOT NULL AND root.ended_at >= root.started_at) AS"
                        + " latency_n,"
                        + " count(*) FILTER (WHERE root.started_at IS NOT NULL AND root.ended_at IS NULL) AS"
                        + " unterminated_traces,"
                        + " count(*) FILTER (WHERE root.started_at IS NULL) AS rootless_traces,"
                        + " count(*) AS n"
                        + " FROM trace t " + ROOT
                        + " WHERE t.project_id = :pid AND t.is_deleted IS NOT TRUE"
                        + " AND t.started_at >= :from AND t.started_at < :to",
                st);

        MetricsStatement two = plan(req("traces", List.of("latency_p50_ms", "latency_p95_ms")));
        assertEquals(st.sql(), two.sql(), "asking p50 beside p95 adds no column");
        Column array = two.columns().get(0);
        assertEquals("latency_q", array.alias());
        assertTrue(array.array());
        assertEquals(
                List.of("latency_p50_ms", "latency_p95_ms"),
                array.measures().stream().map(MetricsMeasure::wire).toList());
        assertEquals(1, two.columns().stream().filter(Column::array).count());
        assertFalse(two.columns().get(1).array());
    }

    /** The bug: a table that drops keys past top_n instead of folding them, or ranks with a join it never reads. */
    @Test
    @DisplayName("a table ranks through the CTE and folds the rest into Other")
    void tableShapeRanksThroughTheCteAndFoldsTheRestIntoOther() {
        MetricsStatement st = plan(req("traces", List.of("cost_usd", "usd_per_trace", "latency_p95_ms", "traces"))
                .dimension("call_site")
                .topN(20));

        assertEquals(Shape.GROUPED, st.shape());
        String cs = "COALESCE(t.call_site_id, '__unattributed__') COLLATE \"C\"";
        assertSql(
                "WITH ranked AS ( SELECT " + cs + " AS k, row_number() OVER (ORDER BY sum(t.total_cost) FILTER (WHERE"
                        + " t.is_settled) DESC NULLS LAST, " + cs + " ASC) AS rn"
                        + " FROM trace t"
                        + " WHERE t.project_id = :pid AND t.is_deleted IS NOT TRUE"
                        + " AND t.started_at >= :from AND t.started_at < :to"
                        + " GROUP BY 1)"
                        + " SELECT CASE WHEN r.rn <= :topN THEN r.k ELSE '__other__' END AS k,"
                        + " LEAST(r.rn, :topNPlusOne) AS rank,"
                        + " sum(t.total_cost) FILTER (WHERE t.is_settled) AS cost_usd,"
                        + " sum(t.total_cost) FILTER (WHERE t.is_settled) / NULLIF(count(*) FILTER (WHERE t.is_settled"
                        + " AND t.total_cost IS NOT NULL), 0) AS usd_per_trace,"
                        + " " + LATENCY_Q + " AS latency_q,"
                        + " count(*) AS traces,"
                        + " count(*) FILTER (WHERE t.is_settled AND COALESCE(t.unpriced_spans, 0) > 0) AS"
                        + " unpriced_traces,"
                        + " count(*) FILTER (WHERE NOT t.is_settled) AS unsettled_traces,"
                        + " count(*) FILTER (WHERE t.is_settled AND t.total_cost IS NOT NULL) AS priced_traces,"
                        + " count(*) FILTER (WHERE root.ended_at IS NOT NULL AND root.ended_at >= root.started_at) AS"
                        + " latency_n,"
                        + " count(*) FILTER (WHERE root.started_at IS NOT NULL AND root.ended_at IS NULL) AS"
                        + " unterminated_traces,"
                        + " count(*) FILTER (WHERE root.started_at IS NULL) AS rootless_traces,"
                        + " count(*) AS n,"
                        + " (SELECT count(*) FROM ranked) AS total_keys"
                        + " FROM trace t " + ROOT
                        + " JOIN ranked r ON r.k = " + cs
                        + " WHERE t.project_id = :pid AND t.is_deleted IS NOT TRUE"
                        + " AND t.started_at >= :from AND t.started_at < :to"
                        + " GROUP BY 1, 2"
                        + " ORDER BY 2",
                st);
        assertEquals(Map.of("pid", PID, "from", FROM_AT, "to", TO_AT, "topN", 20, "topNPlusOne", 21), st.params());
        String cte = collapse(st.sql()).substring(0, collapse(st.sql()).indexOf(" GROUP BY 1)"));
        assertFalse(cte.contains("LEFT JOIN LATERAL"), "the cost ranking reads no root: " + cte);
    }

    /** The bug: one ix_span_root probe, or one trace probe, per base row that the ranking never reads. */
    @Test
    @DisplayName("the CTE carries only the joins its ranking measure needs")
    void theCteCarriesOnlyTheJoinsItsRankingMeasureNeeds() {
        MetricsStatement byCalls =
                plan(req("llm_calls", List.of("calls", "cost_usd")).dimension("call_site"));
        assertFalse(cte(byCalls).contains("JOIN trace"), cte(byCalls));
        assertTrue(outer(byCalls).contains("JOIN trace t"), outer(byCalls));

        MetricsStatement bySpend =
                plan(req("llm_calls", List.of("cost_usd", "calls")).dimension("call_site"));
        assertTrue(cte(bySpend).contains("JOIN trace t"), cte(bySpend));

        MetricsStatement byLatency =
                plan(req("traces", List.of("latency_p95_ms", "traces")).dimension("call_site"));
        assertTrue(cte(byLatency).contains("LEFT JOIN LATERAL"), cte(byLatency));
        assertTrue(outer(byLatency).contains("LEFT JOIN LATERAL"), outer(byLatency));

        MetricsStatement byCount =
                plan(req("traces", List.of("traces", "latency_p95_ms")).dimension("call_site"));
        assertFalse(cte(byCount).contains("LEFT JOIN LATERAL"), cte(byCount));
    }

    /** The bug: ORDER BY on an output alias with a subscript, which Postgres refuses, or ranking by the whole array. */
    @Test
    @DisplayName("ranking by a quantile subscripts the aggregate's array")
    void rankingByAQuantileSubscriptsTheArray() {
        MetricsStatement st = plan(req("traces", List.of("latency_p95_ms")).dimension("outcome"));
        assertTrue(
                collapse(st.sql()).contains("row_number() OVER (ORDER BY (" + LATENCY_Q + ")[2] DESC NULLS LAST, "),
                st.sql());
    }

    /** The bug: buckets in the session time zone, or a series without its unit bound. */
    @Test
    @DisplayName("a series without a dimension buckets on the clock in UTC")
    void seriesWithoutDimensionBucketsOnTheClockInUtc() {
        MetricsStatement st = plan(req("traces", List.of("latency_p95_ms")).granularity("auto"));

        assertEquals(Shape.SERIES, st.shape());
        assertSql(
                "SELECT date_trunc(:unit::text, t.started_at, 'UTC') AS bkt, " + LATENCY_Q + " AS latency_q,"
                        + " count(*) FILTER (WHERE root.ended_at IS NOT NULL AND root.ended_at >= root.started_at) AS"
                        + " latency_n,"
                        + " count(*) FILTER (WHERE root.started_at IS NOT NULL AND root.ended_at IS NULL) AS"
                        + " unterminated_traces,"
                        + " count(*) FILTER (WHERE root.started_at IS NULL) AS rootless_traces,"
                        + " count(*) AS n"
                        + " FROM trace t " + ROOT
                        + " WHERE t.project_id = :pid AND t.is_deleted IS NOT TRUE"
                        + " AND t.started_at >= :from AND t.started_at < :to"
                        + " GROUP BY 1 ORDER BY 1",
                st);
        assertEquals(Map.of("pid", PID, "from", FROM_AT, "to", TO_AT, "unit", "day"), st.params());
    }

    /** The bug: a series whose keys are re-ranked per bucket, or whose filter values reach the SQL text. */
    @Test
    @DisplayName("a series with a dimension groups by bucket, key and rank and binds its filter as an array")
    void seriesWithDimensionGroupsByBucketKeyAndRank() {
        MetricsStatement st = plan(req("llm_calls", List.of("cost_usd"))
                .dimension("call_site")
                .filters(List.of(new MetricsFilter("model", "in", List.of("gpt-4o", "claude-sonnet-4"))))
                .granularity("auto")
                .topN(5));

        assertEquals(Shape.GROUPED_SERIES, st.shape());
        String cs = "COALESCE(s.call_site_id, '__unattributed__') COLLATE \"C\"";
        String where = " WHERE s.project_id = :pid AND s.kind = 'llm' AND NOT s.is_deleted"
                + " AND s.started_at >= :from AND s.started_at < :to"
                + " AND COALESCE(s.provided_model_name, '__unattributed__') COLLATE \"C\" = ANY(:f0)";
        String join = " JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id";
        assertSql(
                "WITH ranked AS ( SELECT " + cs + " AS k, row_number() OVER (ORDER BY sum(s.total_cost) FILTER (WHERE"
                        + " t.is_settled) DESC NULLS LAST, " + cs + " ASC) AS rn"
                        + " FROM span s" + join + where
                        + " GROUP BY 1)"
                        + " SELECT date_trunc(:unit::text, s.started_at, 'UTC') AS bkt,"
                        + " CASE WHEN r.rn <= :topN THEN r.k ELSE '__other__' END AS k,"
                        + " LEAST(r.rn, :topNPlusOne) AS rank,"
                        + " sum(s.total_cost) FILTER (WHERE t.is_settled) AS cost_usd,"
                        + " count(*) FILTER (WHERE s.cost_source = 'unpriced' AND s.total_tokens IS NOT NULL) AS"
                        + " unpriced_calls,"
                        + " count(*) FILTER (WHERE NOT t.is_settled) AS unsettled_calls,"
                        + " count(DISTINCT s.trace_id COLLATE \"C\") FILTER (WHERE t.is_settled AND s.total_cost IS NOT"
                        + " NULL) AS priced_traces,"
                        + " count(*) AS n,"
                        + " (SELECT count(*) FROM ranked) AS total_keys"
                        + " FROM span s" + join
                        + " JOIN ranked r ON r.k = " + cs
                        + where
                        + " GROUP BY 1, 2, 3"
                        + " ORDER BY 1, 3",
                st);
        Map<String, Object> rest = new HashMap<>(st.params());
        assertArrayEquals(new String[] {"gpt-4o", "claude-sonnet-4"}, (String[]) rest.remove("f0"));
        assertEquals(
                Map.of("pid", PID, "from", FROM_AT, "to", TO_AT, "unit", "day", "topN", 5, "topNPlusOne", 6), rest);
    }

    /** The bug: not_in read as in, which shows exactly the rows the user asked to hide. */
    @Test
    @DisplayName("not_in filters negate the array match and in filters do not")
    void notInFiltersNegateTheArrayMatch() {
        MetricsStatement st = plan(req("tool_calls", List.of("calls"))
                .filters(List.of(
                        new MetricsFilter("tool", "not_in", List.of("search")),
                        new MetricsFilter("scope", "in", List.of("client")))));

        String sql = collapse(st.sql());
        assertTrue(
                sql.endsWith(" AND NOT (COALESCE(NULLIF(tc.name, ''), 'unnamed') COLLATE \"C\" = ANY(:f0))"
                        + " AND COALESCE(tc.scope, '__unattributed__') COLLATE \"C\" = ANY(:f1)"),
                sql);
        assertArrayEquals(new String[] {"search"}, (String[]) st.params().get("f0"));
        assertArrayEquals(new String[] {"client"}, (String[]) st.params().get("f1"));
    }

    /**
     * The bug: a cases window bound as Instant.toString(), which drops the first second of the window and takes the
     * first second after it, or a cast in the WHERE that one malformed opened_at breaks for every window.
     */
    @Test
    @DisplayName("cases bind nine-digit ISO text and cast to timestamptz only in the SELECT")
    void casesBindNineDigitIsoTextAndCastOnlyInTheSelect() {
        MetricsStatement number =
                plan(req("cases", List.of("cases", "open_cases")).compare("previous_window"));
        assertSql("""
                SELECT count(*) AS cases,
                       count(*) FILTER (WHERE c.state <> 'resolved') AS open_cases,
                       count(*) AS n
                  FROM eval_case c
                 WHERE c.project_id = :pid
                   AND c.opened_at >= :from AND c.opened_at < :to
                """, number);
        assertEquals(
                Map.of("pid", PID, "from", "2026-10-11T00:00:00.000000000Z", "to", "2026-10-18T00:00:00.000000000Z"),
                number.params());

        String series = collapse(
                plan(req("cases", List.of("cases")).dimension("detector").granularity("day"))
                        .sql());
        String where = series.substring(series.lastIndexOf(" WHERE "));
        assertTrue(series.contains("date_trunc(:unit::text, c.opened_at::timestamptz, 'UTC') AS bkt"), series);
        assertFalse(where.contains("::timestamptz"), where);
    }

    /** The bug: a filter value spliced into the SQL text, which is SQL injection through a dashboard widget. */
    @Test
    @DisplayName("nothing from the request is interpolated into the SQL text")
    void nothingFromTheRequestIsInterpolated() {
        String hostile = "'; DROP TABLE span; --";
        MetricsStatement st = plan(req("llm_calls", List.of("calls"))
                .dimension("model")
                .filters(List.of(
                        new MetricsFilter("model", "in", List.of(hostile)),
                        new MetricsFilter("call_site", "not_in", List.of(hostile + "2")))));

        assertFalse(st.sql().contains("DROP TABLE"), st.sql());
        assertFalse(st.sql().contains(PID), st.sql());
        assertArrayEquals(new String[] {hostile}, (String[]) st.params().get("f0"));
        assertArrayEquals(new String[] {hostile + "2"}, (String[]) st.params().get("f1"));
    }

    /** The bug: values read from the wrong column because the column list and the measure list drifted apart. */
    @Test
    @DisplayName("columns follow the asked measures, then companions in encounter order, one per alias")
    void columnsFollowAskedThenCompanionOrder() {
        MetricsStatement st = plan(req("tool_calls", List.of("latency_p99_ms", "error_rate", "latency_p50_ms")));

        assertEquals(
                List.of("latency_q", "error_rate", "latency_n", "errored_calls"),
                st.columns().stream().map(Column::alias).toList());
        assertEquals(
                List.of("latency_p99_ms", "latency_p50_ms"),
                st.columns().get(0).measures().stream()
                        .map(MetricsMeasure::wire)
                        .toList());
        String sql = collapse(st.sql());
        assertTrue(
                sql.indexOf(" AS latency_q,") < sql.indexOf(" AS error_rate,")
                        && sql.indexOf(" AS error_rate,") < sql.indexOf(" AS latency_n,")
                        && sql.indexOf(" AS latency_n,") < sql.indexOf(" AS errored_calls,"),
                sql);
    }

    private static MetricsStatement plan(Req r) {
        MetricsQuery q = MetricsQueryValidator.resolve(r.build());
        return MetricsQueryPlanner.plan(q, PID, q.from(), q.to());
    }

    private static void assertSql(String expected, MetricsStatement actual) {
        assertEquals(collapse(expected), collapse(actual.sql()));
    }

    private static String collapse(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String cte(MetricsStatement st) {
        String sql = collapse(st.sql());
        return sql.substring(0, sql.indexOf(" GROUP BY 1)"));
    }

    private static String outer(MetricsStatement st) {
        String sql = collapse(st.sql());
        return sql.substring(sql.indexOf(" GROUP BY 1)"));
    }

    private static Req req(String view, List<String> measures) {
        return new Req(view, measures);
    }

    private static final class Req {
        private final String view;
        private final List<String> measures;
        private @Nullable String dimension;
        private @Nullable List<MetricsFilter> filters;
        private @Nullable String granularity;
        private @Nullable Integer topN;
        private @Nullable String compare;

        private Req(String view, List<String> measures) {
            this.view = view;
            this.measures = measures;
        }

        Req dimension(String v) {
            this.dimension = v;
            return this;
        }

        Req filters(List<MetricsFilter> v) {
            this.filters = v;
            return this;
        }

        Req granularity(String v) {
            this.granularity = v;
            return this;
        }

        Req topN(int v) {
            this.topN = v;
            return this;
        }

        Req compare(String v) {
            this.compare = v;
            return this;
        }

        MetricsQueryRequest build() {
            return new MetricsQueryRequest(view, measures, dimension, filters, FROM, TO, granularity, topN, compare);
        }
    }
}
