// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

/**
 * Signed Wasserstein-1 distance between two {@link MetricSketch}es — the entire statistic behind
 * metric drift ({@code classifiers/metric_drift/PROGRAM.md} §4). Given a bucket's reference window and
 * its current one, this is the single number the detector thresholds and the finding reports.
 *
 * <h2>Why W₁ on logs, and not a test</h2>
 *
 * <p>Because the sketches hold {@code log(value)}, {@code e^W₁} is the <b>multiplicative</b> shift, so
 * the finding sentence writes itself: a W₁ of {@code 0.336} is "1.4× slower", in a unit that means the
 * same thing for a 200 ms tool call and a 40-second research run. A distance on raw milliseconds would
 * not — it would report the busiest, slowest call site forever and nothing else.
 *
 * <p>It is deliberately <b>not</b> a p-value. A KS test over a 100k-trace window calls a
 * three-millisecond shift significant, because significance inflates with sample size while effect size
 * does not. PROGRAM.md §4.2 names that as the single most common way distribution monitoring fails in
 * production; W₁ is an effect size, and the detector's floor is set on the same scale a human reads.
 *
 * <h2>The arithmetic</h2>
 *
 * <p>In general {@code W₁(F, G) = ∫ |F(x) − G(x)| dx}. On a shared log grid the two CDFs are sampled at
 * the same slot edges and every slot is the same {@code ln(r)} wide, so the integral collapses to a sum:
 *
 * <pre>{@code W₁ = ln(r) · Σ_i |CDF_ref(i) − CDF_cur(i)|}</pre>
 *
 * <p>This is exact at the edges rather than approximate: a histogram's cumulative count at a bin edge is
 * the true number of samples below that edge, so {@code cdf()[i]} is the empirical CDF evaluated there
 * with no binning error at all. The only discretization is the {@code ln(r)}-wide step of the sum, and
 * because {@code |F − G|} rises from zero and returns to zero the leading error terms cancel — measured
 * against a known shift the sum lands well inside one slot width, which is what
 * {@code MetricDistanceTest} pins.
 *
 * <p>The two edge counters take part as one slot each, so a bucket that moved <i>out</i> of range still
 * registers a shift. It understates that shift (the mass could have gone anywhere beyond the edge) and
 * never overstates it — the honest direction to err, and the reason the counters exist at all rather
 * than the samples being clipped into the end bins.
 *
 * <h2>Sign</h2>
 *
 * <p>Magnitude comes off the binned CDFs; <b>direction comes off the mean</b>, because W₁ is a distance
 * and distances have no sign. Positive means the current window sits above its reference — slower, or
 * more expensive. Negative means below. Both alarm at the same bar: faster and cheaper is reported as
 * loudly as slower and more expensive, because an agent that quietly stopped doing its verification step
 * reads as a win on
 * every other dashboard in the product (PROGRAM.md §4.4).
 */
public final class MetricDistance {

    private MetricDistance() {}

    /**
     * Signed W₁ between a reference window and the current one, in log units. Positive means
     * {@code cur} sits above {@code ref}.
     *
     * <p>Returns {@code 0} when either sketch is empty. That is not a claim that the windows agree — it
     * is that there is nothing to compare, and the caller has already declined to act on it: the sweep
     * gates on {@code min_sample} (hundreds of samples) long before an empty window could reach here, so
     * a zero from this path is unreachable in production and exists to keep the pure function total.
     *
     * <p>A non-zero magnitude with exactly equal means is a pure change of spread — the tails moved and
     * the centre did not. It is reported positive, since a widened tail is the operationally interesting
     * reading of that, and the detector's floor gates on the magnitude either way.
     *
     * @param ref the pinned or previously-closed window
     * @param cur the window just closed
     * @throws IllegalArgumentException if the two sketches sit on different grids. Asserted rather than
     *     resampled: a plausible number computed across a duration grid and a cost grid is worse than an
     *     exception, because nothing downstream would ever question it.
     */
    public static double signedW1(MetricSketch ref, MetricSketch cur) {
        if (!ref.gridId().equals(cur.gridId())) {
            throw new IllegalArgumentException(
                    "cannot compare sketches on different grids: " + ref.gridId() + " vs " + cur.gridId());
        }
        if (ref.count() == 0 || cur.count() == 0) return 0.0;

        double[] refCdf = ref.cdf();
        double[] curCdf = cur.cdf();
        if (refCdf.length != curCdf.length) {
            // Equal grid ids are supposed to guarantee equal slot counts; if an implementation ever
            // breaks that, fail here rather than silently comparing the first min(a,b) slots.
            throw new IllegalStateException("grid " + ref.gridId() + " produced CDFs of unequal length: "
                    + refCdf.length + " vs " + curCdf.length);
        }

        double absolute = 0.0;
        for (int i = 0; i < refCdf.length; i++) {
            absolute += Math.abs(refCdf[i] - curCdf[i]);
        }
        double magnitude = absolute * ref.slotWidthLog();
        if (magnitude == 0.0) return 0.0;

        return cur.meanLog() < ref.meanLog() ? -magnitude : magnitude;
    }

    /**
     * The multiplicative shift a finding reports: {@code 1.40} for "1.4× slower", {@code 0.71} for
     * "29% faster". Simply {@code e^signedW1}, so the sign carries through as above-one or below-one.
     *
     * <p>The evidence blob carries this <b>and</b> the raw {@code w1_log} it came from (PROGRAM.md §7).
     * Keeping both is deliberate: the ratio is what a human reads, the raw value is what the threshold
     * was set on, and an eval that has to re-derive one from the other is an eval that can disagree with
     * the detector it is measuring.
     */
    public static double ratio(double signedW1) {
        return Math.exp(signedW1);
    }
}
