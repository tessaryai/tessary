// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * One {@code metric_rollup} row. {@code projectId} is nullable for the schema's org grain, though the worker writes per
 * project. @param value non-negative count or token sum @param bucketStart start of the
 * {@code [bucketStart, bucketStart+unit)} window @param granularity {@code hour} or {@code day}
 */
public record MetricRollupRow(
        String id,
        @JsonProperty("org_id") String orgId,
        @JsonProperty("project_id") @Nullable String projectId,
        @JsonProperty("metric") String metric,
        long value,
        @JsonProperty("bucket_start") String bucketStart,
        @JsonProperty("granularity") String granularity,
        @JsonProperty("created_at") String createdAt) {}
