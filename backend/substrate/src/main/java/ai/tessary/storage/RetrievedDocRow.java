// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A {@code retrieved_doc} row hung off a retrieval or reranker span; append-only. Keys as {@link ToolCallRow}. @param
 * seq part of the row's derived identity @param content preserved whole, never truncated
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

    /** {@code id} derives from the producer keys plus {@code seq}, so a redelivery collides on the primary key. */
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

    /** The schema also allows {@code 'candidate'}, which the grounding read filters on. */
    public static final class ListRole {
        private ListRole() {}

        public static final String RESULT = "result";
    }
}
