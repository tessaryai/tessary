// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Wire shapes of the metrics query and catalog endpoints. Every instant on the wire is an ISO-8601 string. */
public final class MetricsQueryDtos {

    private MetricsQueryDtos() {}

    public record MetricsQueryRequest(
            @NotBlank String view,
            @NotNull List<String> measures,
            @Nullable String dimension,
            @Nullable List<@NotNull @Valid MetricsFilter> filters,
            @NotBlank String from,
            @NotBlank String to,
            @Nullable String granularity,
            @JsonProperty("top_n") @Nullable Integer topN,
            @Nullable @Pattern(regexp = "previous_window") String compare) {}

    public record MetricsFilter(
            @NotBlank String field,
            /** "in" or "not_in". */
            @NotBlank String op,
            @NotNull List<String> values) {}

    public record MetricsWindow(
            String from,
            String to,
            /** "hour", "day" or "week"; null without an axis. */
            @Nullable String granularity,
            /** 0 without an axis. */
            int buckets) {}

    public record MetricsRow(
            /** ISO instant of the bucket start; null without an axis. */
            @Nullable String bucket,
            /** The raw group key; null without a dimension; "__other__" for the fold. */
            @Nullable String key,
            /** The display label; null when key is null. */
            @Nullable String label,
            /** Base rows in the group; 0 on a filled bucket. */
            long n,
            /** Asked measures plus companions. A LinkedHashMap, because a value may be null. */
            Map<String, @Nullable Double> values) {}

    public record MetricsTopN(
            int shown, @JsonProperty("total_keys") long totalKeys) {}

    public record MetricsQueryResult(
            String view,
            MetricsWindow window,
            @Nullable String dimension,
            List<MetricsRow> rows,
            @JsonProperty("previous_rows") @Nullable List<MetricsRow> previousRows,
            @JsonProperty("top_n") @Nullable MetricsTopN topN,
            boolean truncated,
            @JsonProperty("elapsed_ms") long elapsedMs) {}

    public record MetricsCatalogDimension(
            String name,
            String label,
            @JsonProperty("high_cardinality") boolean highCardinality) {}

    public record MetricsCatalogMeasure(
            String name,
            String label,
            String unit,
            @JsonProperty("gap_fill") String gapFill,
            List<String> companions) {}

    public record MetricsCatalogView(
            String name,
            String label,
            String description,
            List<MetricsCatalogDimension> dimensions,
            List<MetricsCatalogMeasure> measures) {}

    public record MetricsCatalogLimits(
            @JsonProperty("max_window_days") int maxWindowDays,
            @JsonProperty("max_hour_window_days") int maxHourWindowDays,
            @JsonProperty("max_top_n_with_axis") int maxTopNWithAxis,
            @JsonProperty("max_top_n") int maxTopN,
            @JsonProperty("max_measures") int maxMeasures,
            @JsonProperty("max_filters") int maxFilters,
            @JsonProperty("max_filter_values") int maxFilterValues,
            @JsonProperty("max_widgets") int maxWidgets,
            @JsonProperty("max_dashboards") int maxDashboards) {}

    public record MetricsCatalog(
            List<MetricsCatalogView> views, List<String> granularities, MetricsCatalogLimits limits) {}
}
