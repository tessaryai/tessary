// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Groundedness classifier's operating point, clamped on construction; its own keys because the rate test counts
 * traces where tool_error counts tool calls. @param threshold P(unsupported) at or above which an answer is flagged
 * @param freezeBaselineTraces never below {@code minBaselineTraces}
 */
public record GroundednessConfig(
        double threshold,
        long arlTarget,
        double minDecisionInterval,
        double shiftMultiple,
        double shiftFloor,
        int minBaselineTraces,
        int freezeBaselineTraces) {

    /** The 2% false-alarm point on RAGTruth test, thresholds cross-validated by response. */
    public static final double DEFAULT_THRESHOLD = 0.975;

    /**
     * The reference learns the model's domain-dependent false-alarm rate per call site, so the budget covers only the
     * test's own noise.
     */
    public static final long DEFAULT_ARL_TARGET = 50_000L;

    /** The floor on {@code h}, frustration's: it binds only on a call site whose learned rate is near zero. */
    public static final double DEFAULT_MIN_DECISION_INTERVAL = 4.0;

    public static final double DEFAULT_SHIFT_MULTIPLE = 2.0;

    /** The model's own false-alarm rate on RAGTruth at the default threshold. */
    public static final double DEFAULT_SHIFT_FLOOR = 0.02;

    public static final int DEFAULT_MIN_BASELINE_TRACES = 100;

    /**
     * Simulated on null traffic at 2-10% flagged: judging from 100 while learning to 1,000 costs 0.6-2.6% false
     * findings vs 0.5-2.0% waiting for 200; freezing at 100 roughly doubles false findings over the first 5,000 traces.
     */
    public static final int DEFAULT_FREEZE_BASELINE_TRACES = 1_000;

    public GroundednessConfig {
        threshold = threshold > 0 && threshold < 1 ? threshold : DEFAULT_THRESHOLD;
        arlTarget = arlTarget <= 0 ? DEFAULT_ARL_TARGET : Math.max(1_000L, Math.min(1_000_000_000L, arlTarget));
        minDecisionInterval = !Double.isFinite(minDecisionInterval) || minDecisionInterval <= 0
                ? DEFAULT_MIN_DECISION_INTERVAL
                : Math.max(
                        ToolErrorConfig.LOWEST_DECISION_INTERVAL,
                        Math.min(ToolErrorConfig.MAX_DECISION_INTERVAL, minDecisionInterval));
        shiftMultiple = !Double.isFinite(shiftMultiple) || shiftMultiple <= 0
                ? DEFAULT_SHIFT_MULTIPLE
                : Math.max(1.05, Math.min(100.0, shiftMultiple));
        shiftFloor = !Double.isFinite(shiftFloor) || shiftFloor <= 0
                ? DEFAULT_SHIFT_FLOOR
                : Math.max(0.0001, Math.min(0.5, shiftFloor));
        minBaselineTraces = minBaselineTraces <= 0
                ? DEFAULT_MIN_BASELINE_TRACES
                : Math.max(30, Math.min(1_000_000, minBaselineTraces));
        freezeBaselineTraces = freezeBaselineTraces <= 0
                ? Math.max(minBaselineTraces, DEFAULT_FREEZE_BASELINE_TRACES)
                : Math.max(minBaselineTraces, Math.min(1_000_000, freezeBaselineTraces));
    }

    public static GroundednessConfig defaults() {
        return new GroundednessConfig(
                DEFAULT_THRESHOLD,
                DEFAULT_ARL_TARGET,
                DEFAULT_MIN_DECISION_INTERVAL,
                DEFAULT_SHIFT_MULTIPLE,
                DEFAULT_SHIFT_FLOOR,
                DEFAULT_MIN_BASELINE_TRACES,
                DEFAULT_FREEZE_BASELINE_TRACES);
    }

    /**
     * Falls back per missing key and to all defaults for an unparseable blob. {@code threshold_high} is read only for
     * blobs written before v7 named it {@code threshold}.
     */
    public static GroundednessConfig of(ObjectMapper mapper, @Nullable String configJson) {
        if (configJson == null || configJson.isBlank()) return defaults();
        try {
            JsonNode root = mapper.readTree(configJson);
            JsonNode threshold =
                    root.path("threshold").isNumber() ? root.path("threshold") : root.path("threshold_high");
            return new GroundednessConfig(
                    threshold.asDouble(DEFAULT_THRESHOLD),
                    root.path("arl_target").asLong(DEFAULT_ARL_TARGET),
                    root.path("min_decision_interval").asDouble(DEFAULT_MIN_DECISION_INTERVAL),
                    root.path("shift_multiple").asDouble(DEFAULT_SHIFT_MULTIPLE),
                    root.path("shift_floor").asDouble(DEFAULT_SHIFT_FLOOR),
                    root.path("min_baseline_traces").asInt(DEFAULT_MIN_BASELINE_TRACES),
                    root.path("freeze_baseline_traces").asInt(DEFAULT_FREEZE_BASELINE_TRACES));
        } catch (JsonProcessingException e) {
            return defaults();
        }
    }

    /** Unset keys stay at the engine's defaults; none affects the up arm, the only one filed. */
    public ToolErrorConfig engine() {
        return new ToolErrorConfig(
                arlTarget,
                shiftMultiple,
                shiftFloor,
                minBaselineTraces,
                ToolErrorConfig.DEFAULT_DOWN_ARM_MIN_RATE,
                ToolErrorConfig.DEFAULT_MAX_PATTERNS,
                minDecisionInterval,
                freezeBaselineTraces);
    }

    /** The hash of the model, the input layout and the threshold. */
    public String scorerVersion() {
        return GroundednessDetector.scorerVersion(threshold);
    }

    /** The engine's schema version plus the scorer and floor, which the engine's epoch does not carry. */
    public String schemaVersion() {
        return String.join(
                "|",
                ToolErrorTrend.STATE_SCHEMA_VERSION,
                "groundedness",
                String.format(Locale.ROOT, "%.2f", minDecisionInterval),
                scorerVersion());
    }

    public String stateEpoch() {
        return CarriedState.epochOf(engine(), schemaVersion());
    }
}
