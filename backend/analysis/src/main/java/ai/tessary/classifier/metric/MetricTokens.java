// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.util.List;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * Evidence on a {@code cost} finding, never findings of their own: a cache-killing prompt edit moves five quantities at
 * once, and it is one cause. Kept apart from {@link MetricWorkload} because output tokens are the agent's choice, and
 * that block's argument rests on every quantity being the user's. Persisted, since a reference window's traffic may be
 * weeks gone.
 */
public final class MetricTokens {

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

    public static Grid grid(int bins) {
        return new Grid(1.0, MetricHistogram.DEFAULT_RATIO, bins);
    }

    /**
     * Stored as {@code log1p} because zero is the signal here: a 0% cache share is the regression and must land on the
     * grid, not in underflow.
     */
    private static double toLog(double raw) {
        return Math.log1p(Math.max(0.0, raw));
    }

    private static double fromLog(double logValue) {
        return Math.expm1(logValue);
    }

    private final Grid grid;

    private final MetricSketch[] sketches;

    public MetricTokens(Grid grid) {
        this.grid = grid;
        this.sketches = new MetricSketch[QUANTITIES.size()];
        for (int i = 0; i < sketches.length; i++) {
            sketches[i] = new MetricHistogram(grid);
        }
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

    private void addOne(int index, @Nullable Double raw) {
        if (raw == null || !Double.isFinite(raw)) return;
        sketches[index].add(toLog(raw));
    }

    public void merge(MetricTokens other) {
        for (int i = 0; i < sketches.length; i++) {
            sketches[i].merge(other.sketches[i]);
        }
    }

    public MetricTokens copy() {
        MetricTokens out = new MetricTokens(grid);
        out.merge(this);
        return out;
    }

    public boolean isEmpty() {
        for (MetricSketch sketch : sketches) {
            if (sketch.count() > 0) return false;
        }
        return true;
    }

    public OptionalDouble p50(String quantity) {
        int i = QUANTITIES.indexOf(quantity);
        if (i < 0 || sketches[i].count() == 0) return OptionalDouble.empty();
        Double q = sketches[i].quantile(0.5);
        return q == null ? OptionalDouble.empty() : OptionalDouble.of(fromLog(q));
    }

    public String toJson() {
        ObjectNode root = MetricHistogram.JSON.createObjectNode();
        for (int i = 0; i < sketches.length; i++) {
            root.putRawValue(QUANTITIES.get(i), new RawValue(sketches[i].toJson()));
        }
        return root.toString();
    }

    /**
     * A quantity missing or on a different grid (after a {@code hist_bins} edit) comes back empty rather than throwing;
     * the next close overwrites it.
     */
    public static MetricTokens fromJson(String json, Grid grid) {
        JsonNode node;
        try {
            node = MetricHistogram.JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric tokens json", e);
        }
        MetricTokens out = new MetricTokens(grid);
        for (int i = 0; i < QUANTITIES.size(); i++) {
            JsonNode child = node.get(QUANTITIES.get(i));
            if (child == null || child.isNull()) continue;
            MetricSketch sketch = MetricSketch.fromJson(child.toString());
            if (sketch.gridId().equals(grid.id())) out.sketches[i] = sketch;
        }
        return out;
    }
}
