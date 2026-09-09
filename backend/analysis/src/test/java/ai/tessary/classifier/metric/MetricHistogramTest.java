// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link MetricHistogram} — the accumulator side of the sketch, with
 * {@link MetricDistanceTest} covering the statistic computed from it. Pure Java: no Spring, no database,
 * nothing project-shaped on the classpath.
 *
 * <p>Three properties here are load-bearing rather than incidental, and each has a test because losing
 * any of them would be invisible at the call site:
 *
 * <ul>
 *   <li><b>Merge is exact.</b> The sweep folds a window in over many pages, so a window assembled from
 *       pages has to equal the same window assembled in one pass — whatever the page boundaries were.
 *       Bin-wise addition of longs gives that for free; the test exists so a later summary structure
 *       cannot quietly take it away.
 *   <li><b>Round-trip is lossless.</b> Every window close writes three of these blobs and reads them
 *       back next pass, so a sketch that lost bins on the way through JSON would decay a baseline
 *       gradually rather than break it — the worst failure shape available.
 *   <li><b>Out-of-range samples are counted, never clipped.</b> A bucket whose traffic moved
 *       <i>out</i> of the range is precisely the move a drift detector must not miss, and traffic pinned
 *       at an edge is the signal that the range itself is wrong (PLAN.md §11).
 * </ul>
 */
class MetricHistogramTest {

    /** One slot of the duration grid, {@code ln(1.05) ≈ 0.0488}. */
    private static final double SLOT = Math.log(MetricHistogram.DEFAULT_RATIO);

    private static MetricHistogram duration() {
        return new MetricHistogram(MetricHistogram.Grid.duration());
    }

    /** A seeded log-normal in milliseconds — the shape real latency actually has. */
    private static double[] traffic(long seed, int n) {
        Random random = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = 1500.0 * Math.exp(0.9 * random.nextGaussian());
        }
        return out;
    }

    /**
     * Bin-for-bin equality. {@link MetricHistogram#cdf()} is derived from the bin counts alone and is
     * exact for equal counts, so comparing it plus the three totals is the same assertion as reading the
     * private array — without the reflection.
     */
    private static void assertSameBins(MetricHistogram expected, MetricHistogram actual, String what) {
        assertEquals(expected.gridId(), actual.gridId(), what + ": grid");
        assertEquals(expected.count(), actual.count(), what + ": count");
        assertEquals(expected.underflow(), actual.underflow(), what + ": underflow");
        assertEquals(expected.overflow(), actual.overflow(), what + ": overflow");
        assertArrayEquals(expected.cdf(), actual.cdf(), 0.0, what + ": bins");
    }

    // -------------------------------------------------------------------------------------------
    // Merge
    // -------------------------------------------------------------------------------------------

    /**
     * A window folded in as three pages equals the same window folded in as one, and the order the pages
     * arrive in does not matter. Both halves matter to the sweep: it pages, and it has no control over
     * where the page boundaries land relative to the window.
     */
    @Test
    void mergeIsExactAndAssociative() {
        double[] values = traffic(11L, 3_000);

        MetricHistogram onePass = duration();
        for (double value : values) onePass.add(Math.log(value));

        MetricHistogram a = duration();
        MetricHistogram b = duration();
        MetricHistogram c = duration();
        for (int i = 0; i < values.length; i++) {
            MetricHistogram page = i < 900 ? a : (i < 2_100 ? b : c);
            page.add(Math.log(values[i]));
        }

        MetricHistogram leftFirst = a.copy();
        leftFirst.merge(b);
        leftFirst.merge(c);

        MetricHistogram rightFirst = b.copy();
        rightFirst.merge(c);
        MetricHistogram thenA = a.copy();
        thenA.merge(rightFirst);

        assertSameBins(onePass, leftFirst, "(a+b)+c against one pass");
        assertSameBins(onePass, thenA, "a+(b+c) against one pass");
        assertEquals(onePass.meanLog(), leftFirst.meanLog(), 1e-9, "mean survives paging");
        assertEquals(onePass.meanLog(), thenA.meanLog(), 1e-9);
    }

    /** {@link MetricHistogram#copy()} is a snapshot — the window roll (current → prev) depends on it. */
    @Test
    void copyIsIndependentOfFurtherWrites() {
        MetricHistogram original = duration();
        original.add(Math.log(2000));

        MetricHistogram snapshot = original.copy();
        original.add(Math.log(9000));

        assertEquals(1, snapshot.count(), "the snapshot must not see writes made after it was taken");
        assertEquals(2, original.count());
    }

    /**
     * Merging across grids throws. Duration and cost sketches are structurally identical and would merge
     * without complaint into a number that means nothing, which is why this is checked rather than
     * assumed — the same argument {@link MetricDistance} makes for comparison.
     */
    @Test
    void mergeAcrossGridsThrows() {
        MetricHistogram durationSketch = duration();
        MetricHistogram costSketch = new MetricHistogram(MetricHistogram.Grid.cost());

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> durationSketch.merge(costSketch));
        String message = java.util.Objects.requireNonNull(thrown.getMessage(), "the throw must explain itself");
        assertTrue(message.contains("different grids"), message);
    }

    /** Each measure owns its range; sharing one would spend most of it on values neither ever produces. */
    @Test
    void durationAndCostAreDifferentGrids() {
        assertNotEquals(
                MetricHistogram.Grid.duration().id(),
                MetricHistogram.Grid.cost().id(),
                "cost must not be comparable to duration by accident");
    }

    // -------------------------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------------------------

    /**
     * Round-tripping through {@code *_sketch_json} preserves every bin, both edge counters, the total and
     * the mean. Deliberately built with mass in the body <i>and</i> in both edge counters, because the
     * sparse wire shape stores bins in a map and the counters as separate fields — a serializer that
     * dropped one of those would still round-trip a body-only fixture cleanly.
     */
    @Test
    void jsonRoundTripPreservesEveryBin() {
        MetricHistogram original = duration();
        for (double value : traffic(23L, 2_000)) original.add(Math.log(value));
        original.add(Math.log(0.0001)); // under 1 ms → underflow
        original.add(Math.log(1e9)); // past the top edge → overflow
        original.add(Double.NEGATIVE_INFINITY); // a measure of exactly zero

        MetricSketch restored = MetricSketch.fromJson(original.toJson());

        assertTrue(restored instanceof MetricHistogram, "the kind discriminator must route back to a histogram");
        assertSameBins(original, (MetricHistogram) restored, "round trip");
        assertEquals(original.meanLog(), restored.meanLog(), 0.0, "the clamped sum round-trips exactly");
        assertEquals(original.toJson(), restored.toJson(), "and re-serializes byte for byte");
    }

    /** An empty sketch survives the round trip as an empty sketch, not as a null or a NaN mean. */
    @Test
    void emptySketchRoundTrips() {
        MetricHistogram empty = duration();

        MetricSketch restored = MetricSketch.fromJson(empty.toJson());

        assertEquals(0, restored.count());
        assertEquals(0.0, restored.meanLog(), 0.0);
        assertNull(restored.quantile(0.5), "an empty sketch has no quantile to report");
    }

    /** Malformed or unrecognized payloads throw. The discriminator is the seam a t-digest would use. */
    @Test
    void unknownKindAndMalformedJsonThrow() {
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson("{\"kind\":\"tdigest\"}"));
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson("{\"lo\":1.0}"));
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson("not json at all"));
    }

    // -------------------------------------------------------------------------------------------
    // Range edges
    // -------------------------------------------------------------------------------------------

    /**
     * Samples outside the range increment a dedicated counter and still count toward {@link
     * MetricSketch#count()}. Both halves are the point: dropping them would make a bucket that moved out
     * of range look unchanged, and folding them into the end bins would hide that the range is wrong.
     */
    @Test
    void outOfRangeSamplesAreCountedNeverClipped() {
        MetricHistogram histogram = duration();
        histogram.add(Math.log(0.01)); // 10 µs, below the 1 ms floor
        histogram.add(Double.NEGATIVE_INFINITY); // a measure of exactly zero
        histogram.add(Math.log(2000)); // in range
        histogram.add(Math.log(1e10)); // ~116 days, past the top edge
        histogram.add(Double.POSITIVE_INFINITY);

        assertEquals(2, histogram.underflow());
        assertEquals(2, histogram.overflow());
        assertEquals(5, histogram.count(), "edge samples count toward the total, they are not dropped");
        assertTrue(Double.isFinite(histogram.meanLog()), "an infinite sample must not poison the mean");
    }

    /** {@code NaN} is a caller bug — the log of a negative or absent value — and must not be absorbed. */
    @Test
    void nanIsRejected() {
        MetricHistogram histogram = duration();

        assertThrows(IllegalArgumentException.class, () -> histogram.add(Double.NaN));
    }

    // -------------------------------------------------------------------------------------------
    // Quantiles
    // -------------------------------------------------------------------------------------------

    /**
     * Quantiles land within one slot of the truth — 5% on the value, and by construction half that on
     * average, an order of magnitude below any shift the detector is built to notice. These are what the
     * finding's evidence blob reports as
     * {@code quantiles.p50} / {@code p95} (PROGRAM.md §7), so the error has to be small enough that a
     * human reading "2.1 s → 2.9 s" is reading the traffic and not the grid.
     */
    @Test
    void quantilesAreAccurateToWithinHalfASlot() {
        double[] values = traffic(31L, 20_000);
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);

        MetricHistogram histogram = duration();
        for (double value : values) histogram.add(Math.log(value));

        for (double q : new double[] {0.5, 0.95, 0.99}) {
            Double got = histogram.quantile(q);
            assertNotNull(got, "a populated sketch has quantiles");
            double truth = Math.log(sorted[(int) Math.round(q * (sorted.length - 1))]);
            assertEquals(truth, got, SLOT, "q" + q + " must sit within a slot of the true quantile");
        }
    }

    /** A sketch whose mass is pinned past an edge reports the edge, and says so by way of the counter. */
    @Test
    void quantilesOfPinnedMassReportTheEdge() {
        MetricHistogram low = duration();
        MetricHistogram high = duration();
        for (int i = 0; i < 100; i++) {
            low.add(Math.log(1e-6));
            high.add(Math.log(1e12));
        }

        // Unwrapped explicitly: `quantile` is @Nullable only for the empty sketch, and a test that let
        // a null slip through would auto-unbox into an NPE reading as a failure of the assertion below
        // rather than of the sketch.
        assertEquals(MetricHistogram.Grid.duration().logLo(), requireQuantile(low, 0.5), 0.0);
        assertEquals(MetricHistogram.Grid.duration().logHi(), requireQuantile(high, 0.5), 0.0);
        assertEquals(100, low.underflow());
        assertEquals(100, high.overflow());
    }

    /** A single sample is a legitimate sketch, not a division by zero waiting to happen. */
    @Test
    void singleSampleSketchIsWellDefined() {
        MetricHistogram histogram = duration();
        histogram.add(Math.log(2000));

        assertEquals(1, histogram.count());
        assertEquals(Math.log(2000), histogram.meanLog(), 1e-12);
        assertEquals(Math.log(2000), requireQuantile(histogram, 0.5), SLOT);
        assertEquals(1.0, histogram.cdf()[histogram.cdf().length - 1], 0.0, "the CDF still tops out at 1");
    }

    /** {@link MetricSketch#quantile} answers null only on an empty sketch; every caller here has samples. */
    private static double requireQuantile(MetricSketch sketch, double q) {
        return java.util.Objects.requireNonNull(sketch.quantile(q), "a non-empty sketch has a quantile");
    }
}
