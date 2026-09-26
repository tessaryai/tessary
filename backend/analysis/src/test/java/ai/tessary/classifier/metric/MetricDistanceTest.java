// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link MetricDistance}: arithmetic over two sketches, nothing else.
 *
 * <p>{@link #knownMultiplicativeShiftIsRecoveredWithinOneSlot()} proves the statistic: every claim metric drift makes
 * rests on {@code e^W₁} being the multiplicative shift. Traffic is a seeded log-normal (σ = 0.8, median 2 s), exact
 * because {@code java.util.Random} is specified bit-for-bit.
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
     * Scale every sample by a known factor and the signed W₁ comes back as {@code ln(factor)} within one slot, at
     * 1.1×, 1.4×, and 3×, in both directions: metric-drift.md §4.4 needs a speed-up measured as well as a slowdown.
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
            // One slot is 5% of the ratio; a shade more so a broken formula fails, not a landing on the resolution.
            assertEquals(factor, MetricDistance.ratio(slower), factor * 0.06, "e^W1 is the reported ratio");

            double faster = MetricDistance.signedW1(reference, sketchOf(values, 1.0 / factor));
            assertEquals(-Math.log(factor), faster, SLOT, "a shift down is measured on exactly the same scale");
            assertTrue(faster < 0, "the current window sitting below its reference is negative");
        }
    }

    /** Duration against cost sketches throws: both would produce a plausible W₁ nothing downstream would question. */
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
     * Empty and single-sample sketches return a number: unreachable past {@code min_sample}, but the function stays
     * total.
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
     * Mass leaving the top of the range still reads as a shift up, understated, the only safe direction to be wrong
     * in.
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
