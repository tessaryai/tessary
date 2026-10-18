// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import org.jspecify.annotations.Nullable;

/** One {@code metric_dashboard} row; {@code definitionJson} is the stored {@link MetricDashboardDefinition}. */
public record MetricDashboardRow(
        String id,
        String projectId,
        String name,
        String description,
        String definitionJson,
        @Nullable String createdBy,
        String createdAt,
        String updatedAt) {}
