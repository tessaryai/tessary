// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ops;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * One retention policy — the per-project override of how long a CLASS OF DATA is kept. {@code ttlDays}
 * bounds it; {@code coldAfterDays} is the hot → cold (object-storage) tiering horizon. One policy per
 * {@code (project, data_class)}; {@code RetentionSweeper} enforces it hourly.
 *
 * <p>The column was called {@code signal} until 0095, which was a collision rather than a description: a
 * retention class is not a detector, and every other {@code signal} in this schema became
 * {@code classifier} in 0093.
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
     * The data classes a retention policy can govern.
     *
     * <p>{@code "verdicts"} was one until Track A removed grading and the {@code verdict} table.
     * Changeset 0016 deletes any {@code retention_policy} row still carrying it and narrows
     * {@code retention_policy_data_class_check}, so the string is rejected at the database rather than
     * silently accepted into a class nothing sweeps. {@code "embeddings"} was another, until 0017 removed
     * the vector substrate (#1116) the same way: delete the rows, narrow the CHECK.
     */
    public static final class DataClass {
        private DataClass() {}

        public static final String TRACES = "traces";

        /**
         * The per-classifier detection tables (0088). Added by 0095: the six tables have accumulated
         * per-span rows at ingest rate since the cutover with no class covering them, so the honest
         * answer to "how long do you keep this" was "forever" and nobody had been asked.
         */
        public static final String DETECTIONS = "detections";
    }
}
