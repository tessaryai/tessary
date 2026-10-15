// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Session read shapes shared by {@link SessionsController} and the MCP session readers, so every surface carries the
 * honesty fields ({@code unsettled_traces}, {@code traces_truncated}).
 */
public final class SessionDtos {

    private SessionDtos() {}

    /**
     * Totals are null unless {@code include=totals}. {@code dominant_call_site_id} is the call site that touched this
     * session most (ties to most recent); {@code call_site_count} lets a viewer render "X +N".
     */
    public record SessionListItem(
            String id,
            @JsonProperty("user_id") @Nullable String userId,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("last_activity_at") String lastActivityAt,
            @JsonProperty("trace_count") @Nullable Integer traceCount,
            @JsonProperty("unsettled_traces") @Nullable Integer unsettledTraces,
            @JsonProperty("span_count") @Nullable Long spanCount,
            @JsonProperty("error_count") @Nullable Long errorCount,
            @JsonProperty("total_tokens") @Nullable Long totalTokens,
            @JsonProperty("total_cost") @Nullable BigDecimal totalCost,
            @JsonProperty("unpriced_spans") @Nullable Long unpricedSpans,
            @JsonProperty("dominant_call_site_id") @Nullable String dominantCallSiteId,
            @JsonProperty("call_site_count") @Nullable Integer callSiteCount,
            // The same per-direction breakdown TraceListItem carries, summed in the same batched query as the totals.
            @JsonProperty("input_tokens") @Nullable Long inputTokens,
            @JsonProperty("output_tokens") @Nullable Long outputTokens,
            @JsonProperty("cache_read_tokens") @Nullable Long cacheReadTokens,
            @JsonProperty("cache_write_tokens") @Nullable Long cacheWriteTokens,
            @JsonProperty("reasoning_tokens") @Nullable Long reasoningTokens,
            @JsonProperty("input_cost") @Nullable BigDecimal inputCost,
            @JsonProperty("output_cost") @Nullable BigDecimal outputCost,
            // Not an aggregate: the first trace's own input and the last trace's own output, verbatim.
            @JsonProperty("first_input_preview") @Nullable String firstInputPreview,
            @JsonProperty("last_output_preview") @Nullable String lastOutputPreview,
            @JsonProperty("detected_by") List<TraceDtos.DetectionLabel> detectedBy) {}

    public record SessionsPage(
            List<SessionListItem> sessions,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /**
     * {@code unsettled_traces} counts traces still receiving spans, i.e. provisional addends; {@code unpriced_spans}
     * counts spend that could not be priced.
     */
    public record SessionDetail(
            String id,
            @JsonProperty("user_id") @Nullable String userId,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("last_activity_at") String lastActivityAt,
            @JsonProperty("trace_count") int traceCount,
            @JsonProperty("unsettled_traces") int unsettledTraces,
            @JsonProperty("span_count") @Nullable Long spanCount,
            @JsonProperty("error_count") @Nullable Long errorCount,
            @JsonProperty("total_tokens") @Nullable Long totalTokens,
            @JsonProperty("total_cost") @Nullable BigDecimal totalCost,
            @JsonProperty("unpriced_spans") @Nullable Long unpricedSpans,
            @JsonProperty("traces_truncated") boolean tracesTruncated,
            List<TraceDtos.TraceListItem> traces,
            // Each span a classifier flagged in these traces, so the views can mark where it occurred.
            List<TraceDtos.DetectionMark> detections,
            // The traces that pass the caller's filters, oldest first; null when the caller set none.
            @JsonProperty("matched_trace_ids") @Nullable List<String> matchedTraceIds) {}

    /**
     * One conversation's turns, the grain the frustration classifier scores and RCA cites. Carries no totals,
     * because nothing reads them.
     */
    public record ConversationDetail(
            String id,
            @JsonProperty("traces_truncated") boolean tracesTruncated,
            List<TraceDtos.TraceListItem> traces) {}

    /**
     * Its own endpoint so a totals-only viewer never pays for payloads. Reads the same capped trace list as
     * {@link SessionDetail#traces}; {@code spans_truncated} caps separately at
     * {@link SessionReadService#SESSION_SPAN_CAP}, so spans may be partial even when traces are not.
     */
    public record SessionSpans(
            List<TracesController.SpanView> spans,
            @JsonProperty("spans_truncated") boolean spansTruncated) {}
}
