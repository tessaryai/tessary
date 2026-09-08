// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.traces;

import ai.tessary.evals.storage.TraceV2Repository;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The trace <b>list</b> shapes — the row and the page — plus the one mapping from a repository
 * {@link TraceV2Repository.Summary} onto that row.
 *
 * <p>They live here rather than nested in {@link TracesController} because three surfaces render them and
 * only one of those is that controller: the traces list, a session's detail ({@link SessionDtos.SessionDetail}
 * carries the session's traces as these rows), and the MCP {@code list_traces} reader. A second mapping
 * function is how a rollup column silently starts meaning two things on two wires.
 *
 * <p>The <i>detail</i> shapes stay on the controller ({@code TraceDetail}, {@code SpanView} and its
 * tool-call/retrieval children). Nothing outside it renders those — MCP's {@code get_trace} projects the
 * span columns itself — so moving them would buy nothing.
 */
public final class TraceDtos {

    private TraceDtos() {}

    /**
     * One trace's list row — its identity, its timing, and its rollup columns verbatim.
     *
     * <p>{@code status} is null for a trace that has not rolled up: it has no error count, so it is
     * neither ok nor errored, and saying "ok" would be an invention.
     *
     * <p>The previews are the stored {@code input_preview}/{@code output_preview} columns, not the payload.
     * A list row never carries the full conversation — that is what a point read is for — and a reader that
     * treats a preview as the whole text is reasoning over a truncation.
     */
    public record TraceListItem(
            String id,
            @Nullable String name,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("ended_at") @Nullable String endedAt,
            @JsonProperty("latency_ms") @Nullable Long latencyMs,
            @Nullable String session,
            @JsonProperty("user_id") @Nullable String userId,
            @JsonProperty("thread_id") @Nullable String threadId,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            @JsonProperty("span_count") @Nullable Integer spanCount,
            @JsonProperty("error_count") @Nullable Integer errorCount,
            @Nullable String status,
            @JsonProperty("input_tokens") @Nullable Long inputTokens,
            @JsonProperty("output_tokens") @Nullable Long outputTokens,
            @JsonProperty("cache_read_tokens") @Nullable Long cacheReadTokens,
            @JsonProperty("cache_write_tokens") @Nullable Long cacheWriteTokens,
            @JsonProperty("reasoning_tokens") @Nullable Long reasoningTokens,
            @JsonProperty("total_tokens") @Nullable Long totalTokens,
            @JsonProperty("input_cost") @Nullable BigDecimal inputCost,
            @JsonProperty("output_cost") @Nullable BigDecimal outputCost,
            @JsonProperty("total_cost") @Nullable BigDecimal totalCost,
            // Load-bearing, not diagnostic: a trace holding models we have no rate for shows its total AND
            // how much of it could not be priced, so a small number is never mistaken for a cheap turn.
            @JsonProperty("unpriced_spans") @Nullable Integer unpricedSpans,
            // "Nothing has arrived since the last rollup" — never "we gave up waiting".
            @JsonProperty("is_settled") boolean isSettled,
            @JsonProperty("input_preview") @Nullable String inputPreview,
            @JsonProperty("output_preview") @Nullable String outputPreview) {}

    /** A page of traces plus the cursor to fetch the next (older) page, or null at the end. */
    public record TracesPage(
            List<TraceListItem> traces,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /** Map a repository summary onto the wire. The only such mapping; every trace list surface calls it. */
    public static TraceListItem item(TraceV2Repository.Summary s) {
        Integer errors = s.errorCount();
        return new TraceListItem(
                s.id(),
                s.name(),
                s.startedAt(),
                s.endedAt(),
                s.latencyMs(),
                s.sessionId(),
                s.userId(),
                s.threadId(),
                s.callSiteId(),
                s.spanCount(),
                errors,
                errors == null ? null : (errors > 0 ? "error" : "ok"),
                s.inputTokens(),
                s.outputTokens(),
                s.cacheReadTokens(),
                s.cacheWriteTokens(),
                s.reasoningTokens(),
                s.totalTokens(),
                s.inputCost(),
                s.outputCost(),
                s.totalCost(),
                s.unpricedSpans(),
                s.isSettled(),
                s.inputPreview(),
                s.outputPreview());
    }
}
