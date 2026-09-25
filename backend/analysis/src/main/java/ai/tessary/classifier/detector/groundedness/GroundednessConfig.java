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
 * The Groundedness classifier's operating point: the flag threshold on P(unsupported), and the dials of the
 * rate test that watches flagged traces per call site. Its own record with its own key names, clamped on
 * construction, as {@code FrustrationConfig} is, because the rate test counts traces where tool_error counts
 * tool calls. {@link #engine()} is the {@link ToolErrorConfig} the shared engine runs on.
 *
 * @param threshold P(unsupported) at or above which an answer is flagged
 * @param arlTarget traces a healthy call site should run between false alarms
 * @param minDecisionInterval the lower clamp on the derived alarm threshold {@code h}
 * @param shiftMultiple the multiple of the learned rate the test is tuned to catch quickly
 * @param shiftFloor the smallest absolute rise in the flagged-trace rate worth arming for
 * @param minBaselineTraces traces the learned reference must hold before anything is judged
 * @param freezeBaselineTraces traces the reference keeps learning up to, judging all the while; never below
 *     {@code minBaselineTraces}
 */
public record GroundednessConfig(
        double threshold,
        long arlTarget,
        double minDecisionInterval,
        double shiftMultiple,
        double shiftFloor,
        int minBaselineTraces,
        int freezeBaselineTraces) {

    /**
     * The flag cutoff on P(unsupported): the 2% false-alarm point on RAGTruth test, read with thresholds
     * cross-validated by response. One band: an answer at or above it is flagged, one below is scored clean.
     */
    public static final double DEFAULT_THRESHOLD = 0.975;

    /**
     * Traces a healthy call site runs between false alarms. The model's false-alarm rate depends on the
     * domain, and the reference learns that rate per call site, so the budget is spent only on the test's
     * own noise.
     */
    public static final long DEFAULT_ARL_TARGET = 50_000L;

    /** The floor on {@code h}, frustration's: it binds only on a call site whose learned rate is near zero. */
    public static final double DEFAULT_MIN_DECISION_INTERVAL = 4.0;

    /** A doubling, as tool_error and frustration. */
    public static final double DEFAULT_SHIFT_MULTIPLE = 2.0;

    /** Two traces in a hundred: the model's own false-alarm rate on RAGTruth at the default threshold. */
    public static final double DEFAULT_SHIFT_FLOOR = 0.02;

    /** Traces the reference holds before a call site is judged. */
    public static final int DEFAULT_MIN_BASELINE_TRACES = 200;

    /**
     * Traces the reference keeps learning up to. Simulated, judging from 200 while learning to 1,000 gives
     * about a 1% chance of a false finding while learning and afterwards matches waiting for 1,000, where
     * freezing at 200 gives about four times the false findings.
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
     * Parse a classifier's blob, falling back to the default for any key it does not carry and to every
     * default for a blob that does not parse. {@code threshold_high} is read only for a blob written before
     * v7 named the cutoff {@code threshold}.
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

    /**
     * The shared engine's config. The keys this classifier does not set stay at the engine's defaults; none of
     * them affects the up arm, which is the only one filed.
     */
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

    /** The hash of the model, the input layout and the threshold, stamped on every assessment row it scores. */
    public String scorerVersion() {
        return GroundednessDetector.scorerVersion(threshold);
    }

    /**
     * The schema version handed to {@link ToolErrorTrend#sweep(java.util.List, ToolErrorConfig, java.util.Map,
     * java.util.Map, String)}: the engine's own, plus what the engine's epoch does not already carry. A flag
     * under one scorer and under another are different events, and a different floor is a different threshold.
     */
    public String schemaVersion() {
        return String.join(
                "|",
                ToolErrorTrend.STATE_SCHEMA_VERSION,
                "groundedness",
                String.format(Locale.ROOT, "%.2f", minDecisionInterval),
                scorerVersion());
    }

    /** The epoch a {@code groundedness_state} row built under this config carries. */
    public String stateEpoch() {
        return CarriedState.epochOf(engine(), schemaVersion());
    }
}
