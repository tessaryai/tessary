// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for the curated {@code failure_mode} + {@code failure_mode_instance} clustering output.
 * A recompute UPSERTs each failure mode on its stable {@code (project_id, key)} so its id + lifecycle
 * {@code status} survive: a re-cluster refreshes the mode's name/severity/window and its instance set, but a
 * curated (open/resolved/muted) mode keeps its identity. Stale modes (absent from the latest clustering) are
 * left with their curated lifecycle rather than deleted.
 */
@Repository
public class FailureModeRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public FailureModeRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One computed cluster: the failure-mode row plus its instance rows, written together. */
    public record FailureModeWithInstances(FailureModeRow failureMode, List<FailureModeInstanceRow> instances) {}

    private static final String FM_COLS = "id, project_id, key, name, description, status, severity, "
            + "surface, signature, centroid_ref, impact_count, impact_rate, first_seen_version_id, "
            + "regressed_version_id, first_seen_at, last_seen_at, created_at, updated_at, attributes";

    private static final String FMI_COLS = "id, project_id, failure_mode_id, subject_kind, subject_session_id, "
            + "subject_trace_id, subject_span_id, verdict_id, source, confidence, occurred_at, created_at, "
            + "attributes";

    /**
     * Recompute the project's failure modes from freshly computed {@code clusters}: UPSERT each mode on
     * {@code (project_id, key)} (preserving its id, {@code status}, {@code created_at}, {@code first_seen_at}
     * on conflict), then replace that mode's instance set. One transaction — all-or-nothing.
     */
    @Transactional
    public void recompute(String projectId, List<FailureModeWithInstances> clusters) {
        for (FailureModeWithInstances c : clusters) {
            String fmId = upsertFailureMode(c.failureMode());
            replaceInstances(fmId, c.instances());
        }
    }

    /** UPSERT on {@code (project_id, key)}; returns the authoritative id (existing on conflict, else the new). */
    private String upsertFailureMode(FailureModeRow r) {
        return jdbc.sql("INSERT INTO failure_mode (" + FM_COLS + ") VALUES (:id, :pid, :key, :name, :description, "
                        + ":status, :severity, :surface, :signature, :centroidRef, :impactCount, "
                        + ":impactRate, :firstSeenVersionId, :regressedVersionId, :firstSeenAt::timestamptz, "
                        + ":lastSeenAt::timestamptz, :createdAt::timestamptz, :updatedAt::timestamptz, "
                        + ":attributes::jsonb) "
                        // Re-cluster refreshes the descriptive + window fields but PRESERVES id/status/created_at/
                        // first_seen_at (the curated lifecycle + stable identity).
                        + "ON CONFLICT (project_id, key) DO UPDATE SET name = EXCLUDED.name, "
                        + "description = EXCLUDED.description, severity = EXCLUDED.severity, "
                        + "surface = EXCLUDED.surface, signature = EXCLUDED.signature, "
                        + "centroid_ref = EXCLUDED.centroid_ref, impact_count = EXCLUDED.impact_count, "
                        + "last_seen_at = EXCLUDED.last_seen_at, updated_at = EXCLUDED.updated_at "
                        + "RETURNING id")
                .param("id", r.id())
                .param("pid", r.projectId())
                .param("key", r.key())
                .param("name", r.name())
                .param("description", r.description())
                .param("status", r.status())
                .param("severity", r.severity())
                .param("surface", r.surface())
                .param("signature", r.signature())
                .param("centroidRef", r.centroidRef())
                .param("impactCount", r.impactCount())
                .param("impactRate", r.impactRate())
                .param("firstSeenVersionId", r.firstSeenVersionId())
                .param("regressedVersionId", r.regressedVersionId())
                .param("firstSeenAt", r.firstSeenAt())
                .param("lastSeenAt", r.lastSeenAt())
                .param("createdAt", r.createdAt())
                .param("updatedAt", r.updatedAt())
                .param("attributes", r.attributes())
                .query(String.class)
                .single();
    }

    /** Replace a mode's instance set: delete its existing instances, then batch-insert the computed ones. */
    private void replaceInstances(String failureModeId, List<FailureModeInstanceRow> instances) {
        jdbc.sql("DELETE FROM failure_mode_instance WHERE failure_mode_id = :fmid")
                .param("fmid", failureModeId)
                .update();
        if (instances.isEmpty()) return;
        jdbcTemplate.batchUpdate(
                "INSERT INTO failure_mode_instance (" + FMI_COLS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::jsonb)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        FailureModeInstanceRow r = instances.get(i);
                        ps.setString(1, r.id());
                        ps.setString(2, r.projectId());
                        ps.setString(3, failureModeId);
                        ps.setString(4, r.subjectKind());
                        ps.setString(5, r.subjectSessionId());
                        ps.setString(6, r.subjectTraceId());
                        ps.setString(7, r.subjectSpanId());
                        ps.setString(8, r.verdictId());
                        ps.setString(9, r.source());
                        ps.setString(10, r.confidence());
                        ps.setString(11, r.occurredAt());
                        ps.setString(12, r.createdAt());
                        ps.setString(13, r.attributes());
                    }

                    @Override
                    public int getBatchSize() {
                        return instances.size();
                    }
                });
    }

    public List<FailureModeRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + FM_COLS + " FROM failure_mode WHERE project_id = :pid "
                        + "ORDER BY impact_count DESC, created_at DESC")
                .param("pid", projectId)
                .query((rs, n) -> mapFailureMode(rs))
                .list();
    }

    /** One failure mode by id, tenant-scoped (empty for a cross-project/unknown id). */
    public java.util.Optional<FailureModeRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + FM_COLS + " FROM failure_mode WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> mapFailureMode(rs))
                .optional();
    }

    /**
     * The failure mode a substrate subject belongs to: the newest {@code failure_mode_instance}
     * whose typed subject column matches {@code subjectId}. Bound once and OR'd across the three grain
     * columns so a caller need not know which grain the id is. Empty when the subject isn't clustered.
     */
    public java.util.Optional<String> findFailureModeIdForSubject(
            String projectId, String subjectKind, String subjectId) {
        return jdbc.sql("SELECT failure_mode_id FROM failure_mode_instance "
                        + "WHERE project_id = :pid AND subject_kind = :kind "
                        + "AND (subject_session_id = :sid OR subject_trace_id = :sid "
                        + "OR subject_span_id = :sid) ORDER BY created_at DESC LIMIT 1")
                .param("pid", projectId)
                .param("kind", subjectKind)
                .param("sid", subjectId)
                .query(String.class)
                .optional();
    }

    public List<FailureModeInstanceRow> listInstances(String failureModeId) {
        return jdbc.sql("SELECT " + FMI_COLS + " FROM failure_mode_instance WHERE failure_mode_id = :fmid "
                        + "ORDER BY created_at ASC, id ASC")
                .param("fmid", failureModeId)
                .query((rs, n) -> mapInstance(rs))
                .list();
    }

    /** Instances of {@code failureModeId} scoped to {@code projectId} — the read-surface tenant guard. */
    public List<FailureModeInstanceRow> listInstancesForProject(String projectId, String failureModeId) {
        return jdbc.sql("SELECT " + FMI_COLS + " FROM failure_mode_instance "
                        + "WHERE failure_mode_id = :fmid AND project_id = :pid ORDER BY created_at ASC, id ASC")
                .param("fmid", failureModeId)
                .param("pid", projectId)
                .query((rs, n) -> mapInstance(rs))
                .list();
    }

    /** Stored {@code impact_count} of one mode, tenant-scoped (0 for a cross-project/unknown id). */
    public int impactCountForProject(String projectId, String failureModeId) {
        return jdbc.sql("SELECT impact_count FROM failure_mode WHERE id = :fmid AND project_id = :pid")
                .param("fmid", failureModeId)
                .param("pid", projectId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private static FailureModeRow mapFailureMode(java.sql.ResultSet rs) throws SQLException {
        return new FailureModeRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("severity"),
                rs.getString("surface"),
                rs.getString("signature"),
                rs.getString("centroid_ref"),
                rs.getInt("impact_count"),
                (Double) rs.getObject("impact_rate"),
                rs.getString("first_seen_version_id"),
                rs.getString("regressed_version_id"),
                Timestamps.iso(rs, "first_seen_at"),
                Timestamps.iso(rs, "last_seen_at"),
                Timestamps.iso(rs, "created_at"),
                Timestamps.iso(rs, "updated_at"),
                rs.getString("attributes"));
    }

    private static FailureModeInstanceRow mapInstance(java.sql.ResultSet rs) throws SQLException {
        return new FailureModeInstanceRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("failure_mode_id"),
                rs.getString("subject_kind"),
                rs.getString("subject_session_id"),
                rs.getString("subject_trace_id"),
                rs.getString("subject_span_id"),
                rs.getString("verdict_id"),
                rs.getString("source"),
                rs.getString("confidence"),
                Timestamps.iso(rs, "occurred_at"),
                Timestamps.iso(rs, "created_at"),
                rs.getString("attributes"));
    }
}
