// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.vitals;

import org.jspecify.annotations.Nullable;

/**
 * When a statistic is a concern rather than a number.
 *
 * <p>Every rule compares the window against the project's own immediately-preceding window, so
 * nothing needs per-customer tuning and a project that is simply slow or simply expensive stays quiet
 * until it CHANGES. That is the property a fixed bar cannot have: every agent's normal is different,
 * so a shipped absolute threshold is wrong for almost everyone on day one.
 *
 * <p>Every rule is also volume-aware. Small samples move violently for no reason — three turns, one of
 * them slow, is a p95 built from a single opinion — and a filter that shouts at that is one users learn
 * to ignore. Pure functions, no I/O, so the rules are testable without a database.
 *
 * <p><b>Tool errors are deliberately not here any more.</b> They were, with a two-proportion z-test, and
 * both the statistic and the surface it fed are gone: tool failures are a classifier now
 * ({@code classifiers/tool_error/PROGRAM.md}), watched per tool against that tool's own past rather than
 * per project against last week. Two bars for one fact is exactly the disagreement to avoid, and the
 * resolution taken was one number in one place rather than two that happen to agree today. The z-test did not survive the move on its own merits either — significance inflates with
 * sample size while effect size does not, so on a tool called 200k times a week it reports a 0.05pp move
 * as real (PROGRAM.md §4.3).
 */
public final class VitalsThresholds {

    private VitalsThresholds() {}

    /** Minimum completed turns in both windows before a percentile comparison means anything. */
    static final long MIN_DURATION_SAMPLE = 20;

    /** p95 must be at least this multiple of baseline p95. */
    static final double DURATION_RATIO = 1.20;

    /** Cost per turn must be at least this multiple of baseline. */
    static final double COST_RATIO = 1.25;

    /** Minimum turns in both windows before per-turn cost is compared. */
    static final long MIN_COST_SAMPLE = 20;

    /**
     * Whether p95 turn latency degraded materially.
     *
     * <p>A ratio rather than an absolute bar, because "slow" is only definable against this agent's
     * own normal — a 20-second research turn and a 200ms lookup are both healthy for their call site.
     */
    public static boolean durationWorsened(@Nullable Long p95Ms, @Nullable Long basep95Ms, long turns, long baseTurns) {
        if (p95Ms == null || basep95Ms == null || basep95Ms <= 0) return false;
        if (turns < MIN_DURATION_SAMPLE || baseTurns < MIN_DURATION_SAMPLE) return false;
        return (double) p95Ms / basep95Ms >= DURATION_RATIO;
    }

    /**
     * Whether spend per turn rose materially.
     *
     * <p>Per TURN, not total: total spend rising because traffic doubled is not a concern, it is
     * success. What deserves attention is the same work costing more — a longer prompt, a chattier
     * agent, a model swap, or cache hit rate collapsing.
     */
    public static boolean costWorsened(double usdPerTurn, double baseUsdPerTurn, long turns, long baseTurns) {
        if (turns < MIN_COST_SAMPLE || baseTurns < MIN_COST_SAMPLE) return false;
        // No baseline spend means no ratio. Treating $0 -> anything as an infinite rise would fire on
        // the first priced call a project ever makes.
        return baseUsdPerTurn > 0 && usdPerTurn / baseUsdPerTurn >= COST_RATIO;
    }

    /** The p-th percentile of {@code sorted} (ascending), nearest-rank. Null when empty. */
    public static @Nullable Long percentile(double[] sorted, double p) {
        if (sorted.length == 0) return null;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return Math.round(sorted[Math.max(0, Math.min(sorted.length - 1, idx))]);
    }
}
