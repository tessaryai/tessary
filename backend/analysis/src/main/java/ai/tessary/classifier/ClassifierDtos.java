// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.worker.ClassifierJobRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class ClassifierDtos {

    private ClassifierDtos() {}

    public record ClassifierView(
            String id,
            @JsonProperty("classifier_key") String classifierKey,
            String name,
            @Nullable String description,
            String detector,
            @JsonProperty("config_json") @Nullable String configJson,
            @JsonProperty("built_in") boolean builtIn,
            int version,
            boolean enabled,
            String mode,
            /** The call sites the classifier runs on, or null when it runs on every call site. */
            @JsonProperty("call_site_ids") @Nullable List<String> callSiteIds,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            /**
             * {@link #WAITING_ON_SCHEMAS} on Malformed Output while no call site declares a schema, since a classifier
             * reporting no detections would otherwise read as clean; a {@link ClassifierPause} reason on a paused
             * Frustration.
             */
            @Nullable String readiness,
            /** Whether the trace and session views mark this classifier's flags and offer it in their filter. */
            @JsonProperty("shown_in_trace_views") boolean shownInTraceViews) {

        /** Malformed Output with no call site schema to validate against. Arrives from the connected repo. */
        public static final String WAITING_ON_SCHEMAS = "waiting_on_schemas";

        public static ClassifierView of(ClassifierRow r, @Nullable String readiness, boolean shownInTraceViews) {
            return new ClassifierView(
                    r.id(),
                    r.classifierKey(),
                    r.name(),
                    r.description(),
                    r.detector(),
                    r.configJson(),
                    r.builtIn(),
                    r.version(),
                    r.enabled(),
                    r.mode(),
                    r.callSiteIds(),
                    r.createdAt(),
                    r.updatedAt(),
                    readiness,
                    shownInTraceViews);
        }
    }

    /**
     * {@code severity} is kept for wire-shape compatibility but is always null: no per-detection severity is persisted.
     */
    public record ClassifierEventView(
            String id,
            @JsonProperty("classifier_id") String classifierId,
            @JsonProperty("classifier_version") int classifierVersion,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("session_id") @Nullable String sessionId,
            @JsonProperty("trace_id") @Nullable String traceId,
            @JsonProperty("project_version_id") @Nullable String projectVersionId,
            @Nullable String severity,
            @JsonProperty("evidence_json") @Nullable String evidenceJson,
            @Nullable String confidence,
            @JsonProperty("detected_at") String detectedAt,
            @JsonProperty("occurred_at") @Nullable String occurredAt) {}

    public record SetEnabledRequest(@NotNull Boolean enabled) {}

    /** Limit a classifier to some call sites, or {@code null} to run it on every call site again. Never empty. */
    public record SetCallSitesRequest(
            @JsonProperty("call_site_ids") @Nullable @Size(min = 1)
            List<@NotBlank String> callSiteIds) {}

    /** {@code nextAttemptAt} is null until the queue tracks a backoff stamp; the UI reads null as "next heartbeat". */
    public record ClassifierHealthView(
            @JsonProperty("classifier_id") String classifierId,
            String status,
            int attempts,
            @JsonProperty("max_attempts") int maxAttempts,
            @JsonProperty("last_error") @Nullable String lastError,
            @JsonProperty("last_swept_at") @Nullable String lastSweptAt,
            @JsonProperty("next_attempt_at") @Nullable String nextAttemptAt) {

        public static ClassifierHealthView of(String classifierId, @Nullable ClassifierJobRow job, int maxAttempts) {
            if (job == null) {
                return new ClassifierHealthView(
                        classifierId, ClassifierJobRow.PENDING, 0, maxAttempts, null, null, null);
            }
            return new ClassifierHealthView(
                    classifierId, job.status(), job.attempts(), maxAttempts, job.lastError(), job.updatedAt(), null);
        }
    }

    /**
     * Zero-filled and aligned with {@code days}, oldest first, UTC. {@code counts} buckets by detection time, so a
     * backfill can push a day past {@code trace_totals}.
     */
    public record ClassifierDailyVolumeView(
            List<String> days,
            @JsonProperty("trace_totals") List<Long> traceTotals,
            List<ClassifierDailyCountsView> classifiers) {

        public static ClassifierDailyVolumeView of(ClassifierService.DailyVolume v) {
            return new ClassifierDailyVolumeView(
                    v.days(),
                    box(v.traceTotals()),
                    v.classifiers().stream()
                            .map(c -> new ClassifierDailyCountsView(c.classifierId(), box(c.counts())))
                            .toList());
        }

        private static List<Long> box(long[] values) {
            return java.util.Arrays.stream(values).boxed().toList();
        }
    }

    public record ClassifierDailyCountsView(
            @JsonProperty("classifier_id") String classifierId, List<Long> counts) {}

    /** Read back after {@link MetricDriftConfig}'s clamps. Only these four are editable. */
    public record TuningView(
            @JsonProperty("window_target_count") int windowTargetCount,
            @JsonProperty("window_max_hours") int windowMaxHours,
            @JsonProperty("min_sample") int minSample,
            @JsonProperty("w1_floor") double w1Floor,
            /** Read-only: the consequence of the dial, shown where the dial is set. */
            @JsonProperty("implied_false_alarm_rate") @Nullable
            Double impliedFalseAlarmRate) {

        public static TuningView of(MetricDriftConfig c, @Nullable Double impliedFalseAlarmRate) {
            return new TuningView(
                    c.windowTargetCount(), c.windowMaxHours(), c.minSample(), c.w1Floor(), impliedFalseAlarmRate);
        }
    }

    /** Everything the row shows is read here, so the rules live on the server once. */
    public record GroundednessStatusView(
            String state,
            String mode,
            boolean configured,
            boolean available,
            String reason,
            @JsonProperty("checked_at") @Nullable String checkedAt,
            @JsonProperty("ever_swept") boolean everSwept,
            @JsonProperty("last_scored_at") @Nullable String lastScoredAt,
            @JsonProperty("last_caught_up_at") @Nullable String lastCaughtUpAt,
            @JsonProperty("setup_ref") String setupRef) {}

    /**
     * Every field is clamped server-side. The implied false-alarm rate is reported, not settable: the move is the
     * promise, the rate its consequence.
     */
    public record SetTuningRequest(
            @JsonProperty("window_target_count") @NotNull Integer windowTargetCount,
            @JsonProperty("window_max_hours") @NotNull Integer windowMaxHours,
            @JsonProperty("min_sample") @NotNull Integer minSample,
            @JsonProperty("w1_floor") @NotNull Double w1Floor) {}
}
