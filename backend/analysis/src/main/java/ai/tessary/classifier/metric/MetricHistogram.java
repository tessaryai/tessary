// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A {@link MetricSketch} over fixed geometric bins: bin {@code i} covers
 * {@code [lo · r^i, lo · r^(i+1))}, which in log space is a uniform grid of {@code ln(r)}-wide slots.
 * Constant memory (one {@code long} per bin plus two edge counters), exact merges, and exactly
 * reproducible — no seeds, no compression, no ordering effects. That reproducibility is the reason
 * this and not a t-digest first: the metric-drift eval replays the same corpus through the same
 * sketch and has to get the same number twice.
 *
 * <h2>Geometry</h2>
 *
 * <p>{@code r = 1.05} — each bin is 5% wider than the last, so a quantile read off the grid is
 * accurate to about ±2.5%, well under the ~20% shift the detector is meant to notice. 320 bins spans
 * {@code 1.05^320 ≈ 6.0e6}, six and a bit orders of magnitude, which is what makes one range cover
 * both a 2 ms cache hit and a 40-minute research run. Fewer bins would not span the traffic; more
 * would buy resolution the statistic cannot use.
 *
 * <p>Each measure gets its own {@code lo} and they are never shared — see {@link #forDuration()} and
 * {@link #forCost()}. Putting duration and cost on one range would waste most of it on values neither
 * measure ever produces.
 *
 * <h2>Underflow and overflow are counted, never clipped silently</h2>
 *
 * <p>A sample outside the range increments a dedicated edge counter and still counts toward
 * {@link #count()}. Both matter. Dropping it would make a bucket that moved <i>out</i> of range look
 * unchanged, which is the one move a drift detector must not miss. Folding it into the first or last
 * bin would hide the fact that the range is wrong: traffic pinned in overflow is a configuration bug,
 * and PLAN.md §11 lists "overflow bin non-empty in the null run" as the signal that the cost range
 * needs moving. Distance treats the edge counters as two extra slots one bin wide, which understates
 * a shift that lands out of range and never overstates one.
 *
 * <p>Not thread-safe. Each sweep pass owns its sketches under the signal job's existing lease.
 */
public final class MetricHistogram implements MetricSketch {

    /** The {@code kind} discriminator written into {@code *_sketch_json}. Persisted; never renamed. */
    static final String KIND = "hist";

    /** One mapper for this package's sketch serialization. {@link ObjectMapper} is thread-safe once configured. */
    static final ObjectMapper JSON = new ObjectMapper();

    /** Bin growth ratio. 5% per bin ⇒ quantiles good to ±2.5%. */
    public static final double DEFAULT_RATIO = 1.05;

    /** Bins per grid. {@code 1.05^320 ≈ 6.0e6} — six orders of magnitude, the span real traffic covers. */
    public static final int DEFAULT_BINS = 320;

    /**
     * The immutable layout two sketches must share to be comparable. A value type rather than three
     * loose doubles so {@link #id()} — the thing {@link MetricDistance} asserts on — cannot drift out
     * of step with the numbers it describes.
     *
     * @param lo lower edge of bin 0, in the measure's own units
     * @param ratio width multiplier between consecutive bins
     * @param bins bin count, excluding the two edge counters
     */
    public record Grid(double lo, double ratio, int bins) {

        public Grid {
            // Finiteness is tested FIRST so the comparisons below can be written the plain way round.
            // NaN fails every comparison, so `lo <= 0` alone would let a NaN grid through; ruling it out
            // up front is what makes the ordering load-bearing rather than stylistic.
            if (!Double.isFinite(lo) || lo <= 0) {
                throw new IllegalArgumentException("grid lo must be finite and positive, was " + lo);
            }
            if (!Double.isFinite(ratio) || ratio <= 1) {
                throw new IllegalArgumentException("grid ratio must be finite and > 1, was " + ratio);
            }
            if (bins < 1) {
                throw new IllegalArgumentException("grid needs at least one bin, was " + bins);
            }
        }

        /** {@code ln(lo)} — the lower edge in log space, where every sample is compared. */
        public double logLo() {
            return Math.log(lo);
        }

        /** {@code ln(ratio)} — one slot's width in log space, constant across the grid. */
        public double slotWidthLog() {
            return Math.log(ratio);
        }

        /** {@code ln(lo · ratio^bins)} — the upper edge, above which samples land in overflow. */
        public double logHi() {
            return logLo() + bins * slotWidthLog();
        }

        /**
         * Compact identity, and the exact string {@link MetricDistance} compares. Formatted rather than
         * relying on record equality so that a sketch rehydrated from JSON — where {@code lo} has been
         * through a decimal round-trip — still matches one built in memory.
         */
        public String id() {
            // Locale.ROOT, not the default: this string is COMPARED, and a JVM running under a locale
            // whose decimal separator is a comma would format "lo=1,00000" while its peer formats
            // "lo=1.00000" — two nodes sweeping the same project would then reject each other's
            // sketches as grid mismatches. forbiddenapis bans the default-locale overloads for exactly
            // this class of bug, so the explicit locale is required rather than merely tidy.
            return String.format(Locale.ROOT, "%s/lo=%.6g/r=%.6g/bins=%d", KIND, lo, ratio, bins);
        }

        /**
         * Duration in <b>milliseconds</b>, {@code lo = 1 ms}, spanning up to ≈ 1.7 hours. Nothing an
         * agent does resolves below a millisecond, and the top end comfortably clears the longest real
         * agent runs.
         */
        public static Grid duration() {
            return new Grid(1.0, DEFAULT_RATIO, DEFAULT_BINS);
        }

        /**
         * Cost in <b>USD</b>, {@code lo = $0.00001}, spanning up to ≈ $60. The range is deliberately
         * spent on the expensive tail: a turn below a thousandth of a cent is ~40 tokens on the cheapest
         * model and carries no signal, whereas the regressions this measure exists to catch — a silent
         * reroute to a pricier model, a retry loop — all live at the top.
         */
        public static Grid cost() {
            return new Grid(1e-5, DEFAULT_RATIO, DEFAULT_BINS);
        }
    }

    private final Grid grid;
    private final long[] bins;
    private long underflow;
    private long overflow;
    private long count;

    /** Sum of samples clamped to {@code [logLo, logHi]}; divided by {@link #count} for the mean. */
    private double clampedSumLog;

    /** An empty sketch on {@code grid}. Grids come from {@link Grid#duration()} / {@link Grid#cost()}. */
    public MetricHistogram(Grid grid) {
        this.grid = grid;
        this.bins = new long[grid.bins()];
    }

    public Grid grid() {
        return grid;
    }

    /** Samples that fell below {@code grid.lo()}. Non-zero means the range starts too high. */
    public long underflow() {
        return underflow;
    }

    /** Samples that fell at or above the top edge. Non-zero means the range ends too low. */
    public long overflow() {
        return overflow;
    }

    @Override
    public void add(double logValue) {
        if (Double.isNaN(logValue)) {
            throw new IllegalArgumentException(
                    "NaN is not a sample; the caller took log of a negative or absent value");
        }
        int slot = binOf(logValue);
        if (slot < 0) {
            underflow++;
        } else if (slot >= bins.length) {
            overflow++;
        } else {
            bins[slot]++;
        }
        count++;
        clampedSumLog += Math.clamp(logValue, grid.logLo(), grid.logHi());
    }

    @Override
    public void merge(MetricSketch other) {
        if (!gridId().equals(other.gridId())) {
            throw new IllegalArgumentException(
                    "cannot merge sketches on different grids: " + gridId() + " vs " + other.gridId());
        }
        if (!(other instanceof MetricHistogram h)) {
            // Same grid, different summary structure. Bin-wise addition is what makes merge exact, and
            // there is no exact way to fold a different representation in — refuse rather than approximate.
            throw new IllegalArgumentException(
                    "cannot merge " + other.getClass().getSimpleName() + " into a histogram");
        }
        for (int i = 0; i < bins.length; i++) {
            bins[i] += h.bins[i];
        }
        underflow += h.underflow;
        overflow += h.overflow;
        count += h.count;
        clampedSumLog += h.clampedSumLog;
    }

    /**
     * Add this histogram's bin mass into {@code target}, each bin scaled by {@code weight}.
     *
     * <p>The one operation {@link MetricControl.Weighted} needs from the inside of this class, and the
     * only reason the bins are reachable at all outside it. The loop lives here rather than the array
     * being handed out: a control that could reach {@code bins} could also write to it, and a reference
     * sketch that a reader can mutate is exactly the bug the {@link #copy()} contract exists to rule out.
     */
    void foldScaledInto(double[] target, double weight) {
        for (int i = 0; i < bins.length; i++) {
            target[i] += weight * bins[i];
        }
    }

    @Override
    public MetricHistogram copy() {
        MetricHistogram out = new MetricHistogram(grid);
        System.arraycopy(bins, 0, out.bins, 0, bins.length);
        out.underflow = underflow;
        out.overflow = overflow;
        out.count = count;
        out.clampedSumLog = clampedSumLog;
        return out;
    }

    @Override
    public long count() {
        return count;
    }

    @Override
    public double meanLog() {
        return count == 0 ? 0.0 : clampedSumLog / count;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Recovered from the bins rather than from a running sum of squares, because a sketch rehydrated
     * from JSON has bins and nothing else — persisting a second accumulator would have to survive every
     * merge and every round trip to stay in step, and the bins already carry the answer.
     *
     * <p>Each sample is taken at its bin's MIDPOINT, so the result is the true standard deviation plus
     * the binning's own variance. One bin is {@code ln(1.05)} wide, giving a uniform-within-bin variance
     * of {@code ln(1.05)²/12 ≈ 2e-4}; against the 0.09 variance of even a tight call site that is a tenth
     * of a percent, and it errs upward — a slightly wider reading raises the floor, which is the safe
     * direction for a false-alarm rate. The edge counters sit at their own edges for the same reason
     * {@link #meanLog()} clamps there.
     */
    @Override
    public double stdDevLog() {
        if (count < 2) return 0.0;
        // BOTH moments come off the bin midpoints, deliberately not off meanLog(). That mean is the exact
        // clamped sum, and mixing the two views makes a bucket whose every sample is identical report a
        // width of up to half a bin — the offset between its one midpoint and its true value — so the
        // most stable traffic imaginable would look like it had spread. Taking both from the same view
        // makes a single-bin sketch report exactly zero, which is the truth about it.
        double lo = grid.logLo();
        double hi = grid.logHi();
        double width = grid.slotWidthLog();
        double sum = 0.0;
        if (underflow > 0) sum += underflow * lo;
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] != 0) sum += bins[i] * (lo + (i + 0.5) * width);
        }
        if (overflow > 0) sum += overflow * hi;
        double mean = sum / count;

        double sumSq = 0.0;
        if (underflow > 0) sumSq += underflow * sq(lo - mean);
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] == 0) continue;
            sumSq += bins[i] * sq(lo + (i + 0.5) * width - mean);
        }
        if (overflow > 0) sumSq += overflow * sq(hi - mean);
        return Math.sqrt(sumSq / count);
    }

    private static double sq(double v) {
        return v * v;
    }

    @Override
    public String gridId() {
        return grid.id();
    }

    @Override
    public double slotWidthLog() {
        return grid.slotWidthLog();
    }

    @Override
    public double[] cdf() {
        double[] out = new double[bins.length + 2];
        if (count == 0) return out; // all zeros: no mass anywhere, and no division by zero
        long running = underflow;
        out[0] = (double) running / count;
        for (int i = 0; i < bins.length; i++) {
            running += bins[i];
            out[i + 1] = (double) running / count;
        }
        out[bins.length + 1] = 1.0; // running + overflow == count by construction; assign exactly
        return out;
    }

    @Override
    public @Nullable Double quantile(double q) {
        if (count == 0) return null;
        double clamped = Math.clamp(q, 0.0, 1.0);
        // Rank of the sample we want, on the same 0..count scale the cumulative counts are on.
        double targetRank = clamped * count;
        long below = underflow;
        // Pinned at the floor: the true value is below the range, so the edge is the honest answer.
        if (below > 0 && targetRank <= below) return grid.logLo();
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] == 0) continue;
            if (targetRank <= below + bins[i]) {
                // Interpolate inside the bin, assuming mass is spread evenly across it. Half a bin of
                // error either way is 2.5% on the value — an order below anything the detector reads.
                double within = (targetRank - below) / bins[i];
                return grid.logLo() + (i + within) * grid.slotWidthLog();
            }
            below += bins[i];
        }
        return grid.logHi(); // pinned at the ceiling: the true value is higher
    }

    /** Slot index for a log value; negative means underflow, {@code >= bins.length} means overflow. */
    private int binOf(double logValue) {
        if (logValue == Double.NEGATIVE_INFINITY) return -1; // a measure of exactly zero
        if (logValue == Double.POSITIVE_INFINITY) return bins.length;
        double offset = (logValue - grid.logLo()) / grid.slotWidthLog();
        if (offset < 0) return -1;
        if (offset >= bins.length) return bins.length;
        return (int) Math.floor(offset);
    }

    // ---------------------------------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------------------------------

    /**
     * Wire shape of {@code metric_baseline.*_sketch_json}. Bins are stored <b>sparsely</b>, keyed by
     * index: a thin bucket occupies a handful of the 320, and this blob is written three times per
     * (bucket × measure) on every window close.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(
            @JsonProperty("kind") String kind,
            @JsonProperty("lo") double lo,
            @JsonProperty("r") double ratio,
            @JsonProperty("bins") int bins,
            @JsonProperty("n") long count,
            @JsonProperty("under") long underflow,
            @JsonProperty("over") long overflow,
            @JsonProperty("sum") double clampedSumLog,
            @JsonProperty("b") Map<String, Long> binCounts) {}

    @Override
    public String toJson() {
        Map<String, Long> sparse = new LinkedHashMap<>();
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] != 0) sparse.put(Integer.toString(i), bins[i]);
        }
        Payload payload = new Payload(
                KIND, grid.lo(), grid.ratio(), grid.bins(), count, underflow, overflow, clampedSumLog, sparse);
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize metric histogram", e);
        }
    }

    /** Rehydrate a payload written by {@link #toJson()}. Prefer {@link MetricSketch#fromJson(String)}. */
    public static MetricHistogram fromJson(String json) {
        Payload payload;
        try {
            payload = JSON.readValue(json, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric histogram json", e);
        }
        if (!KIND.equals(payload.kind())) {
            throw new IllegalArgumentException("not a metric histogram payload: kind=" + payload.kind());
        }
        MetricHistogram out = new MetricHistogram(new Grid(payload.lo(), payload.ratio(), payload.bins()));
        Map<String, Long> binCounts = payload.binCounts() == null ? new HashMap<>() : payload.binCounts();
        for (Map.Entry<String, Long> e : binCounts.entrySet()) {
            int index = Integer.parseInt(e.getKey());
            if (index < 0 || index >= out.bins.length) {
                throw new IllegalArgumentException("metric histogram bin index out of range: " + index);
            }
            out.bins[index] = e.getValue();
        }
        out.underflow = payload.underflow();
        out.overflow = payload.overflow();
        out.count = payload.count();
        out.clampedSumLog = payload.clampedSumLog();
        return out;
    }
}
