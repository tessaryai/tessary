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
 * Three writers touch a row on different clocks (sweep fold, window close, human absorb), so each gets its own method
 * touching only its columns. Contract: {@code devdocs/concepts/metric-drift.md}.
 */
@Repository
public class MetricBaselineRepository {

    private static final String COLS = "id, project_id, classifier_id, measure, bucket_kind, bucket_key, "
            + "state, pinned_sketch_json, pinned_at, pinned_by_version_id, "
            + "current_sketch_json, pinned_workload_json, current_workload_json, "
            + "pinned_tokens_json, pinned_refs_json, current_tokens_json, "
            + "current_refs_json, "
            + "control_json, current_opened_at, current_count, "
            + "counted_through_at, counted_through_id, last_event_at, created_at, updated_at";

    /**
     * Postgres infers the arbiter index from the {@code ON CONFLICT} target text, so this must match
     * {@code ux_metric_baseline_scope} character for character.
     */
    private static final String SCOPE_KEY = "project_id, classifier_id, measure, bucket_kind, bucket_key";

    private final JdbcClient jdbc;

    public MetricBaselineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Buckets are discovered from traffic, so read-then-insert would race two sweeps into a duplicate key.
     * {@code DO UPDATE}, not {@code DO NOTHING}, so {@code RETURNING} yields the existing row.
     */
    public MetricBaselineRow ensure(MetricBaselineRow seed) {
        return jdbc.sql("INSERT INTO metric_baseline (" + COLS + ") VALUES (:id, :pid, :sid, :measure, "
                        + ":bucketKind, :bucketKey, :state, :pinnedSketch, :pinnedAt, :pinnedVersion, "
                        + ":currentSketch, :pinnedWorkload, :currentWorkload, "
                        + ":pinnedTokens, :pinnedRefs, :currentTokens, :currentRefs, :control, "
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
                .param("currentTokens", seed.currentTokensJson())
                .param("currentRefs", seed.currentRefsJson())
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

    public Optional<MetricBaselineRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM metric_baseline WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public int deleteByClassifier(String projectId, String classifierId) {
        return jdbc.sql("DELETE FROM metric_baseline WHERE project_id = :pid AND classifier_id = :sid")
                .param("pid", projectId)
                .param("sid", classifierId)
                .update();
    }

    /**
     * Thickest window first: thin buckets wait rather than being skipped, and would otherwise sit at the front of every
     * page.
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
     * Counter and watermark move in one statement, so an interrupted sweep leaves a consistent row. The caller still
     * filters against {@code counted_through_*}. Does not touch the sketch: a read-modify-write here would drop a
     * concurrent close's rotation.
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
     * Sketch, sidecars and refs are written together because they describe the same samples; refs are the one part that
     * cannot be recomputed later.
     */
    public void updateCurrentSketch(
            String id,
            @Nullable String currentSketchJson,
            @Nullable String currentWorkloadJson,
            @Nullable String currentTokensJson,
            @Nullable String currentRefsJson,
            String now) {
        jdbc.sql("UPDATE metric_baseline SET current_sketch_json = :sketch, "
                        + "current_workload_json = CAST(:workload AS text), "
                        + "current_tokens_json = CAST(:tokens AS text), "
                        + "current_refs_json = CAST(:refs AS text), updated_at = :now WHERE id = :id")
                .param("sketch", currentSketchJson)
                .param("workload", currentWorkloadJson)
                .param("tokens", currentTokensJson)
                .param("refs", currentRefsJson)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /**
     * {@code counted_through_*} is per row, not per window, so resetting it on close would re-admit the closed window's
     * tail. Only {@code current_count} resets.
     */
    public void closeWindow(String id, @Nullable String controlJson, String now) {
        jdbc.sql("""
            UPDATE metric_baseline
               SET control_json = CAST(:control AS text),
                   current_sketch_json = NULL,
                   -- Both sidecars rotate with the sketch they describe, in the same statement. A window,
                   -- its workload and its token decomposition are three summaries of ONE set of turns, and
                   -- a finding's whole argument is that they moved differently — which is only readable if
                   -- they are guaranteed to cover the same turns. That is also why the closed window's
                   -- three blobs go into the control ring TOGETHER, in the same day slot, rather than the
                   -- measure sketch rolling on its own.
                   current_workload_json = NULL,
                   current_tokens_json = NULL,
                   -- The refs rotate here too, and must: they are the rows the NEW window is a claim
                   -- about, so a close that folded the sketch but left the old list behind would open a
                   -- window already holding the closed window's population.
                   current_refs_json = NULL,
                   current_opened_at = NULL,
                   current_count = 0,
                   updated_at = :now
             WHERE id = :id
            """)
                .param("control", controlJson)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /**
     * Not reachable from triage: an automatic re-pin on a machine ruling would let the next window silently normalize a
     * real regression. Callers append a {@code behavior_baseline_event} row.
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

    /** Separate from {@link #advanceWindow} so a sweep cannot arm a bucket as a side effect of counting. */
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
                rs.getString("current_tokens_json"),
                rs.getString("current_refs_json"),
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
