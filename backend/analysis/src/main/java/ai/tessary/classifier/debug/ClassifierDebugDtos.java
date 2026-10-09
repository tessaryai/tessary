// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the classifier debug surface — everything the platform already computes for one
 * classifier but the product surface (the {@code ClassifierRail} detail panel) does not render.
 * Snake_case on the wire, camelCase in Java, mirroring {@code ClassifierDtos}.
 *
 * <p>Deliberately read-only and additive: every field here is sourced from a table the production
 * classifier surface already reads from ({@code job}, {@code metric_baseline}) — nothing is computed
 * fresh and nothing is written.
 */
public final class ClassifierDebugDtos {

    private ClassifierDebugDtos() {}

    /**
     * The debug bundle for one classifier. {@code metricBaselines} is populated for the metric-drift
     * family only, and {@code null} otherwise, not an empty list, so a reader can tell "not this
     * family" apart from "this family, nothing fitted yet".
     */
    public record ClassifierDebugView(
            String id,
            String detector,
            String family,
            SweepView sweep,
            @JsonProperty("metric_baselines") @Nullable List<MetricBaselineView> metricBaselines) {

        /** The families the backend actually dispatches on — {@code BuiltInClassifierCatalog}'s tiers. */
        public static final class Family {
            private Family() {}

            public static final String DETERMINISTIC = "deterministic";
            public static final String ENCODER = "encoder";
            public static final String DECISION = "decision";
            public static final String METRIC_DRIFT = "metric_drift";
        }
    }

    /**
     * The sweep-job row in full — a superset of {@code ClassifierHealthView}, which reports only
     * {@code status}/{@code attempts}/{@code lastError}/{@code lastSweptAt}. The cursor and lease are
     * the fields a "why is this quiet" question actually needs.
     */
    public record SweepView(
            @Nullable String status,
            int attempts,
            @JsonProperty("last_error") @Nullable String lastError,
            @JsonProperty("cursor_at") @Nullable String cursorAt,
            @JsonProperty("cursor_id") @Nullable String cursorId,
            @JsonProperty("lease_owner") @Nullable String leaseOwner,
            @JsonProperty("lease_expires_at") @Nullable String leaseExpiresAt,
            @JsonProperty("updated_at") @Nullable String updatedAt) {

        /** The honest empty state for a signal whose sweep has never been enqueued. */
        public static SweepView neverSwept() {
            return new SweepView(null, 0, null, null, null, null, null, null);
        }
    }

    /**
     * One {@code metric_baseline} row — cost_drift/duration_drift's per-(bucket × measure) window
     * state. Not exposed by any other endpoint today; see {@code MetricBaselineRepository}.
     */
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

    /** A sketch reduced to what a debug reader needs: how many samples, and whether it's comparable. */
    public record SketchSummary(
            long count, @JsonProperty("grid_id") String gridId) {}
}
