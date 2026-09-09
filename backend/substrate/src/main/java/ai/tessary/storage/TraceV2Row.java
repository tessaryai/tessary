// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 trace row — one turn: one thing the user asked for and waited on (substrate-model.md §5.1).
 *
 * <p>Identity is {@code (projectId, id)} with the producer's trace id verbatim. Everything after
 * {@code status} is a rollup, and every rollup is <b>written by the worker, never by an arrival</b>: the
 * counters and sums are recomputed wholesale from the trace's spans and stored as a replacement, and the
 * three timestamps are folded in with {@code min}/{@code max}. Nothing on this row is ever incremented.
 *
 * <p><b>Null is not zero here.</b> A trace that has not been rolled up yet carries null counters, which is
 * a different statement from "this turn used no tokens" and has to stay tellable apart all the way out to
 * the wire. {@code isSettled} is the companion fact: it means precisely "nothing has arrived since the
 * last rollup", never "we stopped waiting".
 *
 * <p>Timestamps ride as ISO-8601 {@code String}s per the substrate convention. {@code latencyMs} is a
 * generated column — read only, and null until the trace has an end.
 *
 * @param parentTraceId set only for a sub-agent that OUTLIVED the turn. One the user waited on is a span
 *     of this trace, not a trace of its own (§9).
 * @param threadId the provider's conversation id. Sub-grouping inside a session is a column, because
 *     sessions never nest.
 * @param unpricedSpans load-bearing, not diagnostic: it is what stops a total computed over models we
 *     hold no rate for from reading as a cheap turn.
 */
public record TraceV2Row(
        String projectId,
        String id,
        @Nullable String sessionId,
        @Nullable String parentTraceId,
        @Nullable String threadId,
        @Nullable String name,
        @Nullable String userId,
        @Nullable String projectVersionId,
        @Nullable String status,
        String startedAt,
        @Nullable String endedAt,
        @Nullable Long latencyMs,
        @Nullable Integer spanCount,
        @Nullable Integer errorCount,
        @Nullable Long inputTokens,
        @Nullable Long outputTokens,
        @Nullable Long cacheReadTokens,
        @Nullable Long cacheWriteTokens,
        @Nullable Long reasoningTokens,
        @Nullable Long totalTokens,
        @Nullable String inputCost,
        @Nullable String outputCost,
        @Nullable String totalCost,
        @Nullable Integer unpricedSpans,
        @Nullable String inputPreview,
        @Nullable String outputPreview,
        @Nullable String callSiteId,
        @Nullable String rollupDueAt,
        @Nullable String rolledUpAt,
        @Nullable String rolledUpThrough,
        boolean isSettled,
        boolean hasRootSpan,
        String eventTs,
        boolean isDeleted) {

    /**
     * The get-or-create shape: identity and correlation only, every rollup column left null.
     *
     * <p>This is the ONLY constructor ingest should reach for. The §6.1 get-or-create writes identity
     * fields and nothing else — timing and rollups belong to §7 alone, and a create that seeded them would
     * put an arrival's opinion where only the worker's replacement is allowed to be.
     */
    public static TraceV2Row of(
            String projectId,
            String id,
            @Nullable String sessionId,
            @Nullable String threadId,
            @Nullable String name,
            @Nullable String userId,
            @Nullable String projectVersionId,
            String startedAt,
            String eventTs) {
        return new TraceV2Row(
                projectId,
                id,
                sessionId,
                null,
                threadId,
                name,
                userId,
                projectVersionId,
                null,
                startedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                eventTs,
                false);
    }
}
