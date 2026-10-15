// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * The read side of a {@link MetricSketch}. {@link MetricControl}'s resolved control is a reading, not a sketch, because
 * it is a weighted view rather than an accumulator.
 */
public interface MetricReading {

    /** Including underflow and overflow. */
    long count();

    /**
     * Null when empty. A result exactly on a grid edge means the true quantile is beyond the range; check the edge
     * counters.
     */
    @Nullable
    Double quantile(double q);

    /**
     * Each sample clamped to the grid, which keeps a {@code -inf} sample finite. {@link MetricDistance} reads only the
     * sign; magnitude comes from the binned CDF.
     */
    double meanLog();

    /**
     * Two sketches are comparable iff these are equal; {@link MetricDistance} asserts it rather than produce a
     * plausible cross-range number.
     */
    String gridId();

    /** The {@code dx} of the W₁ sum. */
    double slotWidthLog();

    /**
     * CDF at each slot's upper edge: {@code [0]} underflow, {@code [1 .. n]} bins, last overflow (always {@code 1.0}
     * when non-empty). Equal grids give index-aligned arrays; empty is all zeros.
     */
    double[] cdf();
}
