// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ops;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * The per-project override of how long a data class is kept; {@code coldAfterDays} is the cold-tiering horizon. One per
 * {@code (project, data_class)}, enforced hourly by {@code RetentionSweeper}.
 */
public record RetentionPolicyRow(
        String id,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("data_class") String dataClass,
        @JsonProperty("ttl_days") int ttlDays,
        @JsonProperty("cold_after_days") @Nullable Integer coldAfterDays,
        @JsonProperty("created_at") String createdAt,
        @Nullable String attributes) {

    /**
     * Retired classes are rejected by {@code retention_policy_data_class_check}, not silently accepted into a class
     * nothing sweeps.
     */
    public static final class DataClass {
        private DataClass() {}

        public static final String TRACES = "traces";

        /** The per-classifier detection tables. */
        public static final String DETECTIONS = "detections";
    }
}
