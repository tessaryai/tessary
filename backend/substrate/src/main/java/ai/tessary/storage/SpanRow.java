// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 {@code span} row. Identity is {@code (projectId, traceId, id)}; a bare id cannot be resolved. {@code path} null
 * means UNRESOLVED, never root, and the upsert never overwrites it. {@code correlationState}/{@code pathState} keep the
 * resolver partial indexes near-empty. Generated totals are null whenever every input is null: zero would launder "no
 * usage sent" into "free". @param costSource {@code provided}, {@code inferred} (under {@code priceBookVersion}) or
 * {@code unpriced} @param errorType a short class label (facets group by it); the prose is {@code errorMessage}
 * @param promptName the managed prompt that produced this span, from tessary.prompt.name or a third-party prompt dialect; no FK
 */
public record SpanRow(
        String projectId,
        String traceId,
        String id,
        @Nullable String parentSpanId,
        @Nullable String path,
        @Nullable String sessionId,
        @Nullable String userId,
        @Nullable String projectVersionId,
        @Nullable String callSiteId,
        @Nullable String traceName,
        String kind,
        @Nullable String name,
        boolean isLogicalRoot,
        @Nullable String status,
        @Nullable String level,
        @Nullable String errorType,
        @Nullable String errorMessage,
        String startedAt,
        @Nullable String endedAt,
        @Nullable Long latencyMs,
        @Nullable Long ttftMs,
        @Nullable String providedModelName,
        @Nullable String modelId,
        @Nullable Long inputTokens,
        @Nullable Long outputTokens,
        @Nullable Long cacheReadTokens,
        @Nullable Long cacheWriteTokens,
        @Nullable Long reasoningTokens,
        @Nullable String inputCost,
        @Nullable String outputCost,
        @Nullable String cacheReadCost,
        @Nullable String cacheWriteCost,
        String costSource,
        @Nullable String priceBookVersion,
        @Nullable String inputPreview,
        @Nullable String outputPreview,
        String correlationState,
        String pathState,
        String eventTs,
        boolean isDeleted,
        @Nullable Integer depth,
        @Nullable Long totalTokens,
        @Nullable String totalCost,
        @Nullable String createdAt,
        @Nullable String promptName,
        @Nullable String promptVersion) {

    public static final class CostSource {
        public static final String PROVIDED = "provided";
        public static final String INFERRED = "inferred";
        public static final String UNPRICED = "unpriced";

        private CostSource() {}
    }

    public static final class ResolverState {
        public static final String PENDING = "pending";
        public static final String DONE = "done";
        /** Anonymous traffic: nothing to copy, ever. */
        public static final String NONE = "none";

        public static final String RESOLVED = "resolved";

        private ResolverState() {}
    }
}
