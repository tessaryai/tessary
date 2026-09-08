// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.vitals;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The vitals wire shape: two deterministic statistics per dimension value, each carrying its own
 * baseline so the client never has to recompute a comparison the server already made.
 *
 * <p>Every metric reports its <b>volume</b> alongside its value. A statistic without its denominator is
 * unreadable — a p95 over two turns and one over two thousand are not the same finding, and the client
 * has no way to tell them apart otherwise.
 *
 * <p><b>Tool errors were the third and are gone from this shape entirely</b>, along with the
 * {@code tool_errors} field on {@link Group}. They are a classifier now
 * ({@code classifiers/tool_error/PROGRAM.md}) and are shown on the Classifiers page, where a moved rate
 * arrives with the failure patterns that moved it rather than as a bare percentage. This is a breaking
 * change to {@code GET .../vitals}, taken deliberately at design-partner scale: the Vitals number and
 * the detector must not be able to disagree, and one number in one place is a stronger answer than
 * two that happen to agree today.
 */
public final class VitalsDtos {

    private VitalsDtos() {}

    /** The window compared, and the equal-length window it was compared against. */
    public record Window(
            @JsonProperty("from") String from,
            @JsonProperty("to") String to,
            @JsonProperty("baseline_from") String baselineFrom,
            @JsonProperty("baseline_to") String baselineTo,
            @JsonProperty("days") int days) {}

    /**
     * Spend over the window.
     *
     * @param usd priced spend; excludes whatever {@code unpricedCalls} counts
     * @param unpricedCalls generations on a model the price book does not list — surfaced rather than
     *     priced at zero, because a real spend rendered as free is worse than an admitted gap
     */
    public record Cost(
            @JsonProperty("usd") BigDecimal usd,
            @JsonProperty("tokens") long tokens,
            @JsonProperty("calls") long calls,
            @JsonProperty("unpriced_calls") long unpricedCalls,
            @JsonProperty("usd_per_turn") @Nullable Double usdPerTurn,
            @JsonProperty("baseline_usd") @Nullable BigDecimal baselineUsd,
            // Named for its unit. This compares spend PER TURN, not total spend — traffic doubling is
            // not a regression. Rendering it beside the total `usd` without saying so read as
            // "spend rose 30%" when total spend could be flat or falling.
            @JsonProperty("delta_pct_per_turn") @Nullable Double deltaPctPerTurn,
            @JsonProperty("flagged") boolean flagged) {}

    /**
     * Turn latency over the window, measured on the root span.
     *
     * @param unterminated turns whose root span never ended, so they carry no duration. Counted
     *     because a growing number of stuck turns would otherwise read as improving latency.
     */
    public record Duration(
            @JsonProperty("turns") long turns,
            @JsonProperty("p50_ms") @Nullable Long p50Ms,
            @JsonProperty("p95_ms") @Nullable Long p95Ms,
            @JsonProperty("baseline_p95_ms") @Nullable Long baselineP95Ms,
            @JsonProperty("delta_pct") @Nullable Double deltaPct,
            @JsonProperty("flagged") boolean flagged,
            @JsonProperty("unterminated") long unterminated) {}

    /** Both statistics for one dimension value (or, with a null key, the project total). */
    public record Group(
            @JsonProperty("key") @Nullable String key,
            @JsonProperty("label") @Nullable String label,
            @JsonProperty("cost") Cost cost,
            @JsonProperty("duration") Duration duration,
            @JsonProperty("flagged") boolean flagged) {}

    /** The vitals read. */
    public record Vitals(
            @JsonProperty("window") Window window,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("total") Group total,
            @JsonProperty("groups") List<Group> groups,
            @JsonProperty("priced_models") int pricedModels) {}
}
