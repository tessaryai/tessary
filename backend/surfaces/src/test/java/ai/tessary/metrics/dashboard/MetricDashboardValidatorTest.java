// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.metrics.MetricsQueryDtos.MetricsFilter;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.dashboard.MetricDashboardDefinition.MetricWidgetDefinition;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetricDashboardValidatorTest {

    /** The bug: the cap read from a second constant that drifts from the catalog's max_widgets, or not checked. */
    @Test
    @DisplayName("twelve widgets are storable and a thirteenth is refused")
    void twelveWidgetsPassAndThirteenRefuse() {
        List<MetricWidgetDefinition> widgets = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            widgets.add(W.number("w" + i).at((i % 4) * 3, (i / 4) * 2, 3, 2).build());
        }
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(def(widgets)));

        widgets.add(W.number("w12").at(0, 6, 3, 2).build());
        TessaryException ex =
                assertThrows(TessaryException.class, () -> MetricDashboardValidator.validate(def(widgets)));
        assertSame(MetricsError.TOO_MANY_WIDGETS, ex.error());
        assertEquals("A dashboard holds at most 12 widgets", ex.getMessage());
    }

    /** The bug: an id the frontend cannot use as a grid key, or two widgets sharing one so edits hit the wrong one. */
    @Test
    @DisplayName("widget ids are 1 to 40 lowercase letters, digits or dashes, and no two widgets share one")
    void idsMustMatchThePatternAndBeUnique() {
        String reason = "id must be 1 to 40 lowercase letters, digits or dashes";
        for (String bad : List.of("Spend", "", "by_model", "a b", "x".repeat(41))) {
            assertRefused(bad, reason, W.number(bad));
        }
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(def(
                W.number("by-model-2").build(),
                W.number("x".repeat(40)).at(3, 0, 3, 2).build())));

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> MetricDashboardValidator.validate(def(
                        W.number("spend").build(),
                        W.number("spend").at(6, 0, 3, 2).build())));
        assertSame(MetricsError.INVALID_WIDGET, ex.error());
        assertEquals("Widget 'spend': id is used twice", ex.getMessage());
    }

    /** The bug: a title that overflows the 28 px title bar stored, or a blank (auto) title refused. */
    @Test
    @DisplayName("a title is null or at most 80 characters")
    void titlesLongerThanEightyRefuse() {
        assertDoesNotThrow(() ->
                MetricDashboardValidator.validate(def(W.number("a").title(null).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(W.number("a").title("t".repeat(80)).build())));
        assertRefused("a", "title is longer than 80 characters", W.number("a").title("t".repeat(81)));
    }

    /**
     * The bug: a widget that hangs off the 12-column grid, or one too small to draw, reaches the grid; or an x, w or y
     * near Integer.MAX_VALUE wraps x + w or y + h negative and is stored overlapping its neighbours.
     */
    @ParameterizedTest
    @CsvSource({
        "12, 0, 3, 2",
        "-1, 0, 3, 2",
        "0, 0, 2, 2",
        "0, 0, 13, 2",
        "10, 0, 3, 2",
        "0, 0, 3, 1",
        "0, 0, 3, 9",
        "0, -1, 3, 2",
        "1, 0, 2147483647, 2",
        "2147483647, 0, 3, 2",
        "0, 2147483647, 3, 2",
        "0, 89, 3, 8",
    })
    @DisplayName("placements stay inside the 12-column grid and its 96 rows, with width 3 to 12 and height 2 to 8")
    void placementsStayInsideTheGrid(int x, int y, int w, int h) {
        assertRefused(
                "a", "placement is outside the 12-column grid", W.number("a").at(x, y, w, h));
    }

    /** The bug: an off-by-one at the right or bottom edge refusing the largest legal widget. */
    @Test
    @DisplayName("the extreme legal placements pass")
    void theExtremeLegalPlacementsPass() {
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(W.number("a").at(9, 0, 3, 2).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(W.number("a").at(0, 40, 12, 8).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(W.number("a").at(0, 88, 12, 8).build())));
    }

    /** The bug: widgets drawn over each other, or neighbours that share an edge, in either array order, refused. */
    @Test
    @DisplayName("overlapping widgets are refused and widgets that only touch pass")
    void overlappingWidgetsRefuseAndTouchingWidgetsPass() {
        MetricWidgetDefinition a = W.number("a").at(0, 0, 6, 2).build();
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> MetricDashboardValidator.validate(
                        def(a, W.number("b").at(3, 1, 6, 2).build())));
        assertSame(MetricsError.INVALID_WIDGET, ex.error());
        assertEquals("Widget 'b': overlaps widget 'a'", ex.getMessage());

        assertRefusedAfter(a, "b", "overlaps widget 'a'", W.number("b").at(0, 0, 3, 2));
        assertRefusedAfter(a, "b", "overlaps widget 'a'", W.number("b").at(5, 1, 3, 2));

        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(a, W.number("b").at(6, 0, 6, 2).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(a, W.number("b").at(0, 2, 6, 2).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(a, W.number("b").at(6, 2, 6, 2).build())));

        MetricWidgetDefinition lower = W.number("lower").at(6, 2, 6, 2).build();
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(lower, W.number("above").at(6, 0, 6, 2).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(lower, W.number("left").at(0, 2, 6, 2).build())));
        assertRefusedAfter(
                lower, "above", "overlaps widget 'lower'", W.number("above").at(6, 1, 6, 2));
    }

    /** The bug: a number tile stored with a breakdown it cannot draw, or a third measure it has no line for. */
    @Test
    @DisplayName("a number has one or two measures, no dimension, no top_n, and may compare")
    void aNumberHasOneOrTwoMeasuresAndNoDimension() {
        String reason = "a number has one or two measures and no dimension";
        assertRefused("a", reason, W.number("a").measures("traces", "errored_traces", "cost_usd"));
        assertRefused("a", reason, W.number("a").dimension("call_site"));
        assertRefused("a", reason, W.number("a").topN(5));
        assertRefused("a", reason, W.number("a").measures());

        assertDoesNotThrow(() -> MetricDashboardValidator.validate(def(W.number("a")
                .measures("cost_usd", "usd_per_trace")
                .compare("previous_window")
                .build())));
    }

    /** The bug: a time series with two measures on one axis, more than five lines, or a compare it cannot draw. */
    @Test
    @DisplayName("a time series has one measure, top_n up to 5 and no compare")
    void aTimeSeriesHasOneMeasureTopNUpToFiveAndNoCompare() {
        String reason = "a time series has one measure, top_n up to 5 and no compare";
        assertRefused("a", reason, W.series("a").measures("traces", "errored_traces"));
        assertRefused("a", reason, W.series("a").measures());
        assertRefused("a", reason, W.series("a").dimension("call_site").topN(6));
        assertRefused("a", reason, W.series("a").dimension("call_site").topN(0));
        assertRefused("a", reason, W.series("a").compare("previous_window"));

        assertDoesNotThrow(
                () -> MetricDashboardValidator.validate(def(W.series("a").build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(W.series("a").dimension("call_site").topN(5).build())));
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(
                def(W.series("a").dimension("call_site").topN(1).build())));
    }

    /** The bug: a table with no column to group by, five measures it has no room for, or a 500-row top_n. */
    @Test
    @DisplayName("a table has a dimension, 1 to 4 measures and top_n up to 100")
    void aTableHasADimensionUpToFourMeasuresAndTopNUpToOneHundred() {
        String reason = "a table has 1 to 4 measures, a dimension and top_n up to 100";
        assertRefused("a", reason, W.table("a").dimension(null));
        assertRefused(
                "a",
                reason,
                W.table("a").measures("traces", "errored_traces", "cost_usd", "usd_per_trace", "unpriced_traces"));
        assertRefused("a", reason, W.table("a").measures());
        assertRefused("a", reason, W.table("a").topN(101));
        assertRefused("a", reason, W.table("a").topN(0));
        assertRefused("a", reason, W.table("a").compare("previous_window"));

        assertDoesNotThrow(() -> MetricDashboardValidator.validate(def(W.table("a")
                .measures("traces", "errored_traces", "cost_usd", "usd_per_trace")
                .topN(100)
                .build())));
        assertDoesNotThrow(
                () -> MetricDashboardValidator.validate(def(W.table("a").topN(1).build())));
        assertDoesNotThrow(() ->
                MetricDashboardValidator.validate(def(W.table("a").topN(null).build())));
    }

    /** The bug: a chart type the frontend has no renderer for stored and then shown as a blank card. */
    @Test
    @DisplayName("a chart other than number, timeseries or table is refused")
    void anUnknownChartRefuses() {
        assertRefused(
                "a", "chart must be number, timeseries or table", W.number("a").chart("pie"));
    }

    /** The bug: the query validator's refusal surfaces without the widget id, so the user cannot find the widget. */
    @Test
    @DisplayName("a measure from another view is refused through the query validator, naming the widget")
    void aMeasureFromAnotherViewFailsThroughTheQueryValidatorWithTheWidgetId() {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> MetricDashboardValidator.validate(
                        def(W.number("tile").measures("calls").build())));
        assertSame(MetricsError.INVALID_WIDGET, ex.error());
        assertEquals("Widget 'tile': View 'traces' has no measure named 'calls'", ex.getMessage());
        TessaryException cause = (TessaryException) Objects.requireNonNull(ex.getCause());
        assertSame(MetricsError.UNKNOWN_MEASURE, cause.error());

        assertRefused(
                "tile",
                "No metrics view named 'spans'. Views: traces, llm_calls, tool_calls, cases",
                W.number("tile").view("spans"));
        assertRefused(
                "tile",
                "Filter operator 'eq' is not supported; use in or not_in",
                W.number("tile").filters(List.of(new MetricsFilter("call_site", "eq", List.of("chat")))));
    }

    /** The bug: a stored time series checked without an axis (top_n up to 100 let through) or a number with one. */
    @Test
    @DisplayName("toRequest asks over the one-week dummy window, with auto granularity only for a time series")
    void toRequestUsesTheDummyWindowAndAutoOnlyForTimeseries() {
        List<MetricsFilter> filters = List.of(new MetricsFilter("model", "in", List.of("gpt")));
        MetricsQueryRequest series = MetricDashboardValidator.toRequest(W.series("a")
                .view("llm_calls")
                .measures("cost_usd")
                .dimension("call_site")
                .topN(3)
                .filters(filters)
                .build());
        assertEquals(
                new MetricsQueryRequest(
                        "llm_calls",
                        List.of("cost_usd"),
                        "call_site",
                        filters,
                        "2026-01-01T00:00:00Z",
                        "2026-01-08T00:00:00Z",
                        "auto",
                        3,
                        null),
                series);

        MetricsQueryRequest number = MetricDashboardValidator.toRequest(
                W.number("b").compare("previous_window").build());
        assertEquals(
                new MetricsQueryRequest(
                        "traces",
                        List.of("traces"),
                        null,
                        List.of(),
                        "2026-01-01T00:00:00Z",
                        "2026-01-08T00:00:00Z",
                        null,
                        null,
                        "previous_window"),
                number);

        assertNull(MetricDashboardValidator.toRequest(W.table("c").build()).granularity());
    }

    /** The bug: a widget stored with filters null (the API allows it) refused or read as an empty filter list. */
    @Test
    @DisplayName("null filters mean no filters")
    void nullFiltersMeanNone() {
        MetricWidgetDefinition w = W.number("a").filters(null).build();
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(def(w)));
        assertNull(MetricDashboardValidator.toRequest(w).filters());
    }

    /** The bug: a new dashboard (no widgets) refused, so "New dashboard" cannot save. */
    @Test
    @DisplayName("a definition with no widgets is valid")
    void anEmptyDefinitionIsValid() {
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(MetricDashboardDefinition.empty()));
    }

    private static void assertRefused(String id, String reason, W widget) {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> MetricDashboardValidator.validate(def(widget.build())));
        assertSame(MetricsError.INVALID_WIDGET, ex.error());
        assertEquals("Widget '" + id + "': " + reason, ex.getMessage());
    }

    private static void assertRefusedAfter(MetricWidgetDefinition first, String id, String reason, W widget) {
        TessaryException ex = assertThrows(
                TessaryException.class, () -> MetricDashboardValidator.validate(def(first, widget.build())));
        assertSame(MetricsError.INVALID_WIDGET, ex.error());
        assertEquals("Widget '" + id + "': " + reason, ex.getMessage());
    }

    static MetricDashboardDefinition def(MetricWidgetDefinition... widgets) {
        return def(List.of(widgets));
    }

    static MetricDashboardDefinition def(List<MetricWidgetDefinition> widgets) {
        return new MetricDashboardDefinition(1, "7d", widgets);
    }

    /** A valid widget to start from; each test changes the one field it is about. */
    static final class W {
        private final String id;
        private @Nullable String title = "Tile";
        private String chart;
        private String view = "traces";
        private List<String> measures = List.of("traces");
        private @Nullable String dimension;
        private @Nullable List<MetricsFilter> filters = List.of();
        private @Nullable Integer topN;
        private @Nullable String compare;
        private int x;
        private int y;
        private int w = 3;
        private int h = 2;

        private W(String id, String chart) {
            this.id = id;
            this.chart = chart;
        }

        static W number(String id) {
            return new W(id, "number");
        }

        static W series(String id) {
            return new W(id, "timeseries");
        }

        static W table(String id) {
            return new W(id, "table").dimension("call_site");
        }

        W title(@Nullable String value) {
            title = value;
            return this;
        }

        W chart(String value) {
            chart = value;
            return this;
        }

        W view(String value) {
            view = value;
            return this;
        }

        W measures(String... values) {
            measures = List.of(values);
            return this;
        }

        W dimension(@Nullable String value) {
            dimension = value;
            return this;
        }

        W filters(@Nullable List<MetricsFilter> value) {
            filters = value;
            return this;
        }

        W topN(@Nullable Integer value) {
            topN = value;
            return this;
        }

        W compare(@Nullable String value) {
            compare = value;
            return this;
        }

        W at(int px, int py, int pw, int ph) {
            x = px;
            y = py;
            w = pw;
            h = ph;
            return this;
        }

        MetricWidgetDefinition build() {
            return new MetricWidgetDefinition(
                    id, title, chart, view, measures, dimension, filters, topN, compare, x, y, w, h);
        }
    }
}
