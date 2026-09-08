// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import org.jspecify.annotations.Nullable;

/**
 * A first-class {@code tool_call} row — a tool invocation hung off the span that ran it.
 * Append-only and project-scoped.
 *
 * <p>Carries the provider {@code toolCallId} + {@code toolType} + {@code scope} (client|server) + an
 * {@code isError} flag; {@code arguments}/{@code result}/{@code attributes} are jsonb (with
 * {@code argumentsRaw} keeping the verbatim, possibly non-JSON args); timestamps are timestamptz.
 * {@code errorType}/{@code isError} are what the tool-error signal keys off.
 *
 * <p><b>The two media ref columns are gone (0001).</b> {@code argumentsRef}/{@code resultRef} were
 * declared with FKs into {@code media_object} and were NULL on every row ever written — this record
 * hardcoded null into both — so the schema looked like it knew who referenced an image while nothing
 * did. {@code media_ref} is that answer now (#761).
 *
 * <p><b>One key namespace, as of the teardown (0083).</b> {@code traceId}/{@code spanId} are the
 * producer's own keys — the pair every read joins on and the pair the row's primary key is derived from.
 * The row used to carry a second, surrogate pair ({@code observation_id} and a surrogate
 * {@code trace_id}) from the v1 enricher; those columns were dropped with the tables they pointed at,
 * and the producer pair took their names.
 *
 * @param retries retries before this terminal outcome ({@code 0} = first-try success); {@code null}
 *     when the source does not report it.
 * @param latencyMs wall-clock duration of the call in milliseconds; {@code null} when not measured.
 * @param sourceExternalId the provider span id that ran the tool.
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
     * The ingest shape: a tool call keyed on the producer's own {@code (project, trace, span)}.
     * {@code id} is expected to be derived from those same keys (see {@code SideTableIds}), so an
     * at-least-once redelivery collides on the primary key instead of minting a second row — the
     * surrogate natural key that used to give that property went with the observation id.
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
