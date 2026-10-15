// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A {@code tool_call} row hung off the span that ran it; append-only. {@code argumentsRaw} keeps verbatim, possibly
 * non-JSON args. {@code traceId}/{@code spanId} are the producer's keys every read joins on. @param retries null when
 * the source does not report it @param latencyMs null when not measured @param sourceExternalId the provider span id
 * that ran the tool
 */
public record ToolCallRow(
        String id,
        String projectId,
        @Nullable String name,
        @Nullable String toolCallId,
        @Nullable String toolType,
        @Nullable String scope,
        @Nullable String arguments,
        @Nullable String argumentsRaw,
        @Nullable String result,
        @Nullable String errorType,
        @Nullable String errorMessage,
        @Nullable Boolean isError,
        @Nullable Integer retries,
        @Nullable Long latencyMs,
        @Nullable String sourceExternalId,
        @Nullable String eventTs,
        @Nullable Boolean isDeleted,
        @Nullable String attributes,
        @Nullable String startedAt,
        String createdAt,
        @Nullable String traceId,
        @Nullable String spanId) {

    /**
     * {@code id} is derived from the producer keys (see {@code SideTableIds}), so an at-least-once redelivery collides
     * on the primary key.
     */
    public static ToolCallRow forSpan(
            String id,
            String projectId,
            String traceId,
            String spanId,
            @Nullable String name,
            @Nullable String toolCallId,
            @Nullable String toolType,
            @Nullable String arguments,
            @Nullable String argumentsRaw,
            @Nullable String result,
            @Nullable String errorType,
            @Nullable String errorMessage,
            @Nullable Long latencyMs,
            @Nullable String eventTs,
            @Nullable String startedAt,
            String createdAt) {
        return new ToolCallRow(
                id,
                projectId,
                name,
                toolCallId,
                toolType,
                null,
                arguments,
                argumentsRaw,
                result,
                errorType,
                errorMessage,
                errorType != null,
                null,
                latencyMs,
                spanId,
                eventTs,
                false,
                null,
                startedAt,
                createdAt,
                traceId,
                spanId);
    }
}
