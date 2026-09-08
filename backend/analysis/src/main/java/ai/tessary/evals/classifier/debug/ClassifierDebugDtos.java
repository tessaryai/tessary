// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.debug;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the classifier debug surface — everything the platform already computes for one
 * classifier but the product surface (the {@code ClassifierRail} detail panel) does not render.
 * Snake_case on the wire, camelCase in Java, mirroring {@code ClassifierDtos}.
 *
 * <p>Deliberately read-only and additive: every field here is sourced from a table the production
 * classifier surface already reads from ({@code job}, {@code metric_baseline}, {@code
 * behavior_profile}) — nothing is computed fresh and nothing is written.
 */
public final class ClassifierDebugDtos {

    private ClassifierDebugDtos() {}

    /**
     * The debug bundle for one classifier. {@code family} selects which of {@code metricBaselines} /
     * {@code behaviorProfiles} is populated — the other is {@code null}, not an empty list, so a
     * reader can tell "not this family" apart from "this family, nothing fitted yet".
     */
    public record ClassifierDebugView(
            String id,
            String detector,
            String family,
            SweepView sweep,
            @JsonProperty("metric_baselines") @Nullable List<MetricBaselineView> metricBaselines,
            @JsonProperty("behavior_profiles") @Nullable List<BehaviorProfileDebugView> behaviorProfiles) {

        /** The three families the backend actually dispatches on — {@code BuiltInClassifierCatalog}'s tiers. */
        public static final class Family {
            private Family() {}

            public static final String DETERMINISTIC = "deterministic";
            public static final String ENCODER = "encoder";
            public static final String BEHAVIOR_DRIFT = "behavior_drift";
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

    /**
     * One {@code behavior_profile} row's fitted internals — {@code alphabetSize}/{@code maxOrder}/
     * {@code discoveryRate}/{@code thresholdD2} already reach the product via {@code
     * BehaviorDtos.BehaviorProfileView}; the raw {@code reservoirJson}/{@code fitCarryJson}/{@code
     * rareSymbolsJson} blobs do not, and are the only genuinely new fields here.
     */
    public record BehaviorProfileDebugView(
            @JsonProperty("call_site_id") String callSiteId,
            String state,
            @JsonProperty("opened_at") String openedAt,
            @JsonProperty("armed_at") @Nullable String armedAt,
            @JsonProperty("trace_count") long traceCount,
            @JsonProperty("alphabet_size") int alphabetSize,
            @JsonProperty("max_order") int maxOrder,
            @JsonProperty("discovery_rate") @Nullable Double discoveryRate,
            @JsonProperty("threshold_d2") @Nullable Double thresholdD2,
            @JsonProperty("last_trace_at") @Nullable String lastTraceAt,
            @JsonProperty("reservoir_json") @Nullable String reservoirJson,
            @JsonProperty("fit_carry_json") @Nullable String fitCarryJson,
            @JsonProperty("rare_symbols_json") @Nullable String rareSymbolsJson) {}
}
