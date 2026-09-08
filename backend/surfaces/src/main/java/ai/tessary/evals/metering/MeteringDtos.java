// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.metering;

import ai.tessary.evals.metering.LlmUsageQueryRepository.UsageCell;
import ai.tessary.evals.metering.LlmUsageQueryRepository.UsageSlice;
import ai.tessary.evals.usage.MetricRollupRepository.UsageBucket;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the usage-metering read API. Snake_case on the wire, camelCase in Java (the
 * {@code QueryDtos} convention). Every usage view carries an {@code as_of} freshness instant so a caller
 * can tell "no usage yet this period" apart from "the rollup hasn't run yet" — usage lags by up to one
 * bucket grain because only closed buckets are metered.
 */
public final class MeteringDtos {

    private MeteringDtos() {}

    /** One {@code (bucket_start, value)} point of usage. */
    public record UsageBucketView(
            @JsonProperty("bucket_start") String bucketStart, long value) {}

    /** The project-scoped usage timeseries for one unit. */
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
     * One group of LLM usage — a lane, a project, a model, or the whole org — with the token buckets
     * kept apart rather than collapsed to a total, because they are priced apart.
     *
     * <p>{@code key} is the group's stable identifier ({@code lane} wire value, project id, model id;
     * empty string for calls that reported no model) and is empty for the org total. {@code label} is
     * its display name where the server knows one the client cannot derive (a project's name).
     *
     * <p>{@code costUsd} covers only the calls the pricing catalog held a rate for; {@code
     * unpricedCalls} is how many it did not, so a reader can see the size of the gap instead of
     * mistaking it for free usage. {@code platformCostUsd} and {@code byoCostUsd} split the same total
     * by whose credential paid and must not be re-added into one figure.
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

        /** Project the repository slice onto the wire, with the display label the caller resolved. */
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

    /**
     * The org's platform LLM consumption over {@code [from, to)} — one total plus the same total cut
     * three ways (by product lane, by project, by model). Unlike {@link UsageTimeseriesView} this is
     * read live off the per-call ledger rather than off closed rollup buckets, so {@code asOf} is
     * genuinely now: it includes the call that finished a second ago.
     *
     * <p>A null {@code from}/{@code to} is an open bound — the org's whole history on that side.
     */
    public record LlmUsageView(
            @Nullable String from,
            @Nullable String to,
            @JsonProperty("as_of") String asOf,
            LlmUsageSliceView total,
            @JsonProperty("by_lane") List<LlmUsageSliceView> byLane,
            @JsonProperty("by_project") List<LlmUsageSliceView> byProject,
            @JsonProperty("by_model") List<LlmUsageSliceView> byModel) {}

    /**
     * One {@code (bucket, series)} cell of the LLM usage timeseries — the value of one bar segment.
     *
     * <p>{@code bucketStart} is the bucket's inclusive start as a UTC instant and joins to an entry of
     * {@link LlmUsageSeriesView#buckets}. {@code key} identifies the series within the requested
     * grouping (a lane wire value, a project id, a model id; the empty string both for the ungrouped
     * series and for calls that reported no lane/model), and {@code label} is its display name where the
     * server knows one the client cannot derive.
     */
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
     * The org's LLM consumption over {@code [from, to)} as a bucketed timeseries — what the usage chart
     * draws. Same live per-call ledger as {@link LlmUsageView}, cut on two more axes: {@code grain} (the
     * bucket width) and {@code grouping} (the series axis), with an optional lane / project / model
     * narrowing already applied.
     *
     * <p>{@code buckets} is the complete x-axis, ascending, including buckets with no calls —
     * {@code cells} only carries the non-empty ones, so a client renders a gap for a quiet bucket
     * instead of silently compressing the time axis. {@code total} is the whole window under the same
     * filter, so the headline figures and the bars can never disagree.
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

    /**
     * What one Layer-2 ruling cost — one row of {@link TriageSpendView}.
     *
     * @param findingId the {@code behavior_finding} the ruling was about, so the spend and the verdict
     *     can be put side by side
     * @param runs sandbox runs booked against it, normally 1; above 1 means it was re-triaged
     * @param unpricedRuns runs the pricing catalog held no rate for — the gap behind {@code cost_usd}
     */
    public record TriageSpendRowView(
            @JsonProperty("finding_id") String findingId,
            long runs,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("cost_usd") BigDecimal costUsd,
            @JsonProperty("unpriced_runs") long unpricedRuns,
            @JsonProperty("last_at") String lastAt) {}

    /**
     * The org's triage spend, per ruling, costliest first — the read launch requirement H2 means by
     * "attributable per triage", and the one H3 turns on.
     *
     * <p>{@code costPerRulingUsd} is the whole point. The lane total already says the triage agent cost
     * this org $X; only dividing it by the number of rulings answers whether an agent session per
     * distinct cause is the right price for a filter, which is the question the one-path amendment (D14)
     * left open. Null when nothing was priced — an absent number rather than a misleading zero, the same
     * convention {@code llm_call.cost_usd} carries.
     */
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
