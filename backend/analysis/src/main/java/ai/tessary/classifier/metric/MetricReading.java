// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * The read side of a {@link MetricSketch}: what a comparison needs from a summary of {@code log(value)}
 * samples, and nothing that writes to it or persists it. {@link MetricControl}'s resolved control is a
 * reading and not a sketch, because it is a weighted view of the ring rather than an accumulator.
 */
public interface MetricReading {

    /** Samples folded in so far, including those counted in underflow and overflow. */
    long count();

    /**
     * The {@code log(value)} at quantile {@code q}, interpolated within the containing slot, or
     * {@code null} when the sketch is empty. A result sitting exactly on a grid edge means the mass is
     * pinned there and the true quantile is beyond the range — check the edge counters before
     * reporting it.
     */
    @Nullable
    Double quantile(double q);

    /**
     * Mean of the samples, each first clamped to the grid range. The clamp is what keeps this finite
     * when a zero-valued sample contributed {@code -inf}; it also means a sketch pinned at an edge
     * reports the edge rather than a number pulled to infinity by it.
     *
     * <p>{@link MetricDistance} reads this for the <b>sign</b> only. Magnitude comes off the binned
     * CDF, so the two are computed from slightly different views of the same data — they can disagree
     * only when the shift is a fraction of a bin, which is far below any floor a detector would use.
     */
    double meanLog();

    /**
     * Identity of the log grid this sketch is laid out on. Two sketches are comparable if and only if
     * these are equal — {@link MetricDistance} asserts it rather than resampling, because a plausible
     * number computed across two ranges is worse than an exception (a cost sketch and a duration
     * sketch would happily produce a W₁).
     */
    String gridId();

    /** Width of one grid slot in log space, constant by construction. The {@code dx} of the W₁ sum. */
    double slotWidthLog();

    /**
     * The empirical CDF at each slot's upper edge, ascending: {@code [0]} is the underflow slot,
     * {@code [1 .. n]} the bins, and the last entry the overflow slot (so it is always {@code 1.0} for
     * a non-empty sketch). Length is fixed by the grid, so two sketches sharing a {@link #gridId()}
     * return arrays of equal length that line up index for index. Empty sketch → all zeros.
     */
    double[] cdf();
}
