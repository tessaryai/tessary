// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link MetricHistogram}, the accumulator side of the sketch ({@link MetricDistanceTest} covers the statistic).
 * Three properties are load-bearing and invisible at the call site: merge is exact, because the sweep folds a window
 * over many pages; the JSON round trip is lossless, because a lossy one would decay a baseline gradually; and out-of-
 * range samples are counted, never clipped, because traffic leaving the range is the move drift must not miss.
 */
class MetricHistogramTest {

    private static final double SLOT = Math.log(MetricHistogram.DEFAULT_RATIO);

    private static MetricHistogram duration() {
        return new MetricHistogram(MetricHistogram.Grid.duration());
    }

    /** A seeded log-normal in milliseconds, the shape real latency has. */
    private static double[] traffic(long seed, int n) {
        Random random = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = 1500.0 * Math.exp(0.9 * random.nextGaussian());
        }
        return out;
    }

    /** Bin-for-bin equality through {@link MetricHistogram#cdf()} and the totals, without reflection. */
    private static void assertSameBins(MetricHistogram expected, MetricHistogram actual, String what) {
        assertEquals(expected.gridId(), actual.gridId(), what + ": grid");
        assertEquals(expected.count(), actual.count(), what + ": count");
        assertEquals(expected.underflow(), actual.underflow(), what + ": underflow");
        assertEquals(expected.overflow(), actual.overflow(), what + ": overflow");
        assertArrayEquals(expected.cdf(), actual.cdf(), 0.0, what + ": bins");
    }

    /** Three pages in any order equal one pass: the sweep pages and does not control where boundaries land. */
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

    /**
     * Duration and cost sketches are structurally identical and would merge silently into nonsense, so merging across
     * grids throws.
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

    /**
     * Built with mass in the body and both edge counters: the wire shape stores bins in a map and counters as fields,
     * and a body-only fixture would hide a dropped one.
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

    /** An empty sketch round-trips as empty, not null or a NaN mean. */
    @Test
    void emptySketchRoundTrips() {
        MetricHistogram empty = duration();

        MetricSketch restored = MetricSketch.fromJson(empty.toJson());

        assertEquals(0, restored.count());
        assertEquals(0.0, restored.meanLog(), 0.0);
        assertNull(restored.quantile(0.5), "an empty sketch has no quantile to report");
    }

    /** Malformed or unknown payloads throw; the discriminator is the seam a t-digest would use. */
    @Test
    void unknownKindAndMalformedJsonThrow() {
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson("{\"kind\":\"tdigest\"}"));
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson("{\"lo\":1.0}"));
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson("not json at all"));
    }

    /**
     * Out-of-range samples hit a counter and still count: dropped, a bucket that left the range looks unchanged;
     * folded into the end bins, a wrong range stays hidden.
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

    /** NaN is a caller bug and must not be absorbed. */
    @Test
    void nanIsRejected() {
        MetricHistogram histogram = duration();

        assertThrows(IllegalArgumentException.class, () -> histogram.add(Double.NaN));
    }

    /**
     * Quantiles land within one slot (5%) of the truth, far below any shift the detector notices, so "2.1 s to 2.9 s"
     * in the evidence blob (metric-drift.md §7) reads the traffic, not the grid.
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

    /** Mass pinned past an edge reports the edge, and the counter says so. */
    @Test
    void quantilesOfPinnedMassReportTheEdge() {
        MetricHistogram low = duration();
        MetricHistogram high = duration();
        for (int i = 0; i < 100; i++) {
            low.add(Math.log(1e-6));
            high.add(Math.log(1e12));
        }

        // A null quantile would auto-unbox into an NPE that blames the assertion instead of the sketch.
        assertEquals(MetricHistogram.Grid.duration().logLo(), requireQuantile(low, 0.5), 0.0);
        assertEquals(MetricHistogram.Grid.duration().logHi(), requireQuantile(high, 0.5), 0.0);
        assertEquals(100, low.underflow());
        assertEquals(100, high.overflow());
    }

    private static double requireQuantile(MetricSketch sketch, double q) {
        return java.util.Objects.requireNonNull(sketch.quantile(q), "a non-empty sketch has a quantile");
    }

    /** NaN fails every comparison, so a {@code lo <= 0} check alone would admit a NaN grid that places no sample. */
    @ParameterizedTest
    @CsvSource({
        "0, 1.05, 320",
        "-1, 1.05, 320",
        "NaN, 1.05, 320",
        "Infinity, 1.05, 320",
        "1, 1.0, 320",
        "1, NaN, 320",
        "1, 1.05, 0"
    })
    void aGridThatCannotPlaceASampleIsRefused(double lo, double ratio, int bins) {
        assertThrows(IllegalArgumentException.class, () -> new MetricHistogram.Grid(lo, ratio, bins));
    }

    /**
     * A blob that parses but is not a valid histogram, or names a bin past the grid, is refused: it would corrupt the
     * counts or throw an index error the sweep does not expect.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\":\"hist\",\"lo\":\"one\",\"r\":1.05,\"bins\":4}",
                "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.05,\"bins\":4,\"b\":{\"4\":1}}",
                "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.05,\"bins\":4,\"b\":{\"-1\":1}}",
                "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.0,\"bins\":4}"
            })
    void aCorruptHistogramBlobIsRefused(String blob) {
        assertThrows(IllegalArgumentException.class, () -> MetricSketch.fromJson(blob));
    }
}
