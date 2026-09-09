// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for {@code metric_baseline} — the per-(bucket × measure) window state of the
 * metric-drift classifiers. Design contract: {@code classifiers/metric_drift/PROGRAM.md}.
 *
 * <p>Mirrors {@code BehaviorProfileRepository}'s shape on purpose: one row carries everything a sweep
 * needs, the counters and the watermark that guards them move in a single statement, and the writers
 * are separated by method rather than by trusting callers to touch only their own columns. The
 * separation matters more here than there, because three writers touch this row on different clocks —
 * the sweep folds samples in, the window close rotates the sketches, and a human pressing
 * <em>Legitimate — absorb</em> re-pins the reference.
 *
 * <p>{@code ux_metric_baseline_scope} keeps at most one baseline per scope. It carried a sixth column,
 * {@code COALESCE(environment_id, '')}, until the Environment concept was removed; that migration
 * deletes the rows that would collide under the collapsed key and recreates the index without it. The
 * COALESCE was load-bearing while the column existed — NULL is distinct from itself in a unique index —
 * and the shape is worth remembering if a scope column is ever added back.
 */
@Repository
public class MetricBaselineRepository {

    private static final String COLS = "id, project_id, classifier_id, measure, bucket_kind, bucket_key, "
            + "state, pinned_sketch_json, pinned_at, pinned_by_version_id, "
            + "current_sketch_json, pinned_workload_json, current_workload_json, "
            + "pinned_tokens_json, pinned_refs_json, prev_tokens_json, current_tokens_json, "
            + "control_json, current_opened_at, current_count, "
            + "counted_through_at, counted_through_id, last_event_at, created_at, updated_at";

    /**
     * The unique index's expression, restated exactly once. Postgres infers an arbiter index from the
     * expression text of the {@code ON CONFLICT} target, so this and
     * {@code ux_metric_baseline_scope} must agree character for character; a paraphrase compiles and
     * then fails at runtime with "no unique or exclusion constraint matching".
     */
    private static final String SCOPE_KEY = "project_id, classifier_id, measure, bucket_kind, bucket_key";

    private final JdbcClient jdbc;

    public MetricBaselineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Get the baseline for a scope, creating it from {@code seed} if this is the first sample the bucket
     * has ever produced, and return whichever row is now live.
     *
     * <p>The sweep discovers buckets from traffic rather than from a registry — a new tool appears the
     * first time an agent calls it — so "insert or read" is the only shape a caller ever needs, and
     * splitting it into a read followed by an insert would race two concurrent sweep passes into a
     * duplicate-key failure on a perfectly ordinary event.
     *
     * <p>{@code DO UPDATE} rather than {@code DO NOTHING} even though it writes nothing new:
     * {@code DO NOTHING} suppresses the {@code RETURNING} clause on the conflicting row, so the common
     * path — the bucket already exists — would come back empty and every caller would need a second
     * round trip to read what it just asked for.
     */
    public MetricBaselineRow ensure(MetricBaselineRow seed) {
        return jdbc.sql("INSERT INTO metric_baseline (" + COLS + ") VALUES (:id, :pid, :sid, :measure, "
                        + ":bucketKind, :bucketKey, :state, :pinnedSketch, :pinnedAt, :pinnedVersion, "
                        + ":currentSketch, :pinnedWorkload, :currentWorkload, "
                        + ":pinnedTokens, :pinnedRefs, :prevTokens, :currentTokens, :control, "
                        + ":currentOpenedAt, :currentCount, :countedThroughAt, "
                        + ":countedThroughId, :lastEventAt, :createdAt, :updatedAt) "
                        + "ON CONFLICT (" + SCOPE_KEY + ") DO UPDATE "
                        + "SET updated_at = metric_baseline.updated_at "
                        + "RETURNING " + COLS)
                .param("id", seed.id())
                .param("pid", seed.projectId())
                .param("sid", seed.classifierId())
                .param("measure", seed.measure())
                .param("bucketKind", seed.bucketKind())
                .param("bucketKey", seed.bucketKey())
                .param("state", seed.state())
                .param("pinnedSketch", seed.pinnedSketchJson())
                .param("pinnedAt", seed.pinnedAt())
                .param("pinnedVersion", seed.pinnedByVersionId())
                .param("currentSketch", seed.currentSketchJson())
                .param("pinnedWorkload", seed.pinnedWorkloadJson())
                .param("currentWorkload", seed.currentWorkloadJson())
                .param("pinnedTokens", seed.pinnedTokensJson())
                .param("pinnedRefs", seed.pinnedRefsJson())
                .param("prevTokens", seed.prevTokensJson())
                .param("currentTokens", seed.currentTokensJson())
                .param("control", seed.controlJson())
                .param("currentOpenedAt", seed.currentOpenedAt())
                .param("currentCount", seed.currentCount())
                .param("countedThroughAt", seed.countedThroughAt())
                .param("countedThroughId", seed.countedThroughId())
                .param("lastEventAt", seed.lastEventAt())
                .param("createdAt", seed.createdAt())
                .param("updatedAt", seed.updatedAt())
                .query((rs, n) -> map(rs))
                .single();
    }

    /** The per-scope lookup, matching {@code ux_metric_baseline_scope} exactly. */
    public Optional<MetricBaselineRow> find(
            String projectId, String classifierId, String measure, String bucketKind, String bucketKey) {
        return jdbc.sql("SELECT " + COLS + " FROM metric_baseline "
                        + "WHERE project_id = :pid AND classifier_id = :sid AND measure = :measure "
                        + "AND bucket_kind = :bucketKind AND bucket_key = :bucketKey")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("measure", measure)
                .param("bucketKind", bucketKind)
                .param("bucketKey", bucketKey)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<MetricBaselineRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM metric_baseline WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Every baseline belonging to one classifier, thickest current window first — the sweep's per-pass
     * read. Ordering by {@code current_count} puts the buckets that can actually close a window this
     * pass ahead of the thin ones that cannot, which matters because thin buckets wait rather than being
     * skipped and would otherwise sit at the front of every page forever.
     */
    public List<MetricBaselineRow> listByClassifier(String projectId, String classifierId) {
        return jdbc.sql("SELECT " + COLS + " FROM metric_baseline "
                        + "WHERE project_id = :pid AND classifier_id = :sid "
                        + "ORDER BY current_count DESC, bucket_key ASC")
                .param("pid", projectId)
                .param("sid", classifierId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Fold a batch of samples into the current window: bump the counter, advance the ingest watermark
     * that guards it, and widen the event-time bookkeeping.
     *
     * <p>Counter and watermark move in ONE statement, so the two can never disagree about what has been
     * folded in. The caller is still expected to have filtered its candidates against
     * {@code counted_through_*} before summing them — this method advances the guard, it does not
     * enforce it — but keeping them in one write means an interrupted sweep leaves a consistent row
     * rather than a count without the watermark that makes it re-runnable.
     *
     * <p>Deliberately does NOT touch {@code current_sketch_json}: the sketch is written by
     * {@link #updateCurrentSketch} after the caller has merged the batch into it, and a
     * read-modify-write from here would drop whatever a concurrent close had rotated.
     *
     * @param windowOpenedAt event time of the batch's earliest sample; only takes effect on the first
     *     batch of a window, since a window's open time is the first sample in it.
     * @param lastEventAt event time of the batch's latest sample.
     */
    public void advanceWindow(
            String id,
            long countDelta,
            String now,
            @Nullable String windowOpenedAt,
            @Nullable String lastEventAt,
            @Nullable String countedThroughAt,
            @Nullable String countedThroughId) {
        jdbc.sql("""
            UPDATE metric_baseline
               SET current_count = current_count + :delta,
                   -- The window's open time is the event time of its FIRST sample, so this is set once
                   -- per window and then left alone until the close resets it.
                   current_opened_at = COALESCE(current_opened_at, CAST(:windowOpenedAt AS text)),
                   -- Compared as TIMESTAMPS, never as text. These columns are text and Instant.toString()
                   -- elides trailing zeros, so the forms are variable-length: on text, '...:37Z' sorts
                   -- after '...:37.4Z' under any collation ranking 'Z' above '.', and under COLLATE "C"
                   -- same-second comparisons invert outright. The stored VALUE stays whichever original
                   -- string won, so nothing is re-rendered.
                   last_event_at = CASE
                       WHEN CAST(:lastEventAt AS text) IS NULL THEN last_event_at
                       WHEN last_event_at IS NULL THEN CAST(:lastEventAt AS text)
                       WHEN CAST(:lastEventAt AS timestamptz) > last_event_at::timestamptz
                           THEN CAST(:lastEventAt AS text)
                       ELSE last_event_at END,
                   -- Advanced in the same statement as the counter it guards. A backfill can deliver
                   -- older rows after newer ones on the EVENT clock, but the keyset rides the INGEST
                   -- clock, which is monotonic and gap-free by construction — so COALESCE (take the new
                   -- value whenever one was supplied) is right here where GREATEST would be wrong above.
                   counted_through_at = COALESCE(CAST(:countedThroughAt AS text), counted_through_at),
                   counted_through_id = COALESCE(CAST(:countedThroughId AS text), counted_through_id),
                   updated_at = :now
             WHERE id = :id
            """)
                .param("delta", countDelta)
                .param("windowOpenedAt", windowOpenedAt)
                .param("lastEventAt", lastEventAt)
                .param("countedThroughAt", countedThroughAt)
                .param("countedThroughId", countedThroughId)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /**
     * Persist the current window's sketch after the sweep merged a batch into it, and its two sidecars
     * with it — the workload it was asked for and, for a cost window, the token decomposition it was made
     * of. All three are written in one statement because they describe the same samples: a window whose
     * measure sketch advanced while a sidecar lagged would report a shift against inputs from a different
     * set of turns, which is the one comparison a finding must never make.
     */
    public void updateCurrentSketch(
            String id,
            @Nullable String currentSketchJson,
            @Nullable String currentWorkloadJson,
            @Nullable String currentTokensJson,
            String now) {
        jdbc.sql("UPDATE metric_baseline SET current_sketch_json = :sketch, "
                        + "current_workload_json = CAST(:workload AS text), "
                        + "current_tokens_json = CAST(:tokens AS text), updated_at = :now WHERE id = :id")
                .param("sketch", currentSketchJson)
                .param("workload", currentWorkloadJson)
                .param("tokens", currentTokensJson)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /**
     * Close the current window: it is folded into {@code control_json}, the rolling reference the next
     * close compares against for sudden breaks, and a fresh window opens. The caller does the folding
     * and hands the new ring in — nothing is rotated into a prev slot, which no longer exists: it was
     * replaced by the control ring.
     *
     * <p>{@code counted_through_*} is untouched. It is per ROW, not per window: it answers "which
     * ingested samples has this baseline already seen", and resetting it on close would re-admit the
     * tail of the window just closed into the window just opened. {@code current_count} is the per-window
     * counter and is the only one that resets.
     *
     * <p>The new window is opened with a CARRY rather than empty, because a batch legitimately straddles
     * the cut: windows are cut on event time and a single ingest page can contain samples from both
     * sides of the boundary. The caller splits the batch and hands back the far side, so those samples
     * land in the window they belong to instead of being counted into the closed one or dropped. A
     * close that happens to fall on a page boundary passes {@code null} / {@code 0}.
     *
     * @param openedAt event time to open the new window at, or null to let the next batch's earliest
     *     sample set it.
     */
    public void closeWindow(
            String id,
            @Nullable String controlJson,
            @Nullable String openedAt,
            @Nullable String carriedSketchJson,
            @Nullable String carriedWorkloadJson,
            @Nullable String carriedTokensJson,
            long carriedCount,
            String now) {
        jdbc.sql("""
            UPDATE metric_baseline
               SET control_json = CAST(:control AS text),
                   current_sketch_json = CAST(:carriedSketch AS text),
                   -- Both sidecars rotate with the sketch they describe, in the same statement. A window,
                   -- its workload and its token decomposition are three summaries of ONE set of turns, and
                   -- a finding's whole argument is that they moved differently — which is only readable if
                   -- they are guaranteed to cover the same turns. That is also why the closed window's
                   -- three blobs go into the control ring TOGETHER, in the same day slot, rather than the
                   -- measure sketch rolling on its own.
                   current_workload_json = CAST(:carriedWorkload AS text),
                   current_tokens_json = CAST(:carriedTokens AS text),
                   current_opened_at = CAST(:openedAt AS text),
                   current_count = :carriedCount,
                   updated_at = :now
             WHERE id = :id
            """)
                .param("control", controlJson)
                .param("carriedSketch", carriedSketchJson)
                .param("carriedWorkload", carriedWorkloadJson)
                .param("carriedTokens", carriedTokensJson)
                .param("openedAt", openedAt)
                .param("carriedCount", carriedCount)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /**
     * Re-pin the deploy reference: {@code pinned_sketch ← current}, stamped with when and with which
     * project version.
     *
     * <p>This is the write behind <em>Legitimate — absorb</em>, and behind a deploy re-pinning its
     * epoch. It is NOT reachable from triage, which rules on whether a claim holds and never on whether
     * a shift is welcome: an automatic re-pin on a machine ruling would let the very next window
     * silently normalize a real regression. Triage surfaces the verb; a human presses it.
     *
     * <p>Callers append a {@code behavior_baseline_event} row alongside this. An online baseline cannot
     * be stopped from absorbing drift; what can be done is to make every absorption a durable, readable
     * row.
     */
    public void repin(
            String id,
            @Nullable String pinnedSketchJson,
            @Nullable String pinnedWorkloadJson,
            @Nullable String pinnedTokensJson,
            @Nullable String pinnedRefsJson,
            String pinnedAt,
            @Nullable String pinnedByVersionId,
            String now) {
        jdbc.sql("""
            UPDATE metric_baseline
               SET pinned_sketch_json = CAST(:pinnedSketch AS text),
                   -- Both sidecars move with the sketch. A reference whose workload stayed behind would
                   -- let the next finding compare today's traffic against the input profile of a window
                   -- that is no longer the reference — flat inputs that were never the same inputs — and
                   -- a stale token decomposition would explain today's dollars with last month's cache.
                   pinned_workload_json = CAST(:pinnedWorkload AS text),
                   pinned_tokens_json = CAST(:pinnedTokens AS text),
                   -- And so do the refs, for the same reason and one stronger: a finding writes them as
                   -- its baseline evidence, so refs left behind by a re-pin would put a human absorb's
                   -- traffic under a claim about the window that absorb replaced.
                   pinned_refs_json = CAST(:pinnedRefs AS text),
                   pinned_at = :pinnedAt,
                   pinned_by_version_id = CAST(:pinnedVersion AS text),
                   updated_at = :now
             WHERE id = :id
            """)
                .param("pinnedSketch", pinnedSketchJson)
                .param("pinnedWorkload", pinnedWorkloadJson)
                .param("pinnedTokens", pinnedTokensJson)
                .param("pinnedRefs", pinnedRefsJson)
                .param("pinnedAt", pinnedAt)
                .param("pinnedVersion", pinnedByVersionId)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /**
     * Move the state machine — {@code learning} → {@code armed} once the bucket has enough samples to be
     * compared, or → {@code stale} when its sketch stopped describing it. Separate from
     * {@link #advanceWindow} because arming is a decision about the row rather than an observation of
     * traffic, and the sweep must not be able to arm a bucket as a side effect of counting.
     */
    public void updateState(String id, String state, String now) {
        jdbc.sql("UPDATE metric_baseline SET state = :state, updated_at = :now WHERE id = :id")
                .param("state", state)
                .param("now", now)
                .param("id", id)
                .update();
    }

    private static MetricBaselineRow map(ResultSet rs) throws SQLException {
        return new MetricBaselineRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("classifier_id"),
                rs.getString("measure"),
                rs.getString("bucket_kind"),
                rs.getString("bucket_key"),
                rs.getString("state"),
                rs.getString("pinned_sketch_json"),
                rs.getString("pinned_at"),
                rs.getString("pinned_by_version_id"),
                rs.getString("current_sketch_json"),
                rs.getString("pinned_workload_json"),
                rs.getString("current_workload_json"),
                rs.getString("pinned_tokens_json"),
                rs.getString("pinned_refs_json"),
                rs.getString("prev_tokens_json"),
                rs.getString("current_tokens_json"),
                rs.getString("control_json"),
                rs.getString("current_opened_at"),
                rs.getLong("current_count"),
                rs.getString("counted_through_at"),
                rs.getString("counted_through_id"),
                rs.getString("last_event_at"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
