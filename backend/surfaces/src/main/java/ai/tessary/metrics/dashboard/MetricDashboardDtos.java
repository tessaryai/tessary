// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire shapes of the dashboard endpoints. The built-in dashboard has null clocks and no author. */
public final class MetricDashboardDtos {

    private MetricDashboardDtos() {}

    public record MetricDashboardSummary(
            String id,
            String name,
            String description,
            @JsonProperty("built_in") boolean builtIn,
            @JsonProperty("widget_count") int widgetCount,
            @JsonProperty("updated_at") @Nullable String updatedAt) {}

    public record MetricDashboardList(
            List<MetricDashboardSummary> dashboards,
            @JsonProperty("can_manage") boolean canManage) {}

    public record MetricDashboard(
            String id,
            String name,
            String description,
            @JsonProperty("built_in") boolean builtIn,
            MetricDashboardDefinition definition,
            @JsonProperty("created_by") @Nullable String createdBy,
            @JsonProperty("created_at") @Nullable String createdAt,
            @JsonProperty("updated_at") @Nullable String updatedAt,
            @JsonProperty("can_manage") boolean canManage) {}

    public record MetricDashboardCreateRequest(
            @NotBlank @Size(max = 80) String name,
            @Nullable @Size(max = 500) String description,
            @Nullable @Valid MetricDashboardDefinition definition,
            @JsonProperty("clone_of") @Nullable String cloneOf) {}

    public record MetricDashboardUpdateRequest(
            @NotBlank @Size(max = 80) String name,
            @Nullable @Size(max = 500) String description,
            @NotNull @Valid MetricDashboardDefinition definition) {}
}
