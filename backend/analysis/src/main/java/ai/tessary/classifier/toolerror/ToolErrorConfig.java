// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The {@code tool_error} operating point (tool-error.md §4, §9; arithmetic in deviation-math.md, which must change with
 * any default). Per-project, clamped, every default EXPERIMENT(tool-error-tuning), and deliberately separate from
 * metric drift's dials, which measure different quantities. {@code arlTarget} is the only threshold dial;
 * {@code freezeBaselineCalls} is how long the reference keeps learning after judging starts;
 * {@code referenceSettleHours} how long an hour waits, on the key's own event clock, before it may join it.
 */
public record ToolErrorConfig(
        long arlTarget,
        double shiftMultiple,
        double shiftFloor,
        int minBaselineCalls,
        double downArmMinRate,
        int maxPatterns,
        double minDecisionInterval,
        int freezeBaselineCalls,
        int referenceSettleHours) {

    /**
     * EXPERIMENT(tool-error-tuning): the one dial that means something to a person; {@code h} is derived per tool by
     * {@link #decisionIntervalFor(double)} so every tool cries wolf at about the same rate.
     */
    public static final long DEFAULT_ARL_TARGET = 250_000L;

    /**
     * EXPERIMENT(tool-error-tuning): {@code h = intercept + slope·ln(p0)}, fitted to Brook–Evans exact thresholds; it
     * holds ARL₀ within 220k-309k of the 250k target where a flat threshold spans 44x. Below about 0.5%
     * {@link #MIN_DECISION_INTERVAL} takes over. Assumes independent trials; real failures are bursty, so a null run
     * will likely push these up.
     */
    public static final double ARL_FIT_INTERCEPT = 11.42;

    public static final double ARL_FIT_SLOPE = 1.088;

    /**
     * Below 6 noise fires the alarm; above 12 no replayable corpus can show the detector works. A classifier counting
     * conversations may set a lower floor (frustration's is 4).
     */
    public static final double MIN_DECISION_INTERVAL = 6.0;

    public static final double LOWEST_DECISION_INTERVAL = 2.0;

    public static final double MAX_DECISION_INTERVAL = 12.0;

    /** EXPERIMENT(tool-error-tuning): not a threshold; it sets where the detector is most sensitive. */
    public static final double DEFAULT_SHIFT_MULTIPLE = 2.0;

    /**
     * EXPERIMENT(tool-error-tuning): makes a tool that never failed detectable at all, since twice nearly-zero is still
     * nearly-zero.
     */
    public static final double DEFAULT_SHIFT_FLOOR = 0.005;

    /** EXPERIMENT(tool-error-tuning): a wait, not a skip. About the least from which 1% can be told from 2%. */
    public static final int DEFAULT_MIN_BASELINE_CALLS = 500;

    /** EXPERIMENT(tool-error-tuning): falling failures matter only when there were errors to lose. */
    public static final double DEFAULT_DOWN_ARM_MIN_RATE = 0.01;

    public static final int DEFAULT_MAX_PATTERNS = 8;

    /**
     * EXPERIMENT(tool-error-tuning): hours after an hour ends, on the key's own event clock, before it may join the
     * reference; late spans and labels land inside it.
     */
    public static final int DEFAULT_REFERENCE_SETTLE_HOURS = 1;

    public static final int MAX_REFERENCE_SETTLE_HOURS = 168;

    /** Clamped: the blob is operator-editable, and nonsense could mean permanent silence or an hourly false alarm. */
    public ToolErrorConfig {
        // Far outside the range the fit was measured over at both ends.
        arlTarget = clampLong(arlTarget, 1_000L, 1_000_000_000L, DEFAULT_ARL_TARGET);
        // Strictly above 1, or the log-likelihood ratio is identically zero.
        shiftMultiple = clamp(shiftMultiple, 1.05, 100.0, DEFAULT_SHIFT_MULTIPLE);
        shiftFloor = clamp(shiftFloor, 0.0001, 0.5, DEFAULT_SHIFT_FLOOR);
        minBaselineCalls = clampInt(minBaselineCalls, 30, 1_000_000, DEFAULT_MIN_BASELINE_CALLS);
        downArmMinRate = clamp(downArmMinRate, 0.0001, 0.5, DEFAULT_DOWN_ARM_MIN_RATE);
        maxPatterns = clampInt(maxPatterns, 1, ToolErrorRate.MAX_PATTERNS, DEFAULT_MAX_PATTERNS);
        minDecisionInterval =
                clamp(minDecisionInterval, LOWEST_DECISION_INTERVAL, MAX_DECISION_INTERVAL, MIN_DECISION_INTERVAL);
        // A reference that froze before judging could start would never judge.
        freezeBaselineCalls = clampInt(freezeBaselineCalls, minBaselineCalls, 1_000_000, minBaselineCalls);
        // Zero is meaningful (learn as soon as the hour has ended), so only a negative value is unset.
        referenceSettleHours = referenceSettleHours < 0
                ? DEFAULT_REFERENCE_SETTLE_HOURS
                : Math.min(MAX_REFERENCE_SETTLE_HOURS, referenceSettleHours);
    }

    public ToolErrorConfig(
            long arlTarget,
            double shiftMultiple,
            double shiftFloor,
            int minBaselineCalls,
            double downArmMinRate,
            int maxPatterns,
            double minDecisionInterval,
            int freezeBaselineCalls) {
        this(
                arlTarget,
                shiftMultiple,
                shiftFloor,
                minBaselineCalls,
                downArmMinRate,
                maxPatterns,
                minDecisionInterval,
                freezeBaselineCalls,
                DEFAULT_REFERENCE_SETTLE_HOURS);
    }

    public ToolErrorConfig(
            long arlTarget,
            double shiftMultiple,
            double shiftFloor,
            int minBaselineCalls,
            double downArmMinRate,
            int maxPatterns,
            double minDecisionInterval) {
        this(
                arlTarget,
                shiftMultiple,
                shiftFloor,
                minBaselineCalls,
                downArmMinRate,
                maxPatterns,
                minDecisionInterval,
                minBaselineCalls);
    }

    public ToolErrorConfig(
            long arlTarget,
            double shiftMultiple,
            double shiftFloor,
            int minBaselineCalls,
            double downArmMinRate,
            int maxPatterns) {
        this(
                arlTarget,
                shiftMultiple,
                shiftFloor,
                minBaselineCalls,
                downArmMinRate,
                maxPatterns,
                MIN_DECISION_INTERVAL);
    }

    /** What a stored accumulator's epoch has to say; see {@link CarriedState#epochOf}. */
    public boolean learnsWhileJudging() {
        return freezeBaselineCalls > minBaselineCalls;
    }

    /**
     * Derived, never configured: four failures in a row is damning at 0.1% and a Tuesday at 20%. The base-rate term is
     * the fit; the budget term is exact, since ARL₀ rises one e-fold per unit of {@code h} across the range.
     */
    public double decisionIntervalFor(double p0) {
        if (!Double.isFinite(p0) || p0 <= 0) return minDecisionInterval;
        double h = ARL_FIT_INTERCEPT + ARL_FIT_SLOPE * Math.log(p0) + Math.log((double) arlTarget / DEFAULT_ARL_TARGET);
        return Math.max(minDecisionInterval, Math.min(MAX_DECISION_INTERVAL, h));
    }

    public static ToolErrorConfig defaults() {
        return new ToolErrorConfig(
                DEFAULT_ARL_TARGET,
                DEFAULT_SHIFT_MULTIPLE,
                DEFAULT_SHIFT_FLOOR,
                DEFAULT_MIN_BASELINE_CALLS,
                DEFAULT_DOWN_ARM_MIN_RATE,
                DEFAULT_MAX_PATTERNS);
    }

    /** Unreadable JSON yields the defaults, so a newer build's blob cannot dead-letter an older sweep. */
    public static ToolErrorConfig of(ObjectMapper mapper, @Nullable String configJson) {
        if (configJson == null || configJson.isBlank()) return defaults();
        try {
            JsonNode root = mapper.readTree(configJson);
            return new ToolErrorConfig(
                    root.path("arl_target").asLong(DEFAULT_ARL_TARGET),
                    root.path("shift_multiple").asDouble(DEFAULT_SHIFT_MULTIPLE),
                    root.path("shift_floor").asDouble(DEFAULT_SHIFT_FLOOR),
                    root.path("min_baseline_calls").asInt(DEFAULT_MIN_BASELINE_CALLS),
                    root.path("down_arm_min_rate").asDouble(DEFAULT_DOWN_ARM_MIN_RATE),
                    root.path("max_patterns").asInt(DEFAULT_MAX_PATTERNS),
                    MIN_DECISION_INTERVAL,
                    // Absent: the reference freezes the moment judging starts.
                    root.path("freeze_baseline_calls").asInt(0),
                    root.path("reference_settle_hours").asInt(DEFAULT_REFERENCE_SETTLE_HOURS));
        } catch (JsonProcessingException e) {
            return defaults();
        }
    }

    private static int clampInt(int v, int lo, int hi, int fallback) {
        if (v <= 0) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clampLong(long v, long lo, long hi, long fallback) {
        if (v <= 0) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi, double fallback) {
        if (!Double.isFinite(v) || v <= 0) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }
}
