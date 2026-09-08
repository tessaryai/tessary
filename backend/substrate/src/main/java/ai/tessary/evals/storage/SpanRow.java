// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 {@code span} row — one step: an LLM call, a tool call, a sub-agent (substrate-model.md §3).
 *
 * <p>Identity is {@code (projectId, traceId, id)}, all three the producer's, and that triple is the
 * primary key, the natural key and the upsert conflict target at once. There is no bare-id lookup
 * anywhere in v2: a span pointer that names a span without its trace cannot be resolved.
 *
 * <p><b>Correlation handles are denormalized onto every row</b> ({@code sessionId}, {@code userId},
 * {@code projectVersionId}, {@code traceName}) so no read has to join upward, and
 * <b>usage and cost are typed columns</b> so no read parses JSON per row. The producer's raw usage object
 * survives as a receipt in {@code span_payload.providedUsage} and is never read for arithmetic.
 *
 * <h2>The three fields that are not the producer's</h2>
 *
 * <ul>
 *   <li>{@code path} — materialized ancestry, resolved by the path fixpoint. Null means UNRESOLVED, never
 *       "root": root is {@code parentSpanId == null}, the producer's own statement. The upsert never
 *       overwrites it, because a newer version replaces what the producer said, not what we derived.
 *   <li>{@code correlationState} / {@code pathState} — the resolvers' terminal markers. They are what keep
 *       the two partial indexes near-empty: without them, anonymous traffic (session-less forever) and a
 *       child of a parent the producer never shipped would sit in those indexes permanently and eventually
 *       crowd genuine work out of every LIMITed micro-batch.
 * </ul>
 *
 * <p>{@code depth}, {@code totalTokens} and {@code totalCost} are generated columns: read-only, and
 * <b>null whenever every contributing bucket is null</b>. That is the invariant the whole cost surface
 * rests on — a zero there would launder "the producer sent no usage" into "this call was free".
 *
 * @param costSource the only correct way to read a null cost: {@code provided} (the producer sent it),
 *     {@code inferred} (we priced it, under {@code priceBookVersion}), {@code unpriced} (we hold no rate).
 * @param errorType the CLASS of failure — the producer's own {@code error.type} attribute, or a capped
 *     signature of its status message when it ships none. A short label, because every facet, breakdown
 *     and group-by in the product treats this column as a type. It used to hold the status message
 *     itself, which is how a facet key came to be 3,271 characters of agent markdown (#762).
 * @param errorMessage the PROSE — the producer's status message, capped at write. Nothing renders it
 *     today; it is here so that switching {@code errorType} to a class does not throw the description of
 *     the failure away, and so a reader is not sent to {@code span_payload} for it.
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
        // Generated / defaulted columns: populated on read, ignored on write.
        @Nullable Integer depth,
        @Nullable Long totalTokens,
        @Nullable String totalCost,
        @Nullable String createdAt) {

    /** {@code cost_source} values — the vocabulary {@code ck_span_cost_source} enforces. */
    public static final class CostSource {
        public static final String PROVIDED = "provided";
        public static final String INFERRED = "inferred";
        public static final String UNPRICED = "unpriced";

        private CostSource() {}
    }

    /** {@code correlation_state} / {@code path_state} values (implementation plan §2.6). */
    public static final class ResolverState {
        /** Still in the resolver's work queue. */
        public static final String PENDING = "pending";
        /** Correlation copied down from the trace row. */
        public static final String DONE = "done";
        /** The trace settled with a null session id — anonymous traffic, nothing to copy, ever. */
        public static final String NONE = "none";
        /** Ancestry materialized. */
        public static final String RESOLVED = "resolved";
        /** The trace settled with the parent row still absent — the producer never shipped it. */
        public static final String ORPHAN = "orphan";

        private ResolverState() {}
    }

    /**
     * The minimal ingest shape: identity, kind, timing and versioning, with no usage, no cost and no
     * resolved ancestry. {@code costSource} defaults to {@code unpriced}, which is the honest state for a
     * span nothing has priced — never a zero cost.
     */
    public static SpanRow of(
            String projectId,
            String traceId,
            String id,
            @Nullable String parentSpanId,
            String kind,
            @Nullable String name,
            String startedAt,
            @Nullable String endedAt,
            String eventTs) {
        return new SpanRow(
                projectId,
                traceId,
                id,
                parentSpanId,
                null,
                null,
                null,
                null,
                null,
                null,
                kind,
                name,
                parentSpanId == null,
                null,
                null,
                null,
                null,
                startedAt,
                endedAt,
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
                CostSource.UNPRICED,
                null,
                null,
                null,
                ResolverState.PENDING,
                ResolverState.PENDING,
                eventTs,
                false,
                null,
                null,
                null,
                null);
    }
}
