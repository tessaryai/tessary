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
 * Fixed geometric bins: exact merges and exactly reproducible, which is why this rather than a t-digest. Out-of-range
 * samples go to edge counters and still count: dropping them would hide a bucket moving out of range, and clipping
 * would hide that the range is wrong. Not thread-safe; each sweep pass owns its sketches under the job lease.
 */
public final class MetricHistogram implements MetricSketch {

    /** Persisted; never renamed. */
    static final String KIND = "hist";

    static final ObjectMapper JSON = new ObjectMapper();

    /** 5% per bin gives quantiles to about ±2.5%, well under the ~20% shift the detector looks for. */
    public static final double DEFAULT_RATIO = 1.05;

    /** {@code 1.05^320 ≈ 6.0e6}: one range covers a 2 ms cache hit and a 40-minute run. */
    public static final int DEFAULT_BINS = 320;

    /** A value type so {@link #id()}, which {@link MetricDistance} asserts on, cannot drift from the numbers. */
    public record Grid(double lo, double ratio, int bins) {

        public Grid {
            // Finiteness first: NaN fails every comparison, so `lo <= 0` alone would let a NaN grid through.
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

        public double logLo() {
            return Math.log(lo);
        }

        public double slotWidthLog() {
            return Math.log(ratio);
        }

        public double logHi() {
            return logLo() + bins * slotWidthLog();
        }

        /** Formatted rather than record equality, so a sketch rehydrated through a decimal round trip still matches. */
        public String id() {
            // Locale.ROOT: this string is compared, and a comma-decimal locale on one node would reject its peer's
            // sketches.
            return String.format(Locale.ROOT, "%s/lo=%.6g/r=%.6g/bins=%d", KIND, lo, ratio, bins);
        }

        /** Milliseconds, {@code lo = 1 ms}, up to about 1.7 hours. */
        public static Grid duration() {
            return new Grid(1.0, DEFAULT_RATIO, DEFAULT_BINS);
        }

        /**
         * USD, {@code lo = $0.00001}, up to about $60: the range is spent on the expensive tail, where reroutes and
         * retry loops live.
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

    private double clampedSumLog;

    public MetricHistogram(Grid grid) {
        this.grid = grid;
        this.bins = new long[grid.bins()];
    }

    public long underflow() {
        return underflow;
    }

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
        MetricHistogram h = (MetricHistogram) other;
        for (int i = 0; i < bins.length; i++) {
            bins[i] += h.bins[i];
        }
        underflow += h.underflow;
        overflow += h.overflow;
        count += h.count;
        clampedSumLog += h.clampedSumLog;
    }

    /** The loop lives here rather than handing out {@code bins}, which a caller could then mutate. */
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
     * From the bins at midpoints, since a rehydrated sketch has nothing else. The binning adds about 2e-4 variance,
     * erring upward, the safe direction for a false-alarm rate.
     */
    @Override
    public double stdDevLog() {
        if (count < 2) return 0.0;
        // Both moments come off the midpoints, not meanLog(): mixing views makes identical samples report up to half a
        // bin of spread.
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
        double targetRank = clamped * count;
        long below = underflow;
        if (below > 0 && targetRank <= below) return grid.logLo();
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] == 0) continue;
            if (targetRank <= below + bins[i]) {
                // Half a bin of error is 2.5% on the value, an order below anything the detector reads.
                double within = (targetRank - below) / bins[i];
                return grid.logLo() + (i + within) * grid.slotWidthLog();
            }
            below += bins[i];
        }
        return grid.logHi(); // pinned at the ceiling: the true value is higher
    }

    private int binOf(double logValue) {
        if (logValue == Double.NEGATIVE_INFINITY) return -1; // a measure of exactly zero
        if (logValue == Double.POSITIVE_INFINITY) return bins.length;
        double offset = (logValue - grid.logLo()) / grid.slotWidthLog();
        if (offset < 0) return -1;
        if (offset >= bins.length) return bins.length;
        return (int) Math.floor(offset);
    }

    /**
     * Bins are stored sparsely: a thin bucket fills a handful of the 320, and this blob is written three times per
     * bucket and measure on every window close.
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
        return JSON.valueToTree(payload).toString();
    }

    public static MetricHistogram fromJson(String json) {
        Payload payload;
        try {
            payload = JSON.readValue(json, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric histogram json", e);
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
