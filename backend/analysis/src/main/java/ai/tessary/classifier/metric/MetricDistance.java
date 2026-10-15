// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

/**
 * W₁ on log values, so {@code e^W₁} is the multiplicative shift and means the same for a 200 ms call and a 40 s run.
 * Deliberately not a p-value: significance inflates with sample size, effect size does not. The CDF sum is exact at bin
 * edges; edge counters take part as one slot each, understating (never overstating) an out-of-range shift. Direction
 * comes off the mean, and both directions alarm alike.
 */
public final class MetricDistance {

    private MetricDistance() {}

    /**
     * Returns 0 when either sketch is empty, unreachable in production behind {@code min_sample}. A pure spread change
     * with equal means reports positive. @throws IllegalArgumentException on different grids, asserted rather than
     * resampled
     */
    public static double signedW1(MetricReading ref, MetricReading cur) {
        if (!ref.gridId().equals(cur.gridId())) {
            throw new IllegalArgumentException(
                    "cannot compare sketches on different grids: " + ref.gridId() + " vs " + cur.gridId());
        }
        if (ref.count() == 0 || cur.count() == 0) return 0.0;

        double[] refCdf = ref.cdf();
        double[] curCdf = cur.cdf();

        double absolute = 0.0;
        for (int i = 0; i < refCdf.length; i++) {
            absolute += Math.abs(refCdf[i] - curCdf[i]);
        }
        double magnitude = absolute * ref.slotWidthLog();
        if (magnitude == 0.0) return 0.0;

        return cur.meanLog() < ref.meanLog() ? -magnitude : magnitude;
    }

    /** The evidence carries this and the raw {@code w1_log}, so an eval need not re-derive one from the other. */
    public static double ratio(double signedW1) {
        return Math.exp(signedW1);
    }
}
