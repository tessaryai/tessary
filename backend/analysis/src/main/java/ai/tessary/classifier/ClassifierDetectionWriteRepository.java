// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.detection.DetectionTableRegistry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes a fired detection into its classifier's own table (migration {@code 0088}).
 *
 * <p>A detection used to be a {@code verdict} row with {@code source='automatic'}, sharing a table with
 * grader scores, human rulings and escalation runs. It no longer is. Each per-span classifier has a
 * table, each table declares the grain that classifier judges at, and the idempotency follows from that
 * grain instead of from whatever the shared {@code ux_verdict_online_subject} index happened to key on.
 *
 * <p><b>The routing is by detector kind, not by table name guessing.</b> {@link #tableFor} is the one
 * place that maps a classifier onto its store; a kind with no table is a classifier that does not write
 * per-span detections (the drift/conformance families file findings directly), and the writer says so by
 * returning null rather than inventing a destination.
 *
 * <p>The name says WRITE because {@link ClassifierDetectionRepository} is the read side: that one
 * projects the stitched view for the events surface, this one owns the six tables the view is stitched
 * from and the window counts a classifier's arming gate reads back.
 *
 * <p><b>Insert-if-absent, first-write-wins.</b> {@code ON CONFLICT DO NOTHING} against the per-table
 * unique subject index, so a re-sweep over the same window writes nothing and
 * {@link #insert} reports {@code false}: a genuinely new detection is the one that inserted. That is the
 * same contract the old online-verdict upsert had, and the arming counter and the pre-deploy
 * registration both still key on it.
 */
@Repository
public class ClassifierDetectionWriteRepository {

    private final JdbcClient jdbc;
    private final DetectionTableRegistry tables;

    public ClassifierDetectionWriteRepository(JdbcClient jdbc, DetectionTableRegistry tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    /** The detection table for {@code detectorKind}, or null when that classifier writes no detections. */
    public @Nullable String tableFor(String detectorKind) {
        return tables.tableFor(detectorKind);
    }

    /** True when {@code detectorKind} has a detection table — i.e. it is one of the per-span classifiers. */
    public boolean writesDetections(String detectorKind) {
        return tables.writesDetections(detectorKind);
    }

    /**
     * Insert one fired detection, or do nothing if this classifier has already spoken about this subject.
     *
     * @return true when the row was written — a genuinely new detection
     * @throws IllegalArgumentException when the classifier has no detection table (a routing bug, not a
     *     runtime condition: the caller checks {@link #writesDetections} before it scores anything)
     */
    public boolean insert(
            String id,
            String detectorKind,
            String projectId,
            String classifierId,
            String classifierKey,
            @Nullable String projectVersionId,
            @Nullable String sessionId,
            String traceId,
            @Nullable String spanId,
            @Nullable String severity,
            @Nullable String confidence,
            @Nullable String evidenceJson) {
        String table = tableFor(detectorKind);
        if (table == null) {
            throw new IllegalArgumentException("no detection table for detector kind " + detectorKind);
        }
        // The table name is interpolated because it is not a bind-able position; every value that
        // reaches SQL from outside this class is a parameter, and the name itself comes from a
        // DetectionTable registration, whose constructor rejects anything but a bare identifier.
        int written = jdbc.sql("INSERT INTO " + table + " (id, project_id, classifier_id, classifier_key,"
                        + " project_version_id, subject_session_id, subject_trace_id, subject_span_id,"
                        + " severity, confidence, evidence, created_at)"
                        + " VALUES (:id, :pid, :sid, :skey, :versionId, :sessionId, :traceId, :spanId,"
                        + " :severity, :confidence, CAST(:evidence AS jsonb), now())"
                        + " ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("skey", classifierKey)
                .param("versionId", projectVersionId)
                .param("sessionId", sessionId)
                .param("traceId", traceId)
                .param("spanId", spanId)
                .param("severity", severity)
                .param("confidence", confidence)
                .param("evidence", evidenceJson)
                .update();
        return written > 0;
    }

    /**
     * How many detections this classifier has recorded in {@code [start, end)} — the count the sweep's
     * arming gate reads. Scoped by {@code classifier_id} rather than by key so a renamed classifier keeps its
     * own window, and restricted to the HIGH band (NULL means high) when the classifier is in tracking
     * mode, which is the same precision filter every other detection read applies.
     */
    public long countInWindow(
            String detectorKind,
            String projectId,
            String classifierId,
            String start,
            String end,
            boolean trackingOnly) {
        String table = tableFor(detectorKind);
        if (table == null) return 0;
        return jdbc.sql("SELECT COUNT(*) FROM " + table
                        + " WHERE project_id = :pid AND classifier_id = :sid"
                        + " AND created_at >= :start::timestamptz AND created_at < :end::timestamptz"
                        + (trackingOnly ? " AND (confidence = 'high' OR confidence IS NULL)" : ""))
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("start", start)
                .param("end", end)
                .query(Long.class)
                .single();
    }

    /**
     * Of {@code sessionIds}, the ones this classifier has ALREADY flagged at the HIGH band.
     *
     * <p>The turn-grain sweep reads this to stop re-scoring a conversation that is already flagged. A
     * conversation is one event, not one per turn: interview-coach carried 8,012 frustration detections
     * over 987 conversations (2026-08-20) — 8.12 rows per conversation, each a separate encoder call,
     * all saying the same thing about the same conversation.
     *
     * <p>HIGH specifically, because HIGH is the CEILING: no later turn can move a conversation already
     * flagged at the top band, so scoring one is work with no possible outcome. A conversation flagged
     * only at LOW stays eligible so it can still escalate. NULL reads as high, the same convention every
     * other detection read here uses.
     */
    public Set<String> sessionsAlreadyFlaggedHigh(
            String detectorKind, String projectId, String classifierId, Collection<String> sessionIds) {
        String table = tableFor(detectorKind);
        if (table == null || sessionIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT DISTINCT subject_session_id FROM " + table
                        + " WHERE project_id = :pid AND classifier_id = :sid"
                        + " AND subject_session_id IN (:sessions)"
                        + " AND (confidence = 'high' OR confidence IS NULL)")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("sessions", sessionIds)
                .query(String.class)
                .list());
    }

    /**
     * How many distinct SESSIONS this classifier flagged in {@code [start, end)} — the {@code
     * distinct_users} basis a translated threshold rule may carry. Session is the user proxy until
     * entity identity is wired.
     *
     * <p>Read straight off the detection row: the span carried its session when the sweep scored it, so
     * there is no join. Anonymous traffic contributes nothing — {@code subject_session_id} is NULL and
     * {@code COUNT(DISTINCT)} skips nulls, which is the honest answer, since a turn with no session
     * identifies no user to count.
     */
    public long countDistinctSessionsInWindow(
            String detectorKind,
            String projectId,
            String classifierId,
            String start,
            String end,
            boolean trackingOnly) {
        String table = tableFor(detectorKind);
        if (table == null) return 0;
        return jdbc.sql("SELECT COUNT(DISTINCT subject_session_id) FROM " + table
                        + " WHERE project_id = :pid AND classifier_id = :sid"
                        + " AND created_at >= :start::timestamptz AND created_at < :end::timestamptz"
                        + (trackingOnly ? " AND (confidence = 'high' OR confidence IS NULL)" : ""))
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("start", start)
                .param("end", end)
                .query(Long.class)
                .single();
    }
}
