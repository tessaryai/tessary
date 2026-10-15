// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Debug DTOs for one classifier: what the platform already computes but the product does not render. Read-only, from
 * tables the product surface already reads.
 */
public final class ClassifierDebugDtos {

    private ClassifierDebugDtos() {}

    /**
     * {@code metricBaselines} is null outside the metric-drift family, not empty, so "not this family" differs from
     * "nothing fitted yet".
     */
    public record ClassifierDebugView(
            String id,
            String detector,
            String family,
            SweepView sweep,
            @JsonProperty("metric_baselines") @Nullable List<MetricBaselineView> metricBaselines) {

        /** {@code BuiltInClassifierCatalog}'s tiers. */
        public static final class Family {
            private Family() {}

            public static final String DETERMINISTIC = "deterministic";
            public static final String ENCODER = "encoder";
            public static final String DECISION = "decision";
            public static final String METRIC_DRIFT = "metric_drift";
        }
    }

    /** A superset of {@code ClassifierHealthView}: the cursor and lease answer "why is this quiet". */
    public record SweepView(
            @Nullable String status,
            int attempts,
            @JsonProperty("last_error") @Nullable String lastError,
            @JsonProperty("cursor_at") @Nullable String cursorAt,
            @JsonProperty("cursor_id") @Nullable String cursorId,
            @JsonProperty("lease_owner") @Nullable String leaseOwner,
            @JsonProperty("lease_expires_at") @Nullable String leaseExpiresAt,
            @JsonProperty("updated_at") @Nullable String updatedAt) {

        public static SweepView neverSwept() {
            return new SweepView(null, 0, null, null, null, null, null, null);
        }
    }

    /** Not exposed by any other endpoint; see {@code MetricBaselineRepository}. */
    public record MetricBaselineView(
            String measure,
            @JsonProperty("bucket_kind") String bucketKind,
            @JsonProperty("bucket_key") String bucketKey,
            String state,
            @JsonProperty("w1_floor") double w1Floor,
            @JsonProperty("current_count") long currentCount,
            @JsonProperty("current_opened_at") @Nullable String currentOpenedAt,
            @JsonProperty("last_event_at") @Nullable String lastEventAt,
            @JsonProperty("pinned_at") @Nullable String pinnedAt,
            @Nullable SketchSummary pinned,
            @Nullable SketchSummary prev,
            @Nullable SketchSummary current) {}

    public record SketchSummary(
            long count, @JsonProperty("grid_id") String gridId) {}
}
