// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.metrics.MetricsQueryDtos.MetricsFilter;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetricsQueryValidatorTest {

    private static final String FROM = "2026-10-11T00:00:00Z";
    private static final String TO = "2026-10-18T00:00:00Z";

    /** The bug: a 90-day default range refused, or a year-long scan let through to the database. */
    @Test
    @DisplayName("a 90-day window passes and anything longer is refused")
    void ninetyDaysPassAndNinetyOneDaysRefuse() {
        MetricsQuery ok = MetricsQueryValidator.resolve(
                req().from("2026-07-01T00:00:00Z").to("2026-09-29T00:00:00Z").build());
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), ok.from());

        assertRefused(
                MetricsError.WINDOW_TOO_LONG,
                "The window is longer than 90 days; narrow it",
                req().from("2026-07-01T00:00:00Z").to("2026-09-30T00:00:00Z"));
        assertRefused(
                MetricsError.WINDOW_TOO_LONG,
                "The window is longer than 90 days; narrow it",
                req().from("2026-07-01T00:00:00Z").to("2026-09-29T00:00:01Z"));
    }

    /** The bug: hour buckets over a month, 720 points the chart cannot draw and the database must sort. */
    @Test
    @DisplayName("hour buckets pass at 14 days and are refused at 15")
    void hourAtFourteenDaysPassesAndFifteenRefuses() {
        MetricsQuery ok = MetricsQueryValidator.resolve(req().from("2026-10-01T00:00:00Z")
                .to("2026-10-15T00:00:00Z")
                .granularity("hour")
                .build());
        assertEquals(MetricsGranularity.HOUR, ok.unit());

        assertRefused(
                MetricsError.WINDOW_TOO_FINE,
                "Hour buckets cover at most 14 days; pick day or week",
                req().from("2026-10-01T00:00:00Z").to("2026-10-16T00:00:00Z").granularity("hour"));
    }

    /** The bug: auto handed to the planner unresolved, or resolved against the wrong window. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "2026-10-15T00:00:00Z, auto, HOUR",
                "2026-10-15T00:00:01Z, auto, DAY",
                "2026-10-18T00:00:00Z, auto, DAY",
                "2026-10-18T00:00:00Z, week, WEEK",
                "2026-10-18T00:00:00Z, null, null",
            })
    @DisplayName("auto becomes hour up to 72 hours and day past it; no granularity means no axis")
    void autoResolvesToHourOrDayByWindowLength(
            String to, @Nullable String granularity, @Nullable MetricsGranularity unit) {
        MetricsQuery q = MetricsQueryValidator.resolve(req().from("2026-10-12T00:00:00Z")
                .to(to)
                .granularity(granularity)
                .build());
        assertEquals(unit, q.unit());
        assertEquals(unit != null, q.hasAxis());
    }

    /** The bug: a series with dozens of lines, or a table that pulls thousands of keys into one answer. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "day, 5, ",
                "day, 1, ",
                "day, 6, 5",
                "day, 0, 5",
                "null, 100, ",
                "null, 1, ",
                "null, 101, 100",
                "null, 0, 100",
            })
    @DisplayName("top_n is 1 to 5 with an axis and 1 to 100 without, and a refusal names the max")
    void topNIsOneToFiveWithAnAxisAndOneToOneHundredWithout(
            @Nullable String granularity, int topN, @Nullable Integer refusedMax) {
        Req r = req().dimension("call_site").granularity(granularity).topN(topN);
        if (refusedMax == null) {
            assertEquals(topN, MetricsQueryValidator.resolve(r.build()).topN());
        } else {
            assertRefused(
                    MetricsError.TOP_N_TOO_LARGE, "top_n must be between 1 and " + refusedMax + " for this query", r);
        }
    }

    /** The bug: a series with no top_n drawing every key, or a table cut to five rows. */
    @Test
    @DisplayName("top_n defaults to 5 with an axis and 20 without")
    void topNDefaultsToFiveWithAnAxisAndTwentyWithout() {
        assertEquals(
                5,
                MetricsQueryValidator.resolve(
                                req().dimension("call_site").granularity("day").build())
                        .topN());
        assertEquals(
                20,
                MetricsQueryValidator.resolve(req().dimension("call_site").build())
                        .topN());
    }

    /** The bug: a previous-window series the planner has no shape for. */
    @Test
    @DisplayName("compare is refused with an axis and taken without one")
    void compareWithAnAxisIsRefused() {
        assertRefused(
                MetricsError.COMPARE_NEEDS_NO_AXIS,
                "compare works only without a granularity",
                req().granularity("day").compare("previous_window"));
        assertTrue(
                MetricsQueryValidator.resolve(req().compare("previous_window").build())
                        .compare());
        assertFalse(MetricsQueryValidator.resolve(req().build()).compare());
    }

    /**
     * The bug: a previous window planned with its own top N and its own fold, so its keys do not line up with the
     * current window's and a delta compares one call site with another.
     */
    @Test
    @DisplayName("compare is refused with a dimension, after the axis check")
    void compareWithADimensionIsRefused() {
        assertRefused(
                MetricsError.COMPARE_NEEDS_NO_DIMENSION,
                "compare works only without a dimension",
                req().dimension("call_site").compare("previous_window"));
        assertRefused(
                MetricsError.COMPARE_NEEDS_NO_AXIS,
                "compare works only without a granularity",
                req().dimension("call_site").granularity("day").compare("previous_window"));
    }

    /** The bug: a query of many measures that widens one scan without bound, or a repeated measure read twice. */
    @Test
    @DisplayName("four measures pass, five are refused, and a repeated measure counts once")
    void fourMeasuresPassAndFiveRefuseAndDuplicatesCollapse() {
        MetricsView v = MetricsView.TRACES;
        MetricsQuery four = MetricsQueryValidator.resolve(req().view("traces")
                .measures("traces", "errored_traces", "unsettled_traces", "rootless_traces")
                .build());
        assertEquals(
                List.of(
                        v.measure("traces"),
                        v.measure("errored_traces"),
                        v.measure("unsettled_traces"),
                        v.measure("rootless_traces")),
                four.asked());

        assertRefused(
                MetricsError.TOO_MANY_MEASURES,
                "A query takes 1 to 4 measures",
                req().view("traces")
                        .measures("traces", "errored_traces", "unsettled_traces", "rootless_traces", "latency_n"));

        MetricsQuery repeated = MetricsQueryValidator.resolve(req().view("traces")
                .measures("errored_traces", "traces", "errored_traces")
                .build());
        assertEquals(List.of(v.measure("errored_traces"), v.measure("traces")), repeated.asked());
    }

    /** The bug: an empty query that plans a SELECT with no columns. */
    @Test
    @DisplayName("zero measures are refused")
    void zeroMeasuresRefuse() {
        assertRefused(MetricsError.TOO_MANY_MEASURES, "A query takes 1 to 4 measures", req().measures());
    }

    /** The bug: filters past the cap ANDed into one scan without bound. */
    @Test
    @DisplayName("three filters pass and four are refused")
    void threeFiltersPassAndFourRefuse() {
        MetricsFilter f = new MetricsFilter("model", "in", List.of("gpt-5"));
        assertEquals(
                3,
                MetricsQueryValidator.resolve(req().filters(f, f, f).build())
                        .filters()
                        .size());
        assertRefused(MetricsError.TOO_MANY_FILTERS, "A query takes at most 3 filters", req().filters(f, f, f, f));
    }

    /** The bug: an IN list of thousands bound into one statement, or an empty IN that matches nothing silently. */
    @Test
    @DisplayName("a filter takes 1 to 50 values")
    void fiftyValuesPassAndFiftyOneRefuseAndZeroRefuse() {
        MetricsQuery ok = MetricsQueryValidator.resolve(
                req().filters(new MetricsFilter("model", "in", values(50))).build());
        assertEquals(values(50), ok.filters().get(0).values());

        assertRefused(
                MetricsError.TOO_MANY_VALUES,
                "A filter takes 1 to 50 values",
                req().filters(new MetricsFilter("model", "in", values(51))));
        assertRefused(
                MetricsError.TOO_MANY_VALUES,
                "A filter takes 1 to 50 values",
                req().filters(new MetricsFilter("model", "in", List.of())));
    }

    /** The bug: an unknown operator read as IN, or a filter on a column the view does not have. */
    @Test
    @DisplayName("an unknown operator and an unknown filter field are refused")
    void badOperatorAndUnknownFilterFieldRefuse() {
        assertRefused(
                MetricsError.BAD_OPERATOR,
                "Filter operator 'eq' is not supported; use in or not_in",
                req().filters(new MetricsFilter("model", "eq", List.of("gpt-5"))));
        assertRefused(
                MetricsError.UNKNOWN_FILTER_FIELD,
                "View 'llm_calls' has no dimension named 'tool' to filter on",
                req().filters(new MetricsFilter("tool", "in", List.of("search"))));
    }

    /** The bug: not_in planned as IN, so an exclusion shows only what it meant to hide. */
    @Test
    @DisplayName("not_in resolves to a negated filter and in to a plain one")
    void notInIsNegated() {
        MetricsDimension model = MetricsView.LLM_CALLS.dimension("model");
        MetricsDimension site = MetricsView.LLM_CALLS.dimension("call_site");
        MetricsQuery q = MetricsQueryValidator.resolve(req().filters(
                        new MetricsFilter("model", "not_in", List.of("gpt-5")),
                        new MetricsFilter("call_site", "in", List.of("checkout")))
                .build());
        assertEquals(
                List.of(
                        new MetricsQuery.Filter(model, true, List.of("gpt-5")),
                        new MetricsQuery.Filter(site, false, List.of("checkout"))),
                q.filters());
    }

    /** The bug: an unparseable bound surfacing as a 500, or an empty or inverted window planned at all. */
    @ParameterizedTest
    @CsvSource({
        "yesterday, 2026-10-18T00:00:00Z",
        "2026-10-11T00:00:00Z, 2026-10-18",
        "2026-10-18T00:00:00Z, 2026-10-11T00:00:00Z",
        "2026-10-11T00:00:00Z, 2026-10-11T00:00:00Z",
    })
    @DisplayName("from and to must parse as instants with from before to")
    void fromMustParseAndPrecedeTo(String from, String to) {
        assertRefused(
                MetricsError.INVALID_RANGE,
                "'from' and 'to' must be ISO-8601 instants with from before to",
                req().from(from).to(to));
    }

    /** The bug: an unknown name passed to the planner as SQL, or refused with a code that hides what was wrong. */
    @Test
    @DisplayName("an unknown view, measure, dimension or granularity is refused with its own code")
    void unknownViewMeasureDimensionAndGranularityRefuse() {
        assertRefused(
                MetricsError.UNKNOWN_VIEW,
                "No metrics view named 'spans'. Views: traces, llm_calls, tool_calls, cases",
                req().view("spans"));
        assertRefused(
                MetricsError.UNKNOWN_MEASURE,
                "View 'llm_calls' has no measure named 'traces'",
                req().measures("calls", "traces"));
        assertRefused(
                MetricsError.UNKNOWN_DIMENSION,
                "View 'llm_calls' has no dimension named 'user'",
                req().dimension("user"));
        assertRefused(
                MetricsError.UNKNOWN_GRANULARITY,
                "Granularity 'minute' is not one of auto, hour, day, week",
                req().granularity("minute"));
    }

    /** The bug: a spend figure answered without the unpriced and settling counts that qualify it, or with them twice. */
    @Test
    @DisplayName("companions follow the asked measures, each once, in encounter order")
    void companionsAreAppendedOnceAfterTheAskedMeasures() {
        MetricsView v = MetricsView.LLM_CALLS;
        MetricsQuery spend = MetricsQueryValidator.resolve(
                req().measures("cost_usd", "usd_per_trace").build());
        assertEquals(List.of(v.measure("cost_usd"), v.measure("usd_per_trace")), spend.asked());
        assertEquals(
                List.of(
                        v.measure("cost_usd"),
                        v.measure("usd_per_trace"),
                        v.measure("unpriced_calls"),
                        v.measure("unsettled_calls"),
                        v.measure("priced_traces")),
                spend.all());

        MetricsQuery askedCompanion = MetricsQueryValidator.resolve(
                req().measures("priced_traces", "cost_usd").build());
        assertEquals(
                List.of(
                        v.measure("priced_traces"),
                        v.measure("cost_usd"),
                        v.measure("unpriced_calls"),
                        v.measure("unsettled_calls")),
                askedCompanion.all());
    }

    /** The bug: an absent filter list read as a 500, or an empty one planned as an empty IN. */
    @Test
    @DisplayName("null filters and an empty list both mean no filter")
    void nullFiltersMeanNone() {
        MetricsMeasure calls = MetricsView.LLM_CALLS.measure("calls");
        MetricsQuery expected = new MetricsQuery(
                MetricsView.LLM_CALLS,
                List.of(calls),
                List.of(calls),
                null,
                List.of(),
                Instant.parse(FROM),
                Instant.parse(TO),
                null,
                20,
                false);
        assertEquals(expected, MetricsQueryValidator.resolve(req().build()));
        assertEquals(expected, MetricsQueryValidator.resolve(req().filters().build()));
        assertNull(MetricsQueryValidator.resolve(req().build()).dimension());
    }

    private static List<String> values(int n) {
        return IntStream.range(0, n).mapToObj(i -> "model-" + i).toList();
    }

    private static void assertRefused(MetricsError code, String message, Req r) {
        MetricsQueryRequest request = r.build();
        TessaryException ex = assertThrows(TessaryException.class, () -> MetricsQueryValidator.resolve(request));
        assertEquals(code, ex.error());
        assertEquals(message, ex.getMessage());
    }

    private static Req req() {
        return new Req();
    }

    /** A valid seven-day llm_calls count with no axis; each test changes only what it is about. */
    private static final class Req {
        private String view = "llm_calls";
        private List<String> measures = List.of("calls");
        private @Nullable String dimension;
        private @Nullable List<MetricsFilter> filters;
        private String from = FROM;
        private String to = TO;
        private @Nullable String granularity;
        private @Nullable Integer topN;
        private @Nullable String compare;

        Req view(String v) {
            view = v;
            return this;
        }

        Req measures(String... m) {
            measures = List.of(m);
            return this;
        }

        Req dimension(String d) {
            dimension = d;
            return this;
        }

        Req filters(MetricsFilter... f) {
            List<MetricsFilter> list = new ArrayList<>();
            Collections.addAll(list, f);
            filters = list;
            return this;
        }

        Req from(String f) {
            from = f;
            return this;
        }

        Req to(String t) {
            to = t;
            return this;
        }

        Req granularity(@Nullable String g) {
            granularity = g;
            return this;
        }

        Req topN(int n) {
            topN = n;
            return this;
        }

        Req compare(String c) {
            compare = c;
            return this;
        }

        MetricsQueryRequest build() {
            return new MetricsQueryRequest(view, measures, dimension, filters, from, to, granularity, topN, compare);
        }
    }
}
