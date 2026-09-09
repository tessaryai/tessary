// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * One aggregated billable value for a (scope, unit, time bucket) — a {@code metric_rollup} row.
 * {@code projectId} is nullable to match the schema's org-grain reservation; the worker writes a
 * per-project row (see {@code MeteringWorker} / {@code MetricRollupRepository}).
 *
 * @param value the aggregated count (or token SUM) — non-negative
 * @param bucketStart ISO-8601 start of the {@code [bucketStart, bucketStart+unit)} window this counts
 * @param granularity the bucket grain ({@code hour} or {@code day})
 * @param dimensions a JSON bag of extra metric dimensions (empty for the usage metrics)
 * @param attributes a free-form JSON attributes bag
 */
public record MetricRollupRow(
        String id,
        @JsonProperty("org_id") String orgId,
        @JsonProperty("project_id") @Nullable String projectId,
        @JsonProperty("metric") String metric,
        long value,
        @JsonProperty("bucket_start") String bucketStart,
        @JsonProperty("granularity") String granularity,
        @Nullable String dimensions,
        @Nullable String attributes,
        @JsonProperty("created_at") String createdAt) {

    /** Build a rollup with no extra dimensions/attributes — the usage-metering shape. */
    public static MetricRollupRow of(
            String id,
            String orgId,
            @Nullable String projectId,
            String metric,
            long value,
            String bucketStart,
            String granularity,
            String createdAt) {
        return new MetricRollupRow(
                id, orgId, projectId, metric, value, bucketStart, granularity, "{}", "{}", createdAt);
    }
}
