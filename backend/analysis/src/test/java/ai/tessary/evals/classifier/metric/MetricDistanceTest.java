// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link MetricDistance}. No Spring, no database, no project concepts — this is
 * arithmetic over two sketches and nothing else.
 *
 * <p>{@link #knownMultiplicativeShiftIsRecoveredWithinOneSlot()} is the test that proves the whole
 * statistic. Every claim metric drift makes to a human rests on {@code e^W₁} being the multiplicative
 * shift, so if scaling a real, skewed sample set by a known factor does not come back out as
 * {@code ln(factor)}, nothing built on top of it means anything and no amount of threshold tuning would
 * reveal it. The rest of this class is bookkeeping by comparison.
 *
 * <p>Traffic is a log-normal (σ = 0.8 in log space, median 2 s) drawn from a seeded {@link Random} —
 * {@code java.util.Random} is specified bit-for-bit, so these are exact fixtures, not approximately
 * reproducible ones. Log-normal because that is what real latency looks like: a tight body and a long
 * right tail.
 */
class MetricDistanceTest {

    /** One slot of the duration grid, {@code ln(1.05) ≈ 0.0488}. The tolerance every shift is held to. */
    private static final double SLOT = Math.log(MetricHistogram.DEFAULT_RATIO);

    private static final int SAMPLES = 20_000;

    /** A seeded log-normal in milliseconds: median 2 s, σ = 0.8 in log space, so p99 ≈ 13 s. */
    private static double[] traffic(long seed) {
        Random random = new Random(seed);
        double[] out = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            out[i] = 2000.0 * Math.exp(0.8 * random.nextGaussian());
        }
        return out;
    }

    private static MetricHistogram sketchOf(double[] values, double factor) {
        MetricHistogram histogram = new MetricHistogram(MetricHistogram.Grid.duration());
        for (double value : values) {
            histogram.add(Math.log(value * factor));
        }
        return histogram;
    }

    /**
     * <b>The load-bearing test.</b> Scale every sample by a known factor and the signed W₁ must come back
     * as {@code ln(factor)}, within the resolution of one grid slot. Run at 1.1×, 1.4× and 3× so the
     * claim holds for a shift smaller than the detector's floor, one at it, and one far past it — a
     * distance that only worked in the middle of its range would be a coincidence.
     *
     * <p>Both directions, at the same tolerance: a 1.4× speed-up is measured exactly as well as a 1.4×
     * slowdown, which is what PROGRAM.md §4.4 requires of a detector meant to notice an agent that
     * quietly stopped doing work.
     */
    @Test
    void knownMultiplicativeShiftIsRecoveredWithinOneSlot() {
        double[] values = traffic(20260730L);
        MetricHistogram reference = sketchOf(values, 1.0);

        for (double factor : new double[] {1.1, 1.4, 3.0}) {
            double slower = MetricDistance.signedW1(reference, sketchOf(values, factor));
            assertEquals(
                    Math.log(factor),
                    slower,
                    SLOT,
                    "W1 must recover ln(" + factor + ") = " + Math.log(factor) + " within one slot");
            // One slot of W1 error is 5% of the ratio by construction (e^ln(1.05)); allow a shade more so
            // the assertion fails on a broken formula rather than on landing exactly on the resolution.
            assertEquals(factor, MetricDistance.ratio(slower), factor * 0.06, "e^W1 is the reported ratio");

            double faster = MetricDistance.signedW1(reference, sketchOf(values, 1.0 / factor));
            assertEquals(-Math.log(factor), faster, SLOT, "a shift down is measured on exactly the same scale");
            assertTrue(faster < 0, "the current window sitting below its reference is negative");
        }
    }

    /** A window compared against itself is zero, with no sign. The null case, and the floor's anchor. */
    @Test
    void identicalSketchesAreExactlyZero() {
        double[] values = traffic(1L);
        double w1 = MetricDistance.signedW1(sketchOf(values, 1.0), sketchOf(values, 1.0));

        assertEquals(0.0, w1, 0.0, "identical inputs must be exactly zero, not nearly");
        assertEquals(0.0, Math.signum(w1), 0.0);
        assertEquals(1.0, MetricDistance.ratio(w1), 0.0, "no shift is a ratio of exactly 1");
    }

    /**
     * Two independent draws from the same distribution — the shape of PLAN.md §9's null run, in
     * miniature. Sampling noise alone must stay far below {@code w1_floor} (0.139 as it stands), or the
     * classifier would fire on ordinary traffic before it ever saw a regression.
     */
    @Test
    void independentDrawsFromOneDistributionStayNearZero() {
        double w1 = MetricDistance.signedW1(sketchOf(traffic(7L), 1.0), sketchOf(traffic(8L), 1.0));

        assertTrue(Math.abs(w1) < 0.05, "same-distribution noise should be well under the floor, was " + w1);
    }

    /**
     * Comparing a duration sketch against a cost sketch throws rather than returning a number. Both are
     * "logs of a positive quantity" and would happily produce a plausible W₁ — which is exactly why this
     * has to be an exception: a silently wrong distance is one nothing downstream would question.
     */
    @Test
    void mismatchedGridsThrowRatherThanReturningAPlausibleNumber() {
        MetricHistogram duration = new MetricHistogram(MetricHistogram.Grid.duration());
        MetricHistogram cost = new MetricHistogram(MetricHistogram.Grid.cost());
        duration.add(Math.log(2000));
        cost.add(Math.log(0.02));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> MetricDistance.signedW1(duration, cost));
        String message = java.util.Objects.requireNonNull(thrown.getMessage(), "the throw must explain itself");
        assertTrue(message.contains("different grids"), message);
    }

    /**
     * Empty and single-sample sketches return a number instead of dividing by zero. The sweep's
     * {@code min_sample} makes these unreachable in production; the point is that the pure function is
     * total, so an eval or a unit test can hand it anything without special-casing.
     */
    @Test
    void degenerateSketchesDoNotDivideByZero() {
        MetricHistogram empty = new MetricHistogram(MetricHistogram.Grid.duration());
        MetricHistogram alsoEmpty = new MetricHistogram(MetricHistogram.Grid.duration());
        MetricHistogram one = new MetricHistogram(MetricHistogram.Grid.duration());
        one.add(Math.log(2000));
        MetricHistogram oneShifted = new MetricHistogram(MetricHistogram.Grid.duration());
        oneShifted.add(Math.log(2800));

        assertEquals(0.0, MetricDistance.signedW1(empty, alsoEmpty), 0.0);
        assertEquals(0.0, MetricDistance.signedW1(empty, one), 0.0, "nothing to compare is not a shift");
        assertEquals(0.0, MetricDistance.signedW1(one, empty), 0.0);

        double w1 = MetricDistance.signedW1(one, oneShifted);
        assertTrue(Double.isFinite(w1), "a one-sample comparison must still be a number");
        assertEquals(Math.log(1.4), w1, SLOT, "two point masses are ln(ratio) apart");
    }

    /**
     * Mass that moves out of the top of the range still reads as a shift up. It reads as a smaller one
     * than the truth — the overflow counter knows the samples left, not how far they went — and
     * understating is the only safe direction to be wrong in. The overflow counter itself is what tells
     * an operator the range needs moving (PLAN.md §11).
     */
    @Test
    void massLeavingTheRangeStillRegistersAsAShift() {
        MetricHistogram reference = new MetricHistogram(MetricHistogram.Grid.duration());
        MetricHistogram current = new MetricHistogram(MetricHistogram.Grid.duration());
        for (int i = 0; i < 1000; i++) {
            reference.add(Math.log(2000));
            current.add(Math.log(1e12)); // far past the ~1.7 hour top edge
        }

        assertEquals(1000, current.overflow(), "out-of-range samples are counted, never dropped");
        assertTrue(MetricDistance.signedW1(reference, current) > 0, "traffic that left the top is a shift up");
    }
}
