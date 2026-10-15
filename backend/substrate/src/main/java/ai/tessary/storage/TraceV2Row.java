// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 trace row: one turn. Every rollup is written by the worker as a replacement, never incremented by an arrival.
 * Null is not zero: an unrolled trace carries null counters. {@code isSettled} means "nothing arrived since the last
 * rollup". @param parentTraceId set only for a sub-agent that outlived the turn @param unpricedSpans load-bearing:
 * stops a total over unrated models reading as a cheap turn
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
     * The only constructor ingest should use: get-or-create writes identity only, since timing and rollups belong to
     * the worker.
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
