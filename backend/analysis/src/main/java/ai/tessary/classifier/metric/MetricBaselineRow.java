// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * The rolling control catches sudden breaks; the deploy-pinned window catches slow creep. @param measure persisted,
 * never renamed @param pinnedSketchJson only a human ruling moves it; an automatic re-pin would let the next window
 * normalize a real regression @param pinnedWorkloadJson persisted because the traffic behind the pin may be weeks gone
 * @param currentOpenedAt event time: a backfill lands a corpus in one burst, so cutting on ingest time would compare a
 * month against nothing
 */
public record MetricBaselineRow(
        String id,
        String projectId,
        String classifierId,
        String measure,
        String bucketKind,
        String bucketKey,
        String state,
        @Nullable String pinnedSketchJson,
        @Nullable String pinnedAt,
        @Nullable String pinnedByVersionId,
        @Nullable String currentSketchJson,
        @Nullable String pinnedWorkloadJson,
        @Nullable String currentWorkloadJson,
        @Nullable String pinnedTokensJson,
        /**
         * A sketch cannot be un-summarized, so the rows are kept at pin time. Rotates with {@link #pinnedSketchJson} so
         * the two always describe the same window.
         */
        @Nullable String pinnedRefsJson,
        @Nullable String currentTokensJson,
        /**
         * Persisted, not per page: otherwise a multi-page window enumerates only its last page, and
         * {@code finding_evidence}'s {@code member} role forbids presenting a sample as the population. Bounded by
         * {@code window_target_count}.
         */
        @Nullable String currentRefsJson,
        /**
         * Weighting and regression-day exclusion are applied on read, so a late Layer-2 verdict can retroactively drop
         * a day.
         */
        @Nullable String controlJson,
        @Nullable String currentOpenedAt,
        long currentCount,
        /**
         * The ingest clock, not the event clock. Lives on this row rather than the job row, which is cleared
         * independently. Not reset on close, or the closed window's tail would be re-admitted.
         */
        @Nullable String countedThroughAt,
        @Nullable String countedThroughId,
        /** {@code updatedAt} can't answer this: a periodic pass writes it whether or not anything arrived. */
        @Nullable String lastEventAt,
        String createdAt,
        String updatedAt) {

    /**
     * A bucket below the minimum stays {@link #LEARNING} and keeps accumulating, so a rarely called tool is watched
     * slowly rather than not at all.
     */
    public static final class State {
        private State() {}

        public static final String LEARNING = "learning";
        public static final String ARMED = "armed";
    }

    /** Persisted; never renamed. */
    public static final class BucketKind {
        private BucketKind() {}

        public static final String CALL_SITE = "call_site";

        public static final String TOOL = "tool";
    }

    /**
     * Persisted; never renamed. The four token buckets are evidence on the cost finding and never open findings of
     * their own, or one cache-killing edit would file five.
     */
    public static final class Measure {
        private Measure() {}

        public static final String TURN_DURATION = "turn_duration";
        public static final String TOOL_DURATION = "tool_duration";
        public static final String COST = "cost";
        public static final String TOK_INPUT = "tok_input";
        public static final String TOK_OUTPUT = "tok_output";
        public static final String TOK_CACHE_READ = "tok_cache_read";

        /**
         * Abstains rather than recording zero where cache creation is not billed, decided by the model's own rate since
         * the convention differs within a vendor.
         */
        public static final String TOK_CACHE_WRITE = "tok_cache_write";
    }
}
