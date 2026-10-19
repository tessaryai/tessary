// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import ai.tessary.metrics.dashboard.MetricDashboardDefinition.MetricWidgetDefinition;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The built-in dashboard every project has. It lives in code, not in {@code metric_dashboard}, so it is read-only; a
 * copy is the way to change it. The placements tile grid rows 0 to 18 with no gap.
 */
public final class DefaultDashboard {

    public static final String ID = "default";
    public static final String NAME = "Default";
    public static final String DESCRIPTION = "Spend, traces, latency, tokens and cases over the window.";

    public static final MetricDashboardDefinition DEFINITION = new MetricDashboardDefinition(
            MetricDashboardDefinition.VERSION,
            "7d",
            List.of(
                    widget(
                            "spend",
                            "Spend",
                            "number",
                            "llm_calls",
                            List.of("cost_usd", "usd_per_trace"),
                            null,
                            null,
                            "previous_window",
                            0,
                            0,
                            4,
                            3),
                    widget(
                            "traces",
                            "Traces",
                            "number",
                            "traces",
                            List.of("traces", "unterminated_traces"),
                            null,
                            null,
                            null,
                            4,
                            0,
                            4,
                            3),
                    widget(
                            "p95-latency",
                            "p95 latency",
                            "number",
                            "traces",
                            List.of("latency_p95_ms", "latency_p50_ms"),
                            null,
                            null,
                            "previous_window",
                            8,
                            0,
                            4,
                            3),
                    widget(
                            "tokens",
                            "Tokens",
                            "number",
                            "llm_calls",
                            List.of("tokens", "calls"),
                            null,
                            null,
                            null,
                            0,
                            3,
                            6,
                            2),
                    widget(
                            "cases-opened",
                            "Cases opened",
                            "number",
                            "cases",
                            List.of("cases", "open_cases"),
                            null,
                            null,
                            "previous_window",
                            6,
                            3,
                            6,
                            2),
                    widget(
                            "spend-by-call-site",
                            "Spend by call site",
                            "timeseries",
                            "llm_calls",
                            List.of("cost_usd"),
                            "call_site",
                            5,
                            null,
                            0,
                            5,
                            6,
                            5),
                    widget(
                            "p95-over-time",
                            "p95 latency over time",
                            "timeseries",
                            "traces",
                            List.of("latency_p95_ms"),
                            null,
                            null,
                            null,
                            6,
                            5,
                            6,
                            5),
                    widget(
                            "by-call-site",
                            "By call site",
                            "table",
                            "traces",
                            List.of("cost_usd", "usd_per_trace", "latency_p95_ms", "traces"),
                            "call_site",
                            20,
                            null,
                            0,
                            10,
                            12,
                            5),
                    widget(
                            "by-model",
                            "By model",
                            "table",
                            "llm_calls",
                            List.of("cost_usd", "calls", "tokens"),
                            "model",
                            20,
                            null,
                            0,
                            15,
                            12,
                            4)));

    private DefaultDashboard() {}

    public static MetricDashboard view(boolean canManage) {
        return new MetricDashboard(ID, NAME, DESCRIPTION, true, DEFINITION, null, null, null, canManage);
    }

    public static MetricDashboardSummary summary() {
        return new MetricDashboardSummary(
                ID, NAME, DESCRIPTION, true, DEFINITION.widgets().size(), null);
    }

    private static MetricWidgetDefinition widget(
            String id,
            String title,
            String chart,
            String view,
            List<String> measures,
            @Nullable String dimension,
            @Nullable Integer topN,
            @Nullable String compare,
            int x,
            int y,
            int w,
            int h) {
        return new MetricWidgetDefinition(
                id, title, chart, view, measures, dimension, List.of(), topN, compare, x, y, w, h);
    }
}
