// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.metrics.dashboard.MetricDashboardDefinition.MetricWidgetDefinition;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardSummary;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultDashboardTest {

    private static final List<MetricWidgetDefinition> WIDGETS = DefaultDashboard.DEFINITION.widgets();

    /** The bug: a catalog rename leaves the built-in dashboard pointing at a measure that no longer exists. */
    @Test
    @DisplayName("the default dashboard passes the same validator every saved dashboard passes")
    void theDefaultValidates() {
        assertDoesNotThrow(() -> MetricDashboardValidator.validate(DefaultDashboard.DEFINITION));
        assertEquals(1, DefaultDashboard.DEFINITION.version());
        assertEquals("7d", DefaultDashboard.DEFINITION.defaultRange());
    }

    /** The bug: a widget dropped or duplicated in the constant, so the grid keys collide or a Vitals figure goes. */
    @Test
    @DisplayName("there are nine widgets and their ids are unique")
    void idsAreUniqueAndThereAreNine() {
        assertEquals(9, WIDGETS.size());
        Set<String> ids = WIDGETS.stream().map(MetricWidgetDefinition::id).collect(Collectors.toSet());
        assertEquals(9, ids.size());
    }

    /** The bug: a Vitals figure silently replaced by a different measure, view or chart when Vitals was removed. */
    @Test
    @DisplayName("each Vitals figure maps to a pinned title, view, measures, breakdown and chart")
    void theVitalsMappingIsPinnedByTitleViewMeasuresAndChart() {
        assertAll(
                () -> assertWidget(
                        0,
                        "spend",
                        "Spend",
                        "number",
                        "llm_calls",
                        List.of("cost_usd", "usd_per_trace"),
                        null,
                        null,
                        "previous_window"),
                () -> assertWidget(
                        1,
                        "traces",
                        "Traces",
                        "number",
                        "traces",
                        List.of("traces", "unterminated_traces"),
                        null,
                        null,
                        null),
                () -> assertWidget(
                        2,
                        "p95-latency",
                        "p95 latency",
                        "number",
                        "traces",
                        List.of("latency_p95_ms", "latency_p50_ms"),
                        null,
                        null,
                        "previous_window"),
                () -> assertWidget(
                        3, "tokens", "Tokens", "number", "llm_calls", List.of("tokens", "calls"), null, null, null),
                () -> assertWidget(
                        4,
                        "cases-opened",
                        "Cases opened",
                        "number",
                        "cases",
                        List.of("cases", "open_cases"),
                        null,
                        null,
                        "previous_window"),
                () -> assertWidget(
                        5,
                        "spend-by-call-site",
                        "Spend by call site",
                        "timeseries",
                        "llm_calls",
                        List.of("cost_usd"),
                        "call_site",
                        5,
                        null),
                () -> assertWidget(
                        6,
                        "p95-over-time",
                        "p95 latency over time",
                        "timeseries",
                        "traces",
                        List.of("latency_p95_ms"),
                        null,
                        null,
                        null),
                () -> assertWidget(
                        7,
                        "by-call-site",
                        "By call site",
                        "table",
                        "traces",
                        List.of("cost_usd", "usd_per_trace", "latency_p95_ms", "traces"),
                        "call_site",
                        20,
                        null),
                () -> assertWidget(
                        8,
                        "by-model",
                        "By model",
                        "table",
                        "llm_calls",
                        List.of("cost_usd", "calls", "tokens"),
                        "model",
                        20,
                        null));
        for (MetricWidgetDefinition w : WIDGETS) assertEquals(List.of(), w.filters(), w.id());
    }

    /** The bug: the screens' layout drifts, leaving a hole in the grid or two cards drawn over each other. */
    @Test
    @DisplayName("the placements cover every cell of grid rows 0 to 18 exactly once, as the screens lay them out")
    void placementsTileRowsZeroToEighteenWithoutGapsOrOverlap() {
        int[][] cover = new int[19][12];
        for (MetricWidgetDefinition w : WIDGETS) {
            for (int row = w.y(); row < w.y() + w.h(); row++) {
                for (int col = w.x(); col < w.x() + w.w(); col++) cover[row][col]++;
            }
        }
        for (int row = 0; row < 19; row++) {
            for (int col = 0; col < 12; col++) assertEquals(1, cover[row][col], "cell row " + row + " col " + col);
        }
        assertEquals(
                List.of(
                        List.of(0, 0, 4, 3),
                        List.of(4, 0, 4, 3),
                        List.of(8, 0, 4, 3),
                        List.of(0, 3, 6, 2),
                        List.of(6, 3, 6, 2),
                        List.of(0, 5, 6, 5),
                        List.of(6, 5, 6, 5),
                        List.of(0, 10, 12, 5),
                        List.of(0, 15, 12, 4)),
                WIDGETS.stream().map(w -> List.of(w.x(), w.y(), w.w(), w.h())).toList());
    }

    /** The bug: the built-in reports an author or a clock, or a widget count that differs from its definition. */
    @Test
    @DisplayName("the view is built in with null clocks and the summary counts nine widgets")
    void theViewIsBuiltInWithNullClocksAndTheSummaryCountsNine() {
        MetricDashboard view = DefaultDashboard.view(true);
        assertEquals("default", view.id());
        assertEquals("Default", view.name());
        assertEquals("Spend, traces, latency, tokens and cases over the window.", view.description());
        assertTrue(view.builtIn());
        assertSame(DefaultDashboard.DEFINITION, view.definition());
        assertNull(view.createdBy());
        assertNull(view.createdAt());
        assertNull(view.updatedAt());
        assertTrue(view.canManage());
        assertFalse(DefaultDashboard.view(false).canManage());

        assertEquals(
                new MetricDashboardSummary(
                        "default",
                        "Default",
                        "Spend, traces, latency, tokens and cases over the window.",
                        true,
                        9,
                        null),
                DefaultDashboard.summary());
    }

    private static void assertWidget(
            int index,
            String id,
            String title,
            String chart,
            String view,
            List<String> measures,
            @Nullable String dimension,
            @Nullable Integer topN,
            @Nullable String compare) {
        MetricWidgetDefinition w = WIDGETS.get(index);
        assertEquals(id, w.id());
        assertEquals(title, w.title(), id);
        assertEquals(chart, w.chart(), id);
        assertEquals(view, w.view(), id);
        assertEquals(measures, w.measures(), id);
        assertEquals(dimension, w.dimension(), id);
        assertEquals(topN, w.topN(), id);
        assertEquals(compare, w.compare(), id);
    }
}
