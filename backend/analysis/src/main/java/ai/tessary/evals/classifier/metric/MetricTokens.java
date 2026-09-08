// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import ai.tessary.evals.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.evals.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * What a window's dollars were <em>made of</em> — the four token buckets and the cache-read share —
 * summarized per window slot of a {@code metric_baseline} row and reported, then versus now, inside a
 * {@code cost} finding's evidence ({@code classifiers/metric_drift/PROGRAM.md} §3.3 and §6.1).
 *
 * <h2>Why these are evidence and never findings of their own</h2>
 *
 * <p>{@code cost} is the only measure under {@code cost_drift} that opens a finding. A prompt edit that
 * stops the cache hitting moves cost, input tokens and cache reads all at once — one change, and five
 * rows if each measure could fire. It is one cause, so it is one row, and the four buckets ride on it as
 * the decomposition that explains it. That is the same argument {@code MetricSuppression} makes across
 * the two duration grains, applied within one grain instead of across two.
 *
 * <p>A finding that said only "this call site got 3× more expensive" sends someone to go and read spend by
 * model. One that says the cache-read share went from 82% to 0.4% while output tokens held flat names
 * the change: somebody edited the prompt prefix.
 *
 * <h2>Why a sibling of {@link MetricWorkload} rather than more quantities on it</h2>
 *
 * <p>They are the same machinery — bounded log-scale sketches riding each window slot, rotating and
 * re-pinning in lockstep with the measure sketch — and they are deliberately not the same block.
 * {@link MetricWorkload} is <b>the ask</b>: every quantity in it is something the user controls, and the
 * whole force of a finding's evidence is flat inputs printed beside a moved output. Output tokens are
 * the agent's own choice, so folding them in there would quietly destroy that argument in the one block
 * that exists to make it. The two therefore print as two blocks and are stored in two columns; nothing
 * here is a covariate either, and nothing is normalized away.
 *
 * <p><b>Persisted, because a reference window is history.</b> Same reason as the workload: by the time a
 * finding is written, the traffic the pinned sketch summarizes may be weeks past, and no query can
 * re-derive what its cache-read share was then.
 */
public final class MetricTokens {

    /**
     * The four token buckets, keyed by the PERSISTED measure names of {@link Measure} so the evidence
     * block and {@code metric_baseline.measure} cannot come to call the same quantity two things.
     *
     * <p>{@code tok_cache_write} is folded only where the model is billed for cache creation, so on a
     * bucket whose traffic never was, its sketch stays empty and its evidence pair reads {@code null}
     * rather than {@code 0} — "not measured" rather than "no writes"
     * ({@link ai.tessary.evals.vitals.TokenPriceBook#billsCacheCreation}).
     */
    public static final String INPUT = Measure.TOK_INPUT;

    public static final String OUTPUT = Measure.TOK_OUTPUT;

    public static final String CACHE_READ = Measure.TOK_CACHE_READ;

    public static final String CACHE_WRITE = Measure.TOK_CACHE_WRITE;

    /**
     * The cache-read share of the prompt, {@code cache_read / (cache_read + input)}, in <b>percent</b>.
     *
     * <p>The ratio rather than the raw count is what PROGRAM.md §3.3 asks cache to be watched in: the
     * most common silent cost regression is a prompt-prefix edit that stops the cache hitting, and on the
     * share that reads as a clean collapse from ~80% to ~0% while the raw count is indistinguishable from
     * a quiet week, because the count moves with traffic volume and the share does not.
     *
     * <p>Percent rather than a 0..1 fraction purely for resolution. These are geometric bins, so they
     * resolve a fixed <em>relative</em> step; under {@code log1p} a fraction near 0.8 sits where the
     * transform is almost linear and one bin spans ~11% of the value, against ~5% for the same share
     * written as 80. The quantity is identical and the field name says which unit it is in.
     */
    public static final String CACHE_READ_PCT = "cache_read_pct";

    /** The five quantities, in the order the evidence blob prints them. */
    public static final List<String> QUANTITIES = List.of(INPUT, OUTPUT, CACHE_READ, CACHE_WRITE, CACHE_READ_PCT);

    /**
     * The shared layout: dimensionless counts, so {@code lo = 1}. With the default 5%-per-bin ratio the
     * grid spans 1 to ≈ 6·10⁶, which covers a context window in tokens with room over, and a percentage
     * many times over.
     */
    public static Grid grid(int bins) {
        return new Grid(1.0, MetricHistogram.DEFAULT_RATIO, bins);
    }

    /**
     * Samples are stored as {@code log1p(v)} and read back through {@code expm1}, exactly as
     * {@link MetricWorkload} does and for a sharper version of the same reason: <b>zero is the signal
     * here</b>. A cache-read count of 0 and a cache-read share of 0% ARE the prompt-prefix regression, so
     * they have to land on the grid as values rather than in the underflow slot {@code log(0) = -inf}
     * would put them in. The offset moves zero onto the grid's own floor and is undone on every read.
     */
    private static double toLog(double raw) {
        return Math.log1p(Math.max(0.0, raw));
    }

    private static double fromLog(double logValue) {
        return Math.expm1(logValue);
    }

    private final Grid grid;

    /** One sketch per {@link #QUANTITIES} entry, positionally; every slot always exists. */
    private final MetricSketch[] sketches;

    public MetricTokens(Grid grid) {
        this.grid = grid;
        this.sketches = new MetricSketch[QUANTITIES.size()];
        for (int i = 0; i < sketches.length; i++) {
            sketches[i] = new MetricHistogram(grid);
        }
    }

    /**
     * Fold one turn's decomposition in. Each quantity is folded independently and a null contributes
     * nothing rather than a zero — that is the whole abstention (a bucket the provider does not report is
     * unknown, not empty), and averaging the unknown in as zero would manufacture exactly the collapse
     * this block exists to detect.
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

    /** Fold another window's decomposition in, in place. Exact, bin for bin, per quantity. */
    public void merge(MetricTokens other) {
        for (int i = 0; i < sketches.length; i++) {
            sketches[i].merge(other.sketches[i]);
        }
    }

    /** An independent copy — what the window roll (current → prev) needs. */
    public MetricTokens copy() {
        MetricTokens out = new MetricTokens(grid);
        out.merge(this);
        return out;
    }

    /** Whether any quantity carries a sample. A window nothing was reported for writes no blob at all. */
    public boolean isEmpty() {
        for (MetricSketch sketch : sketches) {
            if (sketch.count() > 0) return false;
        }
        return true;
    }

    /** The median of one quantity in its own RAW units, or empty when nothing reported it. */
    public OptionalDouble p50(String quantity) {
        int i = QUANTITIES.indexOf(quantity);
        if (i < 0 || sketches[i].count() == 0) return OptionalDouble.empty();
        Double q = sketches[i].quantile(0.5);
        return q == null ? OptionalDouble.empty() : OptionalDouble.of(fromLog(q));
    }

    /** Serialized form for {@code metric_baseline.*_tokens_json}. Round-trips through {@link #fromJson}. */
    public String toJson() {
        ObjectNode root = MetricHistogram.JSON.createObjectNode();
        for (int i = 0; i < sketches.length; i++) {
            try {
                root.set(QUANTITIES.get(i), MetricHistogram.JSON.readTree(sketches[i].toJson()));
            } catch (JsonProcessingException ex) {
                // A sketch that cannot re-read its own output is a bug in the sketch, not a data problem.
                throw new IllegalStateException("metric sketch produced unreadable json", ex);
            }
        }
        return root.toString();
    }

    /**
     * Rehydrate a decomposition written by {@link #toJson()}.
     *
     * <p>A quantity missing from the payload, or written on a different grid because {@code hist_bins}
     * was edited under a live project, comes back empty rather than throwing — the set stays additive, and
     * a sketch on a dead grid can never be merged or compared again anyway. The next close writes a
     * readable one over it.
     *
     * @throws IllegalArgumentException on malformed JSON or a sketch that will not parse
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
