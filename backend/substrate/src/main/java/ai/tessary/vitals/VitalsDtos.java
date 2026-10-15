// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The vitals wire shape: each metric carries its own baseline and its volume, since a statistic without its denominator
 * is unreadable.
 */
public final class VitalsDtos {

    private VitalsDtos() {}

    public record Window(
            @JsonProperty("from") String from,
            @JsonProperty("to") String to,
            @JsonProperty("baseline_from") String baselineFrom,
            @JsonProperty("baseline_to") String baselineTo,
            @JsonProperty("days") int days) {}

    /**
     * @param usd excludes whatever {@code unpricedCalls} counts @param unpricedCalls generations on an unlisted model,
     * surfaced rather than priced at zero
     */
    public record Cost(
            @JsonProperty("usd") BigDecimal usd,
            @JsonProperty("tokens") long tokens,
            @JsonProperty("calls") long calls,
            @JsonProperty("unpriced_calls") long unpricedCalls,
            @JsonProperty("usd_per_turn") @Nullable Double usdPerTurn,
            @JsonProperty("baseline_usd") @Nullable BigDecimal baselineUsd,
            // Spend PER TURN, not total: shown beside total `usd` without the name it read as "spend rose 30%".
            @JsonProperty("delta_pct_per_turn") @Nullable Double deltaPctPerTurn,
            @JsonProperty("flagged") boolean flagged) {}

    /**
     * @param unterminated turns whose root span never ended, counted because growing stuck turns would otherwise read
     * as improving latency
     */
    public record Duration(
            @JsonProperty("turns") long turns,
            @JsonProperty("p50_ms") @Nullable Long p50Ms,
            @JsonProperty("p95_ms") @Nullable Long p95Ms,
            @JsonProperty("baseline_p95_ms") @Nullable Long baselineP95Ms,
            @JsonProperty("delta_pct") @Nullable Double deltaPct,
            @JsonProperty("flagged") boolean flagged,
            @JsonProperty("unterminated") long unterminated) {}

    /** A null key is the project total. */
    public record Group(
            @JsonProperty("key") @Nullable String key,
            @JsonProperty("label") @Nullable String label,
            @JsonProperty("cost") Cost cost,
            @JsonProperty("duration") Duration duration,
            @JsonProperty("flagged") boolean flagged) {}

    public record Vitals(
            @JsonProperty("window") Window window,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("total") Group total,
            @JsonProperty("groups") List<Group> groups,
            @JsonProperty("priced_models") int pricedModels) {}
}
