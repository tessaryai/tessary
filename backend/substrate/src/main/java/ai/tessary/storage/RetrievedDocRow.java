// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A first-class {@code retrieved_doc} row — one retrieved passage hung off the RETRIEVAL/RERANKER span
 * that produced it (a RAG step). Append-only and project-scoped.
 *
 * <p>Carries a {@code listRole} (candidate|result), 1-based {@code rank}, {@code title},
 * {@code sourceUri} and {@code dataSourceId}; {@code metadata} is jsonb and {@code created_at}
 * timestamptz. {@code seq} is the 0-based order within the retrieval result.
 *
 * <p><b>The two media ref columns are gone (0001)</b>, for the reason {@link ToolCallRow} gives: declared
 * with FKs into {@code media_object}, NULL on every row ever written, and replaced by {@code media_ref}.
 *
 * <p><b>One key namespace</b>, exactly as {@link ToolCallRow}: {@code traceId}/{@code spanId} are the
 * producer keys every read joins on, and the surrogate pair they replaced was dropped with the v1
 * substrate in 0083.
 *
 * @param seq 0-based order within the retrieval result — part of the row's derived identity.
 * @param docId the source document/chunk id, when the retriever supplies one; nullable.
 * @param content the retrieved passage text, preserved whole (never truncated).
 * @param score the relevance/similarity score the retriever assigned; nullable.
 */
public record RetrievedDocRow(
        String id,
        String projectId,
        @Nullable Integer seq,
        @Nullable String listRole,
        @Nullable Integer rank,
        @Nullable String docId,
        @Nullable String title,
        @Nullable String content,
        @Nullable Double score,
        @Nullable String sourceUri,
        @Nullable String dataSourceId,
        @Nullable String metadata,
        @Nullable String sourceExternalId,
        @Nullable String eventTs,
        @Nullable Boolean isDeleted,
        String createdAt,
        @Nullable String traceId,
        @Nullable String spanId) {

    /**
     * The ingest shape: a retrieved {@code result} (or {@code candidate}) keyed on the producer's own
     * {@code (project, trace, span)}, with 1-based {@code rank} and {@code sourceUri}/
     * {@code dataSourceId} off OpenInference. {@code id} is derived from the producer keys plus
     * {@code seq}, so a redelivery collides on the primary key.
     */
    public static RetrievedDocRow retrieved(
            String id,
            String projectId,
            String traceId,
            String spanId,
            @Nullable String listRole,
            int seq,
            @Nullable String docId,
            @Nullable String content,
            @Nullable Double score,
            @Nullable String sourceUri,
            @Nullable String dataSourceId,
            @Nullable String metadata,
            @Nullable String eventTs,
            String createdAt) {
        return new RetrievedDocRow(
                id,
                projectId,
                seq,
                listRole,
                seq + 1,
                docId,
                null,
                content,
                score,
                sourceUri,
                dataSourceId,
                metadata,
                spanId,
                eventTs,
                false,
                createdAt,
                traceId,
                spanId);
    }

    /** The canonical {@code retrieved_doc.list_role} values. */
    public static final class ListRole {
        private ListRole() {}

        public static final String CANDIDATE = "candidate";
        public static final String RESULT = "result";
    }
}
