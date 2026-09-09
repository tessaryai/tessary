// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTOs for the classifier feature. Snake_case on the wire, camelCase in Java. */
public final class ClassifierDtos {

    private ClassifierDtos() {}

    /** A classifier definition as exposed to the UI / API. */
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
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {

        public static ClassifierView of(ClassifierRow r) {
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
                    r.createdAt(),
                    r.updatedAt());
        }
    }

    /**
     * A classifier <em>detection</em> as exposed to the UI / API, projected from a
     * {@code verdict} ({@code source='automatic'}) JOIN {@code classifier} row — see
     * {@link ClassifierService#events}/{@link ClassifierService#eventsForClassifier}.
     * {@code id} is the verdict id (the detection's stable id); {@code classifierId}
     * / {@code classifierVersion} come from the JOINed definition; {@code subjectId} is the finest-grain subject
     * id named by {@code subjectKind}; {@code traceId} is the owning trace when the subject is a trace or an
     * observation (the Explore deep-link anchor), null for context-grain detections; {@code detectedAt} is
     * {@code verdict.created_at}. {@code severity} is
     * kept for wire-shape compatibility but is always {@code null}: no per-detection severity is persisted.
     */
    public record ClassifierEventView(
            String id,
            @JsonProperty("classifier_id") String classifierId,
            @JsonProperty("classifier_version") int classifierVersion,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("trace_id") @Nullable String traceId,
            @JsonProperty("project_version_id") @Nullable String projectVersionId,
            @Nullable String severity,
            @JsonProperty("evidence_json") @Nullable String evidenceJson,
            @Nullable String confidence,
            @JsonProperty("detected_at") String detectedAt) {}

    /** Toggle the enable/disable lifecycle of a classifier definition. */
    public record SetEnabledRequest(@NotNull Boolean enabled) {}

    /**
     * A per-tool failure rate: for one tool {@code name}, its total/failed call counts and the
     * derived {@code failure_rate} ({@code failed/total}, 0–1). A live aggregation over {@code tool_call},
     * not a stored grain. {@code tool_name} is null for calls whose tool name was not recorded.
     */
    public record ToolErrorRateView(
            @JsonProperty("tool_name") @Nullable String toolName,
            @JsonProperty("total_calls") long totalCalls,
            @JsonProperty("failed_calls") long failedCalls,
            @JsonProperty("failure_rate") double failureRate) {

        public static ToolErrorRateView of(SubstrateReadRepository.ToolErrorRate r) {
            return new ToolErrorRateView(r.toolName(), r.totalCalls(), r.failedCalls(), r.failureRate());
        }
    }

    /** Set the operating point of a classifier: {@code discovery} (high recall) | {@code tracking} (precise). */
    public record SetModeRequest(@NotNull String mode) {}

    /**
     * Sweep-job health for one classifier: makes a failing sweep observable in the product instead
     * of only in Loki. A classifier with no job yet (never enqueued) reports {@link ClassifierJobRow#PENDING} with
     * no failure history. {@code nextAttemptAt} is only populated once the sweep queue tracks a backoff
     * stamp; until then it is always {@code null} — the UI treats a null next-attempt as "next
     * heartbeat", not an error.
     */
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
     * The per-mode detection breakdown for a classifier — the differing precision/recall surfaced
     * per operating point. {@code discovery_fired} counts every detection (high recall);
     * {@code tracking_fired} counts the HIGH-confidence subset a tracking classifier surfaces (precision).
     * The gap is {@code low_confidence}. No precision/recall ratio is reported: the built-ins have no human-label
     * store, so a ratio would be a fabricated number — the count breakdown is the honest surfacing.
     */
    public record ClassifierMetricsView(
            @JsonProperty("classifier_id") String classifierId,
            String mode,
            @JsonProperty("discovery_fired") long discoveryFired,
            @JsonProperty("tracking_fired") long trackingFired,
            @JsonProperty("low_confidence") long lowConfidence) {

        public static ClassifierMetricsView of(ClassifierService.ClassifierMetrics m) {
            return new ClassifierMetricsView(
                    m.classifierId(), m.mode(), m.discoveryFired(), m.trackingFired(), m.lowConfidence());
        }
    }

    /**
     * Per-classifier daily detected-trace volume over the trailing window. UTC calendar days, oldest
     * first, the last bucket being today-so-far; every array is zero-filled and aligned with
     * {@code days}. {@code counts[i]} is the DISTINCT traces the classifier detected on {@code days[i]}
     * (bucketed by <em>detection</em> time — a backfill sweep over history can push a day's count past
     * {@code trace_totals[i]}); {@code trace_totals[i]} is the non-deleted traces <em>created</em> that
     * day, the "% of all traces" denominator (0 ⇒ no rate for that day).
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

    /** One classifier's per-day distinct-trace detection counts, aligned with the parent's {@code days}. */
    public record ClassifierDailyCountsView(
            @JsonProperty("classifier_id") String classifierId, List<Long> counts) {}

    /**
     * The window/threshold operating point for a metric-drift classifier (cost_drift or
     * duration_drift) — the {@link MetricDriftConfig} fields a tenant can tune, read back after
     * {@link MetricDriftConfig}'s own clamps have applied. Only these four are editable; {@code
     * measures}/{@code explained_by_fraction}/{@code settle_seconds}/{@code hist_bins} stay at
     * whatever the catalog or a prior edit set them to.
     */
    public record TuningView(
            @JsonProperty("window_target_count") int windowTargetCount,
            @JsonProperty("window_max_hours") int windowMaxHours,
            @JsonProperty("min_sample") int minSample,
            @JsonProperty("w1_floor") double w1Floor,
            /**
             * What {@code w1_floor} costs in false alarms on THIS project's traffic, or null when no
             * bucket has enough traffic to say. Read-only: it is the consequence of the dial above, shown
             * where the dial is set so that choosing a move is not a decision whose effect only surfaces
             * as noise a week later.
             */
            @JsonProperty("implied_false_alarm_rate") @Nullable
            Double impliedFalseAlarmRate) {

        public static TuningView of(MetricDriftConfig c, @Nullable Double impliedFalseAlarmRate) {
            return new TuningView(
                    c.windowTargetCount(), c.windowMaxHours(), c.minSample(), c.w1Floor(), impliedFalseAlarmRate);
        }
    }

    /**
     * Request body for {@code PUT .../classifiers/{id}/tuning}. Every field is clamped server-side.
     *
     * <p>{@code w1_floor} is the dial: the smallest move worth reporting, at a full window. A thin window
     * is held to more ({@code MetricDriftDetector.effectiveFloor}) because it cannot measure that move
     * reliably. The false-alarm rate it implies is reported back on {@link TuningView} rather than being
     * settable — the move is the promise, and the rate is its consequence.
     */
    public record SetTuningRequest(
            @JsonProperty("window_target_count") @NotNull Integer windowTargetCount,
            @JsonProperty("window_max_hours") @NotNull Integer windowMaxHours,
            @JsonProperty("min_sample") @NotNull Integer minSample,
            @JsonProperty("w1_floor") @NotNull Double w1Floor) {}
}
