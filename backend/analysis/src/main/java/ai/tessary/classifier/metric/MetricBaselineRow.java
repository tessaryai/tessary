// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * One bucket's window state for one measure of a metric-drift classifier. Design contract:
 * {@code classifiers/metric_drift/PROGRAM.md} — schema in §10, lifecycle in §5.
 *
 * <p>The shape is deliberately close to {@code BehaviorProfileRow} and deliberately not the same row.
 * Both carry a state machine, a version the baseline is pinned to and a counted-through watermark;
 * their payloads have nothing in common. Behaviour drift's fitted state is an n-gram alphabet plus a
 * surprisal reservoir, this one's is a numeric sketch over {@code log(value)}, and sharing one blob
 * column between two writers is the lost update {@code fit_carry_json} was split out to avoid.
 *
 * <p>Two references are compared on every close rather than one, because a single one is provably
 * insufficient in one direction each: the rolling control ({@link #controlJson}) catches sudden breaks
 * but never notices a slow boil (a change spread over a fortnight is simply absorbed into a fortnight of
 * memory), while the window pinned after the last deploy catches cumulative creep but screams forever
 * once something legitimately changed. {@code current} is what they are compared against.
 *
 * @param measure a PERSISTED string — {@code turn_duration}, {@code cost}, {@code tok_cache_read} and
 *     the rest. Same standing rule as the signal keys: wire and Java say <em>classifier</em>, the
 *     database and its persisted strings stay <em>signal</em>, and neither of these names is ever
 *     renamed once written.
 * @param bucketKey opaque here: a call-site id when {@code bucketKind} is {@link BucketKind#CALL_SITE},
 *     an {@code ActionSymbol} {@code kind:name} when it is {@link BucketKind#TOOL}.
 * @param pinnedSketchJson the reference pinned after the last deploy. Only a human pressing
 *     <em>Legitimate — absorb</em> moves it: {@code BehaviorTriageVerdict} states that Layer-2's
 *     confidence is read "never as authority to mutate the baseline", and an automatic re-pin would let
 *     the next window silently normalize a real regression.
 * @param currentSketchJson the window being filled; folded into {@link #controlJson} on close.
 * @param pinnedWorkloadJson what the USER asked for over the same window {@code pinnedSketchJson}
 *     summarizes — prompt size, message length, thread depth
 *     ({@link ai.tessary.classifier.metric.MetricWorkload}). The workload blobs rotate and
 *     re-pin in lockstep with the sketches beside them, and they are persisted rather than
 *     recomputed because a reference window is HISTORY: by the time a finding is written the traffic the
 *     pinned sketch summarizes may be weeks past, and no query can re-derive what the input looked like
 *     then. A finding that could only report today's workload could not make the flat-inputs-versus-moved-
 *     outputs argument at all, which is the argument PROGRAM.md §7 exists for.
 * @param pinnedTokensJson what the same window's dollars were MADE of — the four token buckets and the
 *     cache-read share ({@link ai.tessary.classifier.metric.MetricTokens}). A third blob per slot
 *     rather than more quantities in the workload one, because the workload block is the ask and output
 *     tokens are the agent's answer; see that class. Null on every duration baseline, which folds no
 *     decomposition, and on a cost window whose traffic reported no usage.
 * @param currentOpenedAt EVENT time of the first sample in the current window, not the wall clock at
 *     which the sweep opened it. Windows are cut on {@code COALESCE(started_at, created_at)} because a
 *     backfill lands a whole corpus in one ingest burst, and a time-cut window on ingest time would
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
         * ({@link MetricControl}). It replaced a single previously-closed window as the short-horizon
         * reference — one arbitrary closed window was as noisy as the window it judged, took whatever
         * shape the clock gave it, and forgot a step change within one window because the next
         * window's previous IS the new level.
         *
         * <p>The ring is an EXACT record of what closed when. Both the weighting and the exclusion of
         * days a confirmed regression ran through are applied when it is read, which is what lets a
         * Layer-2 verdict that lands hours after a window closed retroactively drop that day.
         */
        @Nullable String controlJson,
        @Nullable String currentOpenedAt,
        long currentCount,
        /**
         * The {@code (created_at, id)} keyset of the last sample folded into {@link #currentCount} — the
         * INGEST clock, which is the monotonic gap-free one, and deliberately not the event clock the
         * window is cut on.
         *
         * <p>It lives on this row so it shares the counters' lifetime. The sweep's cursor lives on the
         * job row, which does not: clearing a stuck queue restarts the sweep from a null cursor and
         * re-folds samples the baseline already holds. Behaviour drift paid for this lesson in
         * production, reporting {@code trace_count} 565 for a project holding 443 distinct traces.
         *
         * <p>It does NOT reset when a window closes. {@code currentCount} is per window; this is per
         * row, and resetting it on close would re-admit the tail of the window just closed into the
         * window just opened.
         */
        @Nullable String countedThroughAt,
        @Nullable String countedThroughId,
        /**
         * When a sample was last OBSERVED for this bucket, in event time. {@code updatedAt} cannot
         * answer that — a periodic pass writes it whether or not anything arrived — and a thin bucket
         * closes its window on elapsed event time rather than on count, so this is the value that
         * criterion reads.
         */
        @Nullable String lastEventAt,
        String createdAt,
        String updatedAt) {

    /**
     * {@code metric_baseline.state}. Only {@link #ARMED} compares and emits findings; a bucket below the
     * minimum sample stays {@link #LEARNING} and keeps accumulating rather than being skipped, so a tool
     * called thirty times a week is watched on a slower clock instead of not at all (PROGRAM.md §2.3).
     */
    public static final class State {
        private State() {}

        public static final String LEARNING = "learning";
        public static final String ARMED = "armed";

        /**
         * The sketch no longer describes the bucket — its configured range stopped fitting the traffic,
         * so the overflow bin holds the population and a comparison would be arithmetic on clipping.
         * Idleness is NOT a reason to be here, exactly as for behaviour drift: a baseline does not rot
         * because nobody called the agent.
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
     * {@code metric_baseline.measure} — the seven measures the two classifiers govern between them.
     * Persisted strings; never renamed. {@code duration_drift} owns {@link #TURN_DURATION} and
     * {@link #TOOL_DURATION}; {@code cost_drift} owns {@link #COST} plus the four token buckets, which
     * are computed every window as evidence attached to the cost finding and never open findings of
     * their own — otherwise one prompt edit that kills caching writes five rows for one change.
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
         * Abstains rather than recording zero wherever cache creation is not a billed — and so not a
         * counted — quantity. The model's own rate settles that
         * ({@link ai.tessary.vitals.TokenPriceBook#billsCacheCreation}) rather than a provider
         * family, because the convention now differs inside one vendor: {@code gpt-5.6} bills cache
         * creation and {@code gpt-4o}'s automatic caching does not, while Gemini bills storage per hour
         * on every model. A zero here would mean "no writes" when the truth is "not reported".
         */
        public static final String TOK_CACHE_WRITE = "tok_cache_write";
    }
}
