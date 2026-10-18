// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import ai.tessary.metrics.MetricsQueryDtos.MetricsFilter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A dashboard as stored in {@code metric_dashboard.definition}, version 1. Jakarta checks only the shape; every widget
 * rule is {@link MetricDashboardValidator}'s, so a refusal names the widget. The strict mapper reads stored rows, so a
 * field this record does not know fails the read: that is the version gate.
 */
public record MetricDashboardDefinition(
        @Min(1) @Max(1) int version,

        @JsonProperty("default_range") @NotNull @Pattern(regexp = "24h|7d|30d|90d")
        String defaultRange,

        @NotNull @Valid List<@NotNull @Valid MetricWidgetDefinition> widgets) {

    public static final int VERSION = 1;

    public static MetricDashboardDefinition empty() {
        return new MetricDashboardDefinition(VERSION, "7d", List.of());
    }

    /** A saved query plus its place on the 12-column grid; {@code h} counts grid rows. */
    public record MetricWidgetDefinition(
            @NotNull String id,
            @Nullable String title,
            /** "number", "timeseries" or "table". */
            @NotNull String chart,
            @NotNull String view,
            @NotNull List<String> measures,
            @Nullable String dimension,
            /** Null means none. */
            @Nullable List<@NotNull @Valid MetricsFilter> filters,
            @JsonProperty("top_n") @Nullable Integer topN,
            @Nullable @Pattern(regexp = "previous_window") String compare,
            int x,
            int y,
            int w,
            int h) {}
}
