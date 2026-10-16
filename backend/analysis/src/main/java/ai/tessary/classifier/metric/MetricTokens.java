// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Evidence on a {@code cost} finding, never findings of their own: a cache-killing prompt edit moves five quantities at
 * once, and it is one cause. Kept apart from {@link MetricWorkload} because output tokens are the agent's choice, and
 * that block's argument rests on every quantity being the user's. Persisted, since a reference window's traffic may be
 * weeks gone.
 */
public final class MetricTokens extends MetricLogSketches {

    /**
     * Keyed by {@link Measure}'s persisted names. {@code tok_cache_write} is folded only where cache creation is
     * billed, so elsewhere it reads null ("not measured"), not 0.
     */
    public static final String INPUT = Measure.TOK_INPUT;

    public static final String OUTPUT = Measure.TOK_OUTPUT;

    public static final String CACHE_READ = Measure.TOK_CACHE_READ;

    public static final String CACHE_WRITE = Measure.TOK_CACHE_WRITE;

    /**
     * The share, not the raw count: a prefix edit collapses it from ~80% to ~0% while the count just tracks volume.
     * Percent rather than a fraction for resolution on the geometric grid.
     */
    public static final String CACHE_READ_PCT = "cache_read_pct";

    public static final List<String> QUANTITIES = List.of(INPUT, OUTPUT, CACHE_READ, CACHE_WRITE, CACHE_READ_PCT);

    /**
     * Stored as {@code log1p} because zero is the signal here: a 0% cache share is the regression and must land on the
     * grid, not in underflow.
     */
    public MetricTokens(Grid grid) {
        super(QUANTITIES, grid);
    }

    /**
     * A null contributes nothing rather than zero: averaging "not reported" in as zero would manufacture the very
     * collapse this detects.
     */
    public void add(
            @Nullable Double input,
            @Nullable Double output,
            @Nullable Double cacheRead,
            @Nullable Double cacheWrite,
            @Nullable Double cacheReadPct) {
        addOne(0, input);
        addOne(1, output);
        addOne(2, cacheRead);
        addOne(3, cacheWrite);
        addOne(4, cacheReadPct);
    }

    public void merge(MetricTokens other) {
        mergeSketches(other);
    }

    public MetricTokens copy() {
        MetricTokens out = new MetricTokens(grid);
        out.merge(this);
        return out;
    }

    /**
     * A quantity missing or on a different grid (after a {@code hist_bins} edit) comes back empty rather than throwing;
     * the next close overwrites it.
     */
    public static MetricTokens fromJson(String json, Grid grid) {
        MetricTokens out = new MetricTokens(grid);
        out.readJson(json, "tokens");
        return out;
    }
}
