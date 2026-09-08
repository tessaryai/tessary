// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import ai.tessary.evals.detection.DetectionTableRegistry;
import ai.tessary.evals.storage.Timestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads classifier detections out of the runtime-stitched union {@link DetectionTableRegistry}
 * builds over every registered per-classifier detection table — the same twelve-column shape the
 * old baseline's stitching view (migration {@code 0089}) used to project, now assembled at query
 * time so a table simply not being registered on this classpath (e.g. the open edition without a
 * paid classifier jar) removes its arm instead of the query naming a relation that was never
 * created. The view itself is left physically in the baseline changelog, unread, until the
 * partition commit deletes it.
 *
 * <p>The union's {@code classifier_id} column holds the classifier KEY — persisted wire vocabulary inherited
 * verbatim from {@code signal_event_v}, which held {@code verdict.key} there — so the definition's own
 * id and version still come from a JOIN on {@code classifier_key = d.classifier_id}, exactly as they did when
 * detections were verdicts.
 *
 * <p><b>Detection ids changed id-space at the cutover.</b> They are no longer verdict ids, and the rows
 * that carried the old ones were deleted rather than copied (start fresh, plan §1). A stored link to a
 * pre-cutover detection id resolves to nothing, by design.
 *
 * <p>The raw {@link JdbcClient} lives here (not in {@link ClassifierService}) so the architecture rule
 * "JDBC only in repositories" holds.
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
                + "d.subject_session_id AS session_id, d.subject_trace_id AS trace_id, "
                + "d.subject_span_id AS span_id, "
                + "d.project_version_id AS project_version_id, d.evidence AS evidence, "
                + "d.severity AS severity, d.confidence AS confidence, d.created_at AS created_at "
                + "FROM " + relation + " "
                + "JOIN classifier s ON s.project_id = d.project_id AND s.classifier_key = d.classifier_id ";
        this.modeCountsSql = "SELECT\n"
                + "  COUNT(*) FILTER (WHERE confidence = 'low')                        AS low_count,\n"
                + "  COUNT(*) FILTER (WHERE confidence = 'high' OR confidence IS NULL) AS high_count\n"
                + "FROM " + relation + " WHERE project_id = :pid AND classifier_id = :classifierKey";
        this.dailyCountsSql = "SELECT s.id AS classifier_id,\n"
                + "       (d.created_at AT TIME ZONE 'UTC')::date AS day,\n"
                + "       COUNT(DISTINCT d.subject_trace_id) AS traces\n"
                + "FROM " + relation + "\n"
                + "JOIN classifier s ON s.project_id = d.project_id AND s.classifier_key = d.classifier_id\n"
                + "WHERE d.project_id = :pid AND d.created_at >= :from\n"
                + "GROUP BY s.id, day\n"
                + "ORDER BY day ASC";
    }

    /** All detections for a project, newest-first. */
    public List<ClassifierDtos.ClassifierEventView> listByProject(String projectId, int limit) {
        return jdbc.sql(select + "WHERE d.project_id = :pid ORDER BY d.created_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Detections for one signal (by its {@code classifierKey} = verdict.key), newest-first. When
     * {@code trackingOnly}, restrict to the precise HIGH-confidence band (unbanded NULL reads as high).
     */
    public List<ClassifierDtos.ClassifierEventView> listByClassifierKey(
            String projectId, String classifierKey, boolean trackingOnly, int limit) {
        String confidenceFilter = trackingOnly ? " AND (d.confidence = 'high' OR d.confidence IS NULL)" : "";
        return jdbc.sql(select + "WHERE d.project_id = :pid AND d.classifier_id = :classifierKey" + confidenceFilter
                        + " ORDER BY d.created_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("classifierKey", classifierKey)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** High/low confidence-band counts for one classifier's detections — the discovery-vs-tracking breakdown. */
    public ModeCounts modeCounts(String projectId, String classifierKey) {
        return jdbc.sql(modeCountsSql)
                .param("pid", projectId)
                .param("classifierKey", classifierKey)
                .query((rs, n) -> new ModeCounts(rs.getLong("high_count"), rs.getLong("low_count")))
                .single();
    }

    /** High/low detection counts for a classifier's key. */
    public record ModeCounts(long high, long low) {}

    /**
     * Per-classifier, per-UTC-day distinct-trace detection counts since {@code from}, oldest day first.
     * Buckets on {@code (created_at AT TIME ZONE 'UTC')::date} rather than {@code date_trunc('day', …)} —
     * date_trunc on a timestamptz follows the session timezone, which would shift day boundaries.
     * Every detection carries a trace: both grains the view projects are inside one.
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

    /** One classifier's distinct-trace detection count in one UTC day bucket. */
    public record DailyClassifierCount(String classifierId, java.time.LocalDate day, long traces) {}

    private static ClassifierDtos.ClassifierEventView map(ResultSet rs) throws SQLException {
        // The grain is a LITERAL per view arm now, not a stored guess: each detection table declares the
        // one grain its classifier judges at, so 'span' and 'trace' are the only two answers and each is
        // read off the column that actually holds that grain's id.
        String subjectKind = rs.getString("subject_kind");
        String subjectId = "trace".equals(subjectKind) ? rs.getString("trace_id") : rs.getString("span_id");
        return new ClassifierDtos.ClassifierEventView(
                rs.getString("id"),
                rs.getString("classifier_id"),
                rs.getInt("classifier_version"),
                java.util.Objects.requireNonNull(subjectKind, "verdict.subject_kind is NOT NULL"),
                java.util.Objects.requireNonNull(subjectId, "signal detection subject id is present"),
                rs.getString("trace_id"),
                rs.getString("project_version_id"),
                rs.getString("severity"), // v.label — the detection's coarse severity
                rs.getString("evidence"),
                rs.getString("confidence"),
                java.util.Objects.requireNonNull(Timestamps.iso(rs, "created_at"), "detection created_at is NOT NULL"));
    }
}
