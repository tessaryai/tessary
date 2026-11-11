// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The Prompts page's call counts: one row per prompt name for the list, one row per version for a prompt. */
public final class PromptUsageDtos {

    private PromptUsageDtos() {}

    public record PromptCalls(String name, long calls) {}

    public record PromptUsageSummary(int days, List<PromptCalls> items) {}

    /**
     * {@code version} is the string the producer sent, null when it sent none. A null token or cost sum means no
     * linked span reported one, never zero; {@code unpriced_calls} says how many calls the cost leaves out.
     */
    public record VersionUsage(
            @Nullable String version,
            long calls,
            @JsonProperty("error_calls") long errorCalls,
            @JsonProperty("p50_ms") @Nullable Long p50Ms,
            @JsonProperty("p95_ms") @Nullable Long p95Ms,
            @JsonProperty("input_tokens") @Nullable Long inputTokens,
            @JsonProperty("output_tokens") @Nullable Long outputTokens,
            @JsonProperty("cost_usd") @Nullable BigDecimal costUsd,
            @JsonProperty("unpriced_calls") long unpricedCalls,
            @JsonProperty("first_seen") String firstSeen,
            @JsonProperty("last_seen") String lastSeen) {

        static VersionUsage from(PromptUsageRepository.VersionUsageRow row) {
            BigDecimal cost = row.costUsd();
            return new VersionUsage(
                    row.version(),
                    row.calls(),
                    row.errorCalls(),
                    rounded(row.p50Ms()),
                    rounded(row.p95Ms()),
                    row.inputTokens(),
                    row.outputTokens(),
                    cost == null ? null : cost.setScale(6, RoundingMode.HALF_UP),
                    row.unpricedCalls(),
                    row.firstSeen(),
                    row.lastSeen());
        }

        private static @Nullable Long rounded(@Nullable Double ms) {
            return ms == null ? null : Math.round(ms);
        }
    }

    /** {@code unknown_versions}: version strings spans sent that name no version of this prompt, sorted. */
    public record PromptUsage(
            int days,
            List<VersionUsage> versions,
            @JsonProperty("unknown_versions") List<String> unknownVersions) {}
}
