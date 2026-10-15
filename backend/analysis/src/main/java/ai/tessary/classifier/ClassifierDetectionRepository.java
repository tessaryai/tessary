// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.detection.DetectionTableRegistry;
import ai.tessary.storage.Timestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads detections from {@link DetectionTableRegistry}'s union; an unregistered table drops its arm. Its
 * {@code classifier_id} holds the key. The session is the trace's {@code session_id}: pre-{@code 0034} Frustration rows
 * hold a thread id in {@code subject_session_id}.
 */
@Repository
public class ClassifierDetectionRepository {

    private final JdbcClient jdbc;
    private final String select;
    private final String modeCountsSql;
    private final String dailyCountsSql;

    public ClassifierDetectionRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        String relation = "(" + detectionTables.unionSql() + ") d";
        this.select = "SELECT d.id AS id, s.id AS classifier_id, "
                + "s.version AS classifier_version, d.subject_kind AS subject_kind, "
                + "t.session_id AS session_id, d.subject_trace_id AS trace_id, "
                + "d.subject_span_id AS span_id, "
                + "d.project_version_id AS project_version_id, d.evidence AS evidence, "
                + "d.severity AS severity, d.confidence AS confidence, d.created_at AS created_at, "
                + "d.subject_started_at AS subject_started_at "
                + "FROM " + relation + " "
                + "JOIN classifier s ON s.project_id = d.project_id AND s.classifier_key = d.classifier_id "
                + "LEFT JOIN trace t ON t.project_id = d.project_id AND t.id = d.subject_trace_id ";
        this.modeCountsSql = "SELECT\n"
                + "  COUNT(*) FILTER (WHERE confidence = 'low')                        AS low_count,\n"
                + "  COUNT(*) FILTER (WHERE confidence = 'high' OR confidence IS NULL) AS high_count\n"
                + "FROM " + relation + " WHERE project_id = :pid AND classifier_id = :classifierKey";
        this.dailyCountsSql = "SELECT s.id AS classifier_id,\n"
                + "       (d.subject_started_at AT TIME ZONE 'UTC')::date AS day,\n"
                + "       COUNT(DISTINCT d.subject_trace_id) AS traces\n"
                + "FROM " + relation + "\n"
                + "JOIN classifier s ON s.project_id = d.project_id AND s.classifier_key = d.classifier_id\n"
                + "WHERE d.project_id = :pid AND d.subject_started_at >= :from\n"
                + "GROUP BY s.id, day\n"
                + "ORDER BY day ASC";
    }

    public List<ClassifierDtos.ClassifierEventView> listByProject(String projectId, int limit) {
        return jdbc.sql(select
                        + "WHERE d.project_id = :pid ORDER BY d.subject_started_at DESC NULLS LAST, d.created_at DESC"
                        + " LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** {@code trackingOnly} restricts to the high band; unbanded NULL reads as high. */
    public List<ClassifierDtos.ClassifierEventView> listByClassifierKey(
            String projectId, String classifierKey, boolean trackingOnly, int limit) {
        String confidenceFilter = trackingOnly ? " AND (d.confidence = 'high' OR d.confidence IS NULL)" : "";
        return jdbc.sql(select + "WHERE d.project_id = :pid AND d.classifier_id = :classifierKey" + confidenceFilter
                        + " ORDER BY d.subject_started_at DESC NULLS LAST, d.created_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("classifierKey", classifierKey)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    public ModeCounts modeCounts(String projectId, String classifierKey) {
        return jdbc.sql(modeCountsSql)
                .param("pid", projectId)
                .param("classifierKey", classifierKey)
                .query((rs, n) -> new ModeCounts(rs.getLong("high_count"), rs.getLong("low_count")))
                .single();
    }

    public record ModeCounts(long high, long low) {}

    /**
     * Buckets by {@code (subject_started_at AT TIME ZONE 'UTC')::date}, the day the span ran; {@code date_trunc}
     * follows the session timezone. A null {@code subject_started_at} falls out of the filter so the index stays
     * usable.
     */
    public List<DailyClassifierCount> dailyDetectionCounts(String projectId, java.time.Instant from) {
        return jdbc.sql(dailyCountsSql)
                .param("pid", projectId)
                .param("from", java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .query((rs, n) -> new DailyClassifierCount(
                        rs.getString("classifier_id"),
                        rs.getObject("day", java.time.LocalDate.class),
                        rs.getLong("traces")))
                .list();
    }

    public record DailyClassifierCount(String classifierId, java.time.LocalDate day, long traces) {}

    private static ClassifierDtos.ClassifierEventView map(ResultSet rs) throws SQLException {
        // Each detection table has one grain, so only 'span' and 'trace' are possible.
        String subjectKind = rs.getString("subject_kind");
        String subjectId = "trace".equals(subjectKind) ? rs.getString("trace_id") : rs.getString("span_id");
        return new ClassifierDtos.ClassifierEventView(
                rs.getString("id"),
                rs.getString("classifier_id"),
                rs.getInt("classifier_version"),
                java.util.Objects.requireNonNull(subjectKind, "verdict.subject_kind is NOT NULL"),
                java.util.Objects.requireNonNull(subjectId, "signal detection subject id is present"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("project_version_id"),
                rs.getString("severity"), // the detection's coarse severity
                rs.getString("evidence"),
                rs.getString("confidence"),
                java.util.Objects.requireNonNull(Timestamps.iso(rs, "created_at"), "detection created_at is NOT NULL"),
                Timestamps.iso(rs, "subject_started_at"));
    }
}
