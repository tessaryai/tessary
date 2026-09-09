// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The session read shapes, assembled by {@link SessionReadService} and rendered unchanged by both
 * {@link SessionsController} and the MCP {@code list_sessions} / {@code get_session} readers.
 *
 * <p>They sit beside the service rather than inside the controller because the controller is no longer the
 * only surface that serves them, and a session's honesty devices ({@code unsettled_traces},
 * {@code traces_truncated}) are only honest if every surface carries them. Two assemblies is how one wire
 * quietly loses the field that says a total is a lower bound.
 */
public final class SessionDtos {

    private SessionDtos() {}

    /**
     * One session's identity row, plus totals when the caller asked for them ({@code include=totals}).
     *
     * <p>The totals fields are null unless requested — the list stays the cheap identity-only read by
     * default (see {@link SessionReadService} for why: a page of totals is a batched read over the page's
     * own session ids, not a rollup, and it is opt-in so a caller that only wants identities never pays for
     * it). {@code dominant_call_site_id} is the call site that touched this session most (ties broken by
     * most recent); {@code call_site_count} is how many distinct call sites did, so a viewer can tell a
     * single-agent session from one a caller should render as "X +N".
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
            // The same per-direction breakdown TraceListItem carries, summed across the page's sessions in
            // the same batched query as the totals above — one rollup shape for both surfaces, rather than a
            // trace row that can answer "how much was input vs. output" and a session row that can't.
            @JsonProperty("input_tokens") @Nullable Long inputTokens,
            @JsonProperty("output_tokens") @Nullable Long outputTokens,
            @JsonProperty("cache_read_tokens") @Nullable Long cacheReadTokens,
            @JsonProperty("cache_write_tokens") @Nullable Long cacheWriteTokens,
            @JsonProperty("reasoning_tokens") @Nullable Long reasoningTokens,
            @JsonProperty("input_cost") @Nullable BigDecimal inputCost,
            @JsonProperty("output_cost") @Nullable BigDecimal outputCost,
            // The session bracketed as one interaction: what started it, what it most recently produced.
            // Not an aggregate — the first trace's own input and the last trace's own output, verbatim.
            @JsonProperty("first_input_preview") @Nullable String firstInputPreview,
            @JsonProperty("last_output_preview") @Nullable String lastOutputPreview) {}

    /** A page of sessions, most recently active first, plus the cursor for the next (older) page. */
    public record SessionsPage(
            List<SessionListItem> sessions,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /**
     * One session with its totals and its traces.
     *
     * <p>{@code unsettled_traces} is the §7.5 honesty device — the count of this session's traces that are
     * still receiving spans, and therefore the number of addends in the totals below that are provisional.
     * {@code unpriced_spans} is the same device one level down: spend we could not price at all.
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
            List<TraceDtos.TraceListItem> traces) {}

    /**
     * Every span across a session's traces, in one read — the heavier sibling of {@link SessionDetail},
     * deliberately its own endpoint rather than a field on it (see {@link SessionsController}): a viewer
     * that only wants totals should never pay for span payloads it did not ask for.
     *
     * <p>The traces read is the same capped, ordered list {@link SessionDetail#traces} is built from, so the
     * two responses always describe the same trace population — a viewer never sees spans belonging to a
     * trace {@code SessionDetail} did not also list. {@code spans_truncated} caps separately, at
     * {@link SessionReadService#SESSION_SPAN_CAP}, because a session capped at 1,000 traces can still carry
     * far more spans than that: a viewer rendering Tree/Timeline should treat spans as possibly partial even
     * when {@code traces_truncated} on the paired {@link SessionDetail} is false.
     */
    public record SessionSpans(
            List<TracesController.SpanView> spans,
            @JsonProperty("spans_truncated") boolean spansTruncated) {}
}
