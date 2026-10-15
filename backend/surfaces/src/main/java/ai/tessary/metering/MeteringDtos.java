// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.metering.LlmUsageQueryRepository.UsageCell;
import ai.tessary.metering.LlmUsageQueryRepository.UsageSlice;
import ai.tessary.usage.MetricRollupRepository.UsageBucket;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Every view carries {@code as_of}, so "no usage yet" is distinguishable from "rollup not run yet". */
public final class MeteringDtos {

    private MeteringDtos() {}

    public record UsageBucketView(
            @JsonProperty("bucket_start") String bucketStart, long value) {}

    public record UsageTimeseriesView(
            String unit, @JsonProperty("as_of") String asOf, List<UsageBucketView> buckets) {

        public static UsageTimeseriesView of(String unit, List<UsageBucket> rows) {
            return new UsageTimeseriesView(
                    unit,
                    Instant.now().toString(),
                    rows.stream()
                            .map(b -> new UsageBucketView(b.bucketStart(), b.value()))
                            .toList());
        }
    }

    /**
     * Token buckets are kept apart because they are priced apart. {@code unpricedCalls} is the size of the gap behind
     * {@code costUsd}. Platform and BYO cost must not be re-added into one figure.
     */
    public record LlmUsageSliceView(
            String key,
            @Nullable String label,
            long calls,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cache_read_tokens") long cacheReadTokens,
            @JsonProperty("cache_write_tokens") long cacheWriteTokens,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("cost_usd") BigDecimal costUsd,
            @JsonProperty("platform_cost_usd") BigDecimal platformCostUsd,
            @JsonProperty("byo_cost_usd") BigDecimal byoCostUsd,
            @JsonProperty("unpriced_calls") long unpricedCalls) {

        public static LlmUsageSliceView of(UsageSlice s, @Nullable String label) {
            return new LlmUsageSliceView(
                    s.key() == null ? "" : s.key(),
                    label,
                    s.calls(),
                    s.inputTokens(),
                    s.outputTokens(),
                    s.cacheReadTokens(),
                    s.cacheWriteTokens(),
                    s.totalTokens(),
                    s.costUsd(),
                    s.platformCostUsd(),
                    s.byoCostUsd(),
                    s.unpricedCalls());
        }
    }

    /** Read live off the ledger, so {@code asOf} is genuinely now. A null bound is open. */
    public record LlmUsageView(
            @Nullable String from,
            @Nullable String to,
            @JsonProperty("as_of") String asOf,
            LlmUsageSliceView total,
            @JsonProperty("by_lane") List<LlmUsageSliceView> byLane,
            @JsonProperty("by_project") List<LlmUsageSliceView> byProject,
            @JsonProperty("by_model") List<LlmUsageSliceView> byModel) {}

    /** {@code key} is the empty string both for the ungrouped series and for calls that reported no lane or model. */
    public record LlmUsageCellView(
            @JsonProperty("bucket_start") String bucketStart,
            String key,
            @Nullable String label,
            long calls,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cache_read_tokens") long cacheReadTokens,
            @JsonProperty("cache_write_tokens") long cacheWriteTokens,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("cost_usd") BigDecimal costUsd) {

        public static LlmUsageCellView of(UsageCell c) {
            return new LlmUsageCellView(
                    c.bucketStart(),
                    c.key(),
                    c.label(),
                    c.calls(),
                    c.inputTokens(),
                    c.outputTokens(),
                    c.cacheReadTokens(),
                    c.cacheWriteTokens(),
                    c.totalTokens(),
                    c.costUsd());
        }
    }

    /**
     * {@code buckets} is the full axis, so a quiet bucket renders as a gap. {@code total} uses the same filter, so
     * headline and bars cannot disagree.
     */
    public record LlmUsageSeriesView(
            String from,
            String to,
            String grain,
            String grouping,
            @JsonProperty("as_of") String asOf,
            LlmUsageSliceView total,
            List<String> buckets,
            List<LlmUsageCellView> cells) {}

    /** @param runs above 1 means it was re-triaged */
    public record TriageSpendRowView(
            @JsonProperty("finding_id") String findingId,
            long runs,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("cost_usd") BigDecimal costUsd,
            @JsonProperty("unpriced_runs") long unpricedRuns,
            @JsonProperty("last_at") String lastAt) {}

    /** Null when nothing was priced: absent rather than a misleading zero. */
    public record TriageSpendView(
            @Nullable String from,
            @Nullable String to,
            @JsonProperty("as_of") String asOf,
            long rulings,
            @JsonProperty("cost_usd") BigDecimal costUsd,
            @JsonProperty("cost_per_ruling_usd") @Nullable BigDecimal costPerRulingUsd,
            @JsonProperty("unpriced_runs") long unpricedRuns,
            @JsonProperty("by_ruling") List<TriageSpendRowView> byRuling) {}
}
