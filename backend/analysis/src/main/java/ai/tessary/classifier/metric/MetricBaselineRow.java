// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * One bucket's window state for one measure of a metric-drift classifier.
 *
 * <p>The shape is deliberately close to {@code BehaviorProfileRow} and deliberately not the same row:
 * both carry a state machine, a version the baseline is pinned to, and a counted-through watermark, but
 * their payloads have nothing in common. Behaviour drift's fitted state is an n-gram alphabet plus a
 * surprisal reservoir; this one's is a numeric sketch over {@code log(value)}.
 *
 * <p>Two references are compared on every close: the rolling control ({@link #controlJson}) catches
 * sudden breaks but never notices a slow boil, while the window pinned after the last deploy catches
 * cumulative creep but screams forever once something legitimately changed. {@code current} is what
 * they are compared against.
 *
 * @param measure a persisted string ({@code turn_duration}, {@code cost}, {@code tok_cache_read}, etc.),
 *     never renamed once written.
 * @param bucketKey opaque here: a call-site id when {@code bucketKind} is {@link BucketKind#CALL_SITE},
 *     an {@code ActionSymbol} {@code kind:name} when it is {@link BucketKind#TOOL}.
 * @param pinnedSketchJson the reference pinned after the last deploy. Only a human ruling a shift
 *     legitimate moves it; Layer 2's confidence is never read as authority to mutate the baseline, since
 *     an automatic re-pin would let the next window silently normalize a real regression.
 * @param currentSketchJson the window being filled; folded into {@link #controlJson} on close.
 * @param pinnedWorkloadJson what the user asked for over the same window {@code pinnedSketchJson}
 *     summarizes: prompt size, message length, thread depth
 *     ({@link ai.tessary.classifier.metric.MetricWorkload}). Persisted rather than recomputed, since by
 *     the time a finding is written the traffic behind the pinned sketch may be weeks past and cannot be
 *     re-derived.
 * @param pinnedTokensJson what the same window's dollars were made of: the four token buckets and the
 *     cache-read share ({@link ai.tessary.classifier.metric.MetricTokens}). Null on every duration
 *     baseline, and on a cost window whose traffic reported no usage.
 * @param currentOpenedAt event time of the first sample in the current window, not the wall clock at
 *     which the sweep opened it. Windows are cut on {@code COALESCE(started_at, created_at)}, since a
 *     backfill lands a whole corpus in one ingest burst and a time-cut window on ingest time would
 *     swallow a month of traffic and compare it against nothing.
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
         * The substrate rows the pinned window was fitted over, as {@link MetricEvidenceRefs} writes
         * them. A sketch cannot be un-summarized, so a finding fired against this reference has nothing
         * to enumerate on the baseline side unless the rows were kept when the window was pinned; this
         * is where they are kept, and it rotates with {@link #pinnedSketchJson} so the refs can never
         * describe a different window than the sketch does.
         */
        @Nullable String pinnedRefsJson,
        @Nullable String prevTokensJson,
        @Nullable String currentTokensJson,
        /**
         * The rolling control: this bucket's recent normal, as a ring of per-UTC-day merges
         * ({@link MetricControl}). An exact record of what closed when; the weighting and the exclusion
         * of days a confirmed regression ran through are applied when it's read, which is what lets a
         * Layer-2 verdict that lands hours after a window closed retroactively drop that day.
         */
        @Nullable String controlJson,
        @Nullable String currentOpenedAt,
        long currentCount,
        /**
         * The {@code (created_at, id)} keyset of the last sample folded into {@link #currentCount}: the
         * ingest clock, monotonic and gap-free, deliberately not the event clock the window is cut on.
         *
         * <p>Lives on this row so it shares the counters' lifetime rather than the sweep's job row, which
         * gets cleared and restarted independently; a cursor there would re-fold samples this baseline
         * already holds.
         *
         * <p>Does not reset when a window closes: {@code currentCount} is per window, this is per row,
         * and resetting it on close would re-admit the tail of the window just closed into the one just
         * opened.
         */
        @Nullable String countedThroughAt,
        @Nullable String countedThroughId,
        /**
         * When a sample was last observed for this bucket, in event time. {@code updatedAt} can't answer
         * that, since a periodic pass writes it whether or not anything arrived, and a thin bucket closes
         * its window on elapsed event time rather than on count.
         */
        @Nullable String lastEventAt,
        String createdAt,
        String updatedAt) {

    /**
     * {@code metric_baseline.state}. Only {@link #ARMED} compares and emits findings; a bucket below the
     * minimum sample stays {@link #LEARNING} and keeps accumulating rather than being skipped, so a tool
     * called thirty times a week is watched on a slower clock instead of not at all.
     */
    public static final class State {
        private State() {}

        public static final String LEARNING = "learning";
        public static final String ARMED = "armed";

        /**
         * The sketch no longer describes the bucket: its configured range stopped fitting the traffic,
         * so the overflow bin holds the population and a comparison would be arithmetic on clipping.
         * Idleness is not a reason to be here; a baseline does not rot because nobody called the agent.
         */
        public static final String STALE = "stale";
    }

    /** {@code metric_baseline.bucket_kind}. Persisted; never renamed. */
    public static final class BucketKind {
        private BucketKind() {}

        /** The entry-point call site, resolved root-span-first exactly as behaviour drift resolves it. */
        public static final String CALL_SITE = "call_site";

        /** An {@code ActionSymbol} {@code kind:normalized-name}, so tool buckets match drift's alphabet. */
        public static final String TOOL = "tool";
    }

    /**
     * {@code metric_baseline.measure}, the seven measures the two classifiers govern between them.
     * Persisted strings; never renamed. {@code duration_drift} owns {@link #TURN_DURATION} and
     * {@link #TOOL_DURATION}; {@code cost_drift} owns {@link #COST} plus the four token buckets, which
     * are computed every window as evidence attached to the cost finding and never open findings of
     * their own: otherwise one prompt edit that kills caching writes five rows for one change.
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
         * Abstains rather than recording zero wherever cache creation is not a billed, and so not a
         * counted, quantity. The model's own rate settles that
         * ({@link ai.tessary.vitals.TokenPriceBook#billsCacheCreation}) rather than a provider family,
         * since the convention differs inside one vendor: {@code gpt-5.6} bills cache creation and
         * {@code gpt-4o}'s automatic caching does not, while Gemini bills storage per hour on every model.
         * A zero here would mean "no writes" when the truth is "not reported".
         */
        public static final String TOK_CACHE_WRITE = "tok_cache_write";
    }
}
