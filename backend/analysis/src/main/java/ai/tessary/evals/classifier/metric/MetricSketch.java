// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * A bounded, mergeable summary of a set of {@code log(value)} samples — the payload behind
 * {@code metric_baseline.{pinned,prev,current}_sketch_json}. One sketch is one (bucket × measure)
 * window; comparing two of them through {@link MetricDistance} is the whole statistic
 * ({@code classifiers/metric_drift/PROGRAM.md} §4).
 *
 * <p><b>Why an interface over one implementation.</b> {@link MetricHistogram} is a fixed log-spaced
 * histogram, chosen because it is fifty lines and exactly reproducible rather than because it is the
 * best summary available. A t-digest is strictly better in the tails and can replace it here without
 * touching the sweep, the repository or the detector — provided it can project onto a shared log grid,
 * which is what {@link #gridId()}, {@link #slotWidthLog()} and {@link #cdf()} exist to express. The
 * one thing a replacement may <b>not</b> do is grow with the sample count: a thin bucket's window
 * stays open for up to a week (PROGRAM.md §2.3), so a raw sample list is unbounded by design.
 *
 * <p><b>Everything on this interface is in log space.</b> Callers take the logarithm once, at the
 * source, and never hand a raw millisecond or dollar figure to a sketch. That is not a convenience:
 * W₁ on logs is what makes {@code e^W₁} the multiplicative shift a finding reports ("1.4× slower"),
 * and mixing the two spaces produces a number that is neither.
 *
 * <p>Implementations are mutable accumulators, not values — {@link #add} and {@link #merge} write in
 * place. Use {@link #copy()} when a snapshot has to outlive further writes, which is what the window
 * roll (current → prev) needs.
 */
public interface MetricSketch {

    /**
     * Fold one sample in. {@code logValue} is {@code ln} of the measure, not the measure.
     *
     * <p>Values outside the grid are <b>counted at the edge, never dropped and never silently
     * clipped</b> — see {@link MetricHistogram} on why the eval needs to see a bucket pinned at a
     * range edge. {@code -inf} (a measure of exactly zero, which duration genuinely produces) lands in
     * underflow and {@code +inf} in overflow; {@code NaN} is a caller bug and throws.
     */
    void add(double logValue);

    /**
     * Fold another sketch's samples in, in place. Exact: merging is bin-wise addition, so a window
     * assembled from pages equals the same window assembled in one pass, whatever the page boundaries
     * were. Throws if {@code other} sits on a different grid.
     */
    void merge(MetricSketch other);

    /** An independent copy — writes to either afterwards do not touch the other. */
    MetricSketch copy();

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
     * Standard deviation of the samples in log space — how WIDE this bucket's traffic is, which is one
     * of the two things that decide how much sampling noise a comparison of it carries.
     *
     * <p>Read by {@link MetricDriftDetector#effectiveFloor}. The noise floor of W₁ between two windows
     * of unchanged traffic is proportional to this and inversely proportional to the square root of the
     * sample count, so a detector that wants a fixed false-alarm rate has to know it: a metronomic call
     * site can be held to a far smaller move than a lumpy one at the same rate, and holding both to one
     * number means the tight one is under-watched or the wide one is noisy.
     *
     * <p>Zero for an empty or single-sample sketch, and zero for one whose traffic all landed in a single
     * bin. Callers must read that as "no width measured" rather than as licence to compare on a bar of
     * zero — {@link MetricDriftDetector#effectiveFloor} falls back to the configured guard.
     */
    double stdDevLog();

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

    /** Serialized form for {@code metric_baseline.*_sketch_json}. Round-trips through {@link #fromJson}. */
    String toJson();

    /**
     * Rehydrate a sketch written by {@link #toJson()}. Dispatches on the payload's {@code kind}
     * discriminator, which is the seam that lets a later t-digest coexist with histograms already in
     * the table rather than requiring a backfill.
     *
     * @throws IllegalArgumentException on malformed JSON or an unknown {@code kind}
     */
    static MetricSketch fromJson(String json) {
        String kind = kindOf(json);
        return switch (kind) {
            case MetricHistogram.KIND -> MetricHistogram.fromJson(json);
            default -> throw new IllegalArgumentException("unknown metric sketch kind: " + kind);
        };
    }

    /** Peek at the {@code kind} discriminator without committing to an implementation's shape. */
    private static String kindOf(String json) {
        try {
            JsonNode node = MetricHistogram.JSON.readTree(json);
            JsonNode kind = node.get("kind");
            if (kind == null || !kind.isTextual()) {
                throw new IllegalArgumentException("metric sketch json has no 'kind' discriminator");
            }
            return kind.asText();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric sketch json", e);
        }
    }
}
