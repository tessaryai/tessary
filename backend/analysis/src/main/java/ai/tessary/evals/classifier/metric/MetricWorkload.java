// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import ai.tessary.evals.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * What the USER asked for during one window, summarized alongside what the agent did with it — three
 * bounded sketches that ride each window slot of a {@code metric_baseline} row and are reported, then
 * versus now, in a finding's evidence blob ({@code classifiers/metric_drift/PROGRAM.md} §7).
 *
 * <p><b>This block is not decoration, and leaving it out breaks the correction loop.</b> Triage audits
 * whether the finding's claim holds, and "the population moved" is only a claim about the agent if the
 * traffic held still. A triage agent handed "turns are 1.4× slower" and nothing about the input has no
 * way to tell a regression from a Monday, so it rules {@code positive} on a shift that is entirely the
 * users' — the verdict stops carrying information. Flat inputs printed beside moved outputs is the
 * entire argument for "the agent changed, not the traffic", and it is an argument only this record can
 * make.
 *
 * <p><b>Condition on the ask, never on the answer</b> (PROGRAM.md §3.2). Every quantity here is
 * something the user controls — how big the prompt was, how much they typed, how deep into the thread
 * they are. The agent's own choices are deliberately absent: span count, tool-call count and kind mix
 * are not workload, because an agent decomposing "what's my balance" into eleven tool calls <em>is</em>
 * the bug. Report duration against span count and that bug becomes its own explanation, the residual
 * goes flat, and the detector says nothing.
 *
 * <p>Note this is <b>evidence, never a covariate</b>. Nothing regresses the measure on these numbers or
 * normalizes them away; they are printed next to the shift and a human (or the triage agent) reads both.
 *
 * <p><b>Persisted, because a reference window is history.</b> The pinned sketch summarizes traffic that
 * may be weeks past by the time a finding is written, and no query can re-derive what the input looked
 * like then. So the workload rotates and re-pins in lockstep with the measure sketch it sits beside,
 * one blob per window slot.
 */
public final class MetricWorkload {

    /**
     * Prompt size the user's request arrived as — the fresh input tokens summed over the trace's
     * generations. The closest thing to "how much did they ask for" that survives across providers.
     */
    public static final String INPUT_TOKENS = "input_tokens";

    /**
     * Characters in the turn's own input text. Coarser than tokens and independent of the tokenizer, so
     * the two disagreeing is itself informative: same characters, more tokens means the prompt template
     * grew, which is the agent changing rather than the traffic.
     */
    public static final String USER_MSG_CHARS = "user_msg_chars";

    /**
     * How many turns preceded this one in its conversation. Thread depth is the workload dimension a
     * chat product moves without anyone deploying anything — a cohort that started holding longer
     * conversations makes every turn carry more history, and every turn legitimately slower.
     */
    public static final String PRIOR_TURNS = "prior_turns";

    /** The three quantities, in the order the evidence blob prints them. */
    public static final List<String> QUANTITIES = List.of(INPUT_TOKENS, USER_MSG_CHARS, PRIOR_TURNS);

    /**
     * The shared layout. Dimensionless counts rather than a unit, so {@code lo = 1}: with the default
     * 5%-per-bin ratio the grid spans 1 to ≈ 6·10⁶, which comfortably covers a context window in tokens,
     * a long message in characters, and a conversation in turns.
     */
    public static Grid grid(int bins) {
        return new Grid(1.0, MetricHistogram.DEFAULT_RATIO, bins);
    }

    /**
     * Samples are stored as {@code log1p(v)} and read back through {@code expm1}, not as {@code log(v)}.
     *
     * <p>All three quantities are counts that legitimately reach <b>zero</b> — the first turn of a
     * conversation has no prior turns, and a turn whose producer shipped no input text has no
     * characters. {@code log(0)} is {@code -inf}, which the sketch would count in its underflow slot;
     * a bucket whose users mostly open fresh conversations would then report its whole workload pinned
     * at the grid edge, and "prior_turns_p50 = 1" for a population whose median is 0 is a quiet lie in
     * the one field that exists to be believed. The offset moves the zero onto the grid's own floor,
     * costs nothing anywhere else, and is undone on every read.
     */
    private static double toLog(double raw) {
        return Math.log1p(Math.max(0.0, raw));
    }

    private static double fromLog(double logValue) {
        return Math.expm1(logValue);
    }

    private final Grid grid;

    /**
     * One sketch per {@link #QUANTITIES} entry, positionally. An array rather than a map because every
     * slot is always populated — the set is fixed at three — so a map would only add a lookup that can
     * return nothing on a key that always exists.
     */
    private final MetricSketch[] sketches;

    public MetricWorkload(Grid grid) {
        this.grid = grid;
        this.sketches = new MetricSketch[QUANTITIES.size()];
        for (int i = 0; i < sketches.length; i++) {
            sketches[i] = new MetricHistogram(grid);
        }
    }

    /**
     * Fold one turn's readings in. Each quantity is folded independently and a missing one contributes
     * nothing rather than a zero: a producer that ships no input text has an unknown message length, not
     * an empty message, and averaging the unknown in as zero would manufacture a workload collapse out
     * of an instrumentation gap.
     */
    public void add(@Nullable Double inputTokens, @Nullable Double userMsgChars, @Nullable Double priorTurns) {
        addOne(INPUT_TOKENS, inputTokens);
        addOne(USER_MSG_CHARS, userMsgChars);
        addOne(PRIOR_TURNS, priorTurns);
    }

    private void addOne(String quantity, @Nullable Double raw) {
        if (raw == null || !Double.isFinite(raw)) return;
        sketches[QUANTITIES.indexOf(quantity)].add(toLog(raw));
    }

    /** Fold another window's workload in, in place. Exact, bin for bin, per quantity. */
    public void merge(MetricWorkload other) {
        for (int i = 0; i < sketches.length; i++) {
            sketches[i].merge(other.sketches[i]);
        }
    }

    /** An independent copy — what the window roll (current → prev) needs. */
    public MetricWorkload copy() {
        MetricWorkload out = new MetricWorkload(grid);
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

    /** Serialized form for {@code metric_baseline.*_workload_json}. Round-trips through {@link #fromJson}. */
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
     * Rehydrate a workload written by {@link #toJson()}.
     *
     * <p>A quantity missing from the payload comes back empty rather than throwing, which is what makes
     * the set additive: a later build that adds a fourth quantity can read every blob this one wrote. A
     * quantity written on a different grid — {@code hist_bins} edited under a live project — comes back
     * empty for the same reason the measure sketch does: it can never be merged or compared again, and
     * the next close writes a readable one over it.
     *
     * @throws IllegalArgumentException on malformed JSON or a sketch that will not parse
     */
    public static MetricWorkload fromJson(String json, Grid grid) {
        JsonNode node;
        try {
            node = MetricHistogram.JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric workload json", e);
        }
        MetricWorkload out = new MetricWorkload(grid);
        for (int i = 0; i < QUANTITIES.size(); i++) {
            JsonNode child = node.get(QUANTITIES.get(i));
            if (child == null || child.isNull()) continue;
            MetricSketch sketch = MetricSketch.fromJson(child.toString());
            if (sketch.gridId().equals(grid.id())) out.sketches[i] = sketch;
        }
        return out;
    }
}
