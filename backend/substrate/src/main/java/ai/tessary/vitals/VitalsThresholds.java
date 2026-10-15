// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import org.jspecify.annotations.Nullable;

/**
 * When a statistic is a concern. Every rule compares against the project's own preceding window, since a shipped
 * absolute bar is wrong for almost every agent, and every rule is volume-aware because small samples move violently.
 * Tool errors are a per-tool classifier now, not a rule here.
 */
public final class VitalsThresholds {

    private VitalsThresholds() {}

    /** Minimum completed turns in both windows before a percentile comparison means anything. */
    static final long MIN_DURATION_SAMPLE = 20;

    static final double DURATION_RATIO = 1.20;

    static final double COST_RATIO = 1.25;

    /** Minimum turns in both windows before per-turn cost is compared. */
    static final long MIN_COST_SAMPLE = 20;

    /** A ratio, because "slow" is only definable against this agent's own normal. */
    public static boolean durationWorsened(@Nullable Long p95Ms, @Nullable Long basep95Ms, long turns, long baseTurns) {
        if (p95Ms == null || basep95Ms == null || basep95Ms <= 0) return false;
        if (turns < MIN_DURATION_SAMPLE || baseTurns < MIN_DURATION_SAMPLE) return false;
        return (double) p95Ms / basep95Ms >= DURATION_RATIO;
    }

    /** Per turn, not total: total spend rising with traffic is success. */
    public static boolean costWorsened(double usdPerTurn, double baseUsdPerTurn, long turns, long baseTurns) {
        if (turns < MIN_COST_SAMPLE || baseTurns < MIN_COST_SAMPLE) return false;
        // Treating $0 -> anything as an infinite rise would fire on a project's first priced call.
        return baseUsdPerTurn > 0 && usdPerTurn / baseUsdPerTurn >= COST_RATIO;
    }

    /** Nearest-rank; null when empty. */
    public static @Nullable Long percentile(double[] sorted, double p) {
        if (sorted.length == 0) return null;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return Math.round(sorted[Math.max(0, Math.min(sorted.length - 1, idx))]);
    }
}
