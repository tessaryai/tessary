// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricHistogram.Grid;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the user asked for, printed beside what the agent did: flat inputs next to a moved output is the only argument
 * that the agent changed, not the traffic. Condition on the ask, never the answer: span and tool-call counts are
 * deliberately absent, since an agent making eleven calls where three did is the bug. Evidence, never a covariate.
 * Persisted, since a reference window's traffic may be weeks gone.
 */
public final class MetricWorkload extends MetricLogSketches {

    public static final String INPUT_TOKENS = "input_tokens";

    /** Independent of the tokenizer, so same characters with more tokens means the prompt template grew. */
    public static final String USER_MSG_CHARS = "user_msg_chars";

    /** Thread depth moves without any deploy: longer conversations make every turn legitimately slower. */
    public static final String PRIOR_TURNS = "prior_turns";

    public static final List<String> QUANTITIES = List.of(INPUT_TOKENS, USER_MSG_CHARS, PRIOR_TURNS);

    /**
     * Stored as {@code log1p} because these counts reach zero; {@code log(0)} would push a mostly-first-turn bucket
     * into underflow and misreport its median.
     */
    public MetricWorkload(Grid grid) {
        super(QUANTITIES, grid);
    }

    /**
     * A missing reading contributes nothing rather than zero, or an instrumentation gap would look like a workload
     * collapse.
     */
    public void add(@Nullable Double inputTokens, @Nullable Double userMsgChars, @Nullable Double priorTurns) {
        addOne(0, inputTokens);
        addOne(1, userMsgChars);
        addOne(2, priorTurns);
    }

    public void merge(MetricWorkload other) {
        mergeSketches(other);
    }

    public MetricWorkload copy() {
        MetricWorkload out = new MetricWorkload(grid);
        out.merge(this);
        return out;
    }

    /**
     * A missing quantity comes back empty so a later build can add one; a quantity on a different grid (after a
     * {@code hist_bins} edit) comes back empty and is overwritten on the next close.
     */
    public static MetricWorkload fromJson(String json, Grid grid) {
        MetricWorkload out = new MetricWorkload(grid);
        out.readJson(json, "workload");
        return out;
    }
}
