// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import ai.tessary.detection.DetectionTableRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Bounded retention deletes: every statement is {@code ctid IN (SELECT … LIMIT n)} and the caller loops, so a backlog
 * drains in short statements. Payloads age out first on a shorter horizon, leaving spans and traces intact for every
 * list and rollup. Substrate a live finding's evidence points at is never deleted at any tier: {@link #PINNED} is
 * spliced into every statement, at both the trace and session grain.
 */
@Repository
public class RetentionRepository {

    /**
     * Both tokens take a column reference, never a value: {@link #withPin} is the only substituter, and its arguments
     * are literals in this file.
     */
    private static final String PINNED = """
            EXISTS (
                SELECT 1 FROM finding_evidence e
                WHERE e.project_id = :pid
                  AND (e.trace_id = {trace} OR e.session_id = {session})
                  AND EXISTS (SELECT 1 FROM finding f
                              WHERE f.id = e.finding_id AND f.status = 'open'))""";

    private static String withPin(String sql, String traceId, String sessionId) {
        return sql.replace("{pin}", PINNED.replace("{trace}", traceId).replace("{session}", sessionId));
    }

    private final JdbcClient jdbc;
    private final DetectionTableRegistry detectionTables;

    public RetentionRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        this.detectionTables = detectionTables;
    }

    /**
     * Cut on the span's clock so the tiers agree. The pin's session comes from the {@code trace} row through a left
     * join: an uncorrelated span's own {@code session_id} is null until the resolver reaches it.
     */
    public int deleteSpanPayloads(String projectId, String cutoff, int limit) {
        return jdbc.sql(withPin("""
                        DELETE FROM span_payload WHERE ctid IN (
                            SELECT p.ctid FROM span_payload p
                            JOIN span s ON s.project_id = p.project_id
                                       AND s.trace_id = p.trace_id AND s.id = p.span_id
                            LEFT JOIN trace t ON t.project_id = p.project_id AND t.id = p.trace_id
                            WHERE p.project_id = :pid
                              AND s.started_at < :cutoff::timestamptz
                              AND NOT {pin}
                            LIMIT :n)
                        """, "s.trace_id", "t.session_id"))
                .param("pid", projectId)
                .param("cutoff", cutoff)
                .param("n", limit)
                .update();
    }

    /**
     * Nulls the copy of a turn's text sent to the decision model, under the same pin; the verdict and cost stay for the
     * replay and audit. Cuts on {@code turn_started_at}, so a row whose trace is already gone is still reached.
     */
    public int nullAssessmentRequests(String projectId, String cutoff, int limit) {
        return jdbc.sql(withPin("""
                        UPDATE frustration_assessment SET request = NULL WHERE ctid IN (
                            SELECT a.ctid FROM frustration_assessment a
                            LEFT JOIN trace t ON t.project_id = a.project_id AND t.id = a.trace_id
                            WHERE a.project_id = :pid
                              AND a.request IS NOT NULL
                              AND a.turn_started_at < :cutoff::timestamptz
                              AND NOT {pin}
                            LIMIT :n)
                        """, "a.trace_id", "t.session_id"))
                .param("pid", projectId)
                .param("cutoff", cutoff)
                .param("n", limit)
                .update();
    }

    /**
     * Spans and payloads cascade. Cut on {@code trace.started_at}, so a replayed corpus ages on its own timeline. A
     * pinned trace is never deleted, or a live case becomes a page of dead links; see {@link #PINNED}.
     */
    public int deleteTraces(String projectId, String cutoff, int limit) {
        return jdbc.sql(withPin("""
                        DELETE FROM trace WHERE ctid IN (
                            SELECT t.ctid FROM trace t
                            WHERE t.project_id = :pid
                              AND t.started_at < :cutoff::timestamptz
                              AND NOT {pin}
                            LIMIT :n)
                        """, "t.id", "t.session_id"))
                .param("pid", projectId)
                .param("cutoff", cutoff)
                .param("n", limit)
                .update();
    }

    /**
     * Collect evidence rows whose finding is closed and whose substrate is already gone, so it never removes a
     * reference that resolves or one a live claim needs. Not an FK cascade, which would fail retention or silently
     * shrink a closed finding's evidence. A session-grain row releases on its traces, since nothing deletes sessions.
     */
    public int deleteOrphanedEvidence(String projectId, int limit) {
        return jdbc.sql(withPin("""
                        DELETE FROM finding_evidence WHERE ctid IN (
                            SELECT e2.ctid FROM finding_evidence e2
                            WHERE e2.project_id = :pid
                              AND NOT {pin}
                              AND NOT EXISTS (
                                  SELECT 1 FROM trace t
                                  WHERE t.project_id = e2.project_id AND t.id = e2.trace_id)
                              AND NOT EXISTS (
                                  SELECT 1 FROM trace ts
                                  WHERE ts.project_id = e2.project_id AND ts.session_id = e2.session_id)
                            LIMIT :n)
                        """, "e2.trace_id", "e2.session_id"))
                .param("pid", projectId)
                .param("n", limit)
                .update();
    }

    /**
     * Makes an unbounded pin visible: climbing without a matching climb in open findings means a classifier writes
     * evidence it never closes.
     */
    public long countPinnedTraces(String projectId) {
        return jdbc.sql(withPin("""
                        SELECT count(*) FROM trace t
                        WHERE t.project_id = :pid AND {pin}
                        """, "t.id", "t.session_id"))
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /**
     * {@code {table}} takes only a name from {@link ai.tessary.detection.DetectionTableRegistry#tables()}; the
     * parameters are bound.
     */
    private static final String DELETE_DETECTIONS = """
            DELETE FROM {table} WHERE ctid IN (
                SELECT d.ctid FROM {table} d
                WHERE d.project_id = :pid
                  AND d.created_at < :cutoff::timestamptz
                  AND NOT {pin}
                LIMIT :n)
            """;

    /**
     * Pinned at the session and trace grains via the detection's own subject columns. {@code limit} is a budget for the
     * whole call across tables, so the result is {@code <= limit} as {@link RetentionSweeper}'s "more to do" test
     * needs.
     */
    public int deleteDetections(String projectId, String cutoff, int limit) {
        int deleted = 0;
        // Sorted by name, so the budget is spent in a deterministic order.
        for (var table : detectionTables.tables()) {
            int remaining = limit - deleted;
            if (remaining <= 0) break;
            deleted += jdbc.sql(withPin(
                            DELETE_DETECTIONS.replace("{table}", table.table()),
                            "d.subject_trace_id",
                            "d.subject_session_id"))
                    .param("pid", projectId)
                    .param("cutoff", cutoff)
                    .param("n", remaining)
                    .update();
        }
        return deleted;
    }

    /**
     * Collect media nothing references: not a policy, so it runs for every project. The cutoff is a grace window, since
     * bytes are stored before the batch writes their {@code media_ref} rows. A deduplicated object is collected when
     * its last referrer is gone.
     */
    public int deleteOrphanedMedia(String projectId, String graceCutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM media_object WHERE ctid IN (
                            SELECT m.ctid FROM media_object m
                            WHERE m.project_id = :pid
                              AND m.created_at::timestamptz < :cutoff::timestamptz
                              AND NOT EXISTS (
                                  SELECT 1 FROM media_ref r
                                  WHERE r.project_id = m.project_id AND r.media_id = m.id)
                            LIMIT :n)
                        """)
                .param("pid", projectId)
                .param("cutoff", graceCutoff)
                .param("n", limit)
                .update();
    }
}
