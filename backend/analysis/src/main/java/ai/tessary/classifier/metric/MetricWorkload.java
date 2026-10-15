// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.util.List;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * What the user asked for, printed beside what the agent did: flat inputs next to a moved output is the only argument
 * that the agent changed, not the traffic. Condition on the ask, never the answer: span and tool-call counts are
 * deliberately absent, since an agent making eleven calls where three did is the bug. Evidence, never a covariate.
 * Persisted, since a reference window's traffic may be weeks gone.
 */
public final class MetricWorkload {

    public static final String INPUT_TOKENS = "input_tokens";

    /** Independent of the tokenizer, so same characters with more tokens means the prompt template grew. */
    public static final String USER_MSG_CHARS = "user_msg_chars";

    /** Thread depth moves without any deploy: longer conversations make every turn legitimately slower. */
    public static final String PRIOR_TURNS = "prior_turns";

    public static final List<String> QUANTITIES = List.of(INPUT_TOKENS, USER_MSG_CHARS, PRIOR_TURNS);

    public static Grid grid(int bins) {
        return new Grid(1.0, MetricHistogram.DEFAULT_RATIO, bins);
    }

    /**
     * Stored as {@code log1p} because these counts reach zero; {@code log(0)} would push a mostly-first-turn bucket
     * into underflow and misreport its median.
     */
    private static double toLog(double raw) {
        return Math.log1p(Math.max(0.0, raw));
    }

    private static double fromLog(double logValue) {
        return Math.expm1(logValue);
    }

    private final Grid grid;

    private final MetricSketch[] sketches;

    public MetricWorkload(Grid grid) {
        this.grid = grid;
        this.sketches = new MetricSketch[QUANTITIES.size()];
        for (int i = 0; i < sketches.length; i++) {
            sketches[i] = new MetricHistogram(grid);
        }
    }

    /**
     * A missing reading contributes nothing rather than zero, or an instrumentation gap would look like a workload
     * collapse.
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

    public void merge(MetricWorkload other) {
        for (int i = 0; i < sketches.length; i++) {
            sketches[i].merge(other.sketches[i]);
        }
    }

    public MetricWorkload copy() {
        MetricWorkload out = new MetricWorkload(grid);
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
     * A missing quantity comes back empty so a later build can add one; a quantity on a different grid (after a
     * {@code hist_bins} edit) comes back empty and is overwritten on the next close.
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
