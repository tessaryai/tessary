// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only store for {@link AuditLog} rows. Insert + read only; there is no update
 * or delete path — the trail is immutable. Reads are available by project (the API-key audit surface) and
 * by typed subject (the general governance surface).
 */
@Repository
public class AuditLogRepository {

    private static final String COLS = "id, project_id, organization_id, subject_kind, subject_id, "
            + "principal_id, action, details, changes, attributes, occurred_at";

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditLog a) {
        jdbc.sql("""
            INSERT INTO audit_log (id, project_id, organization_id, subject_kind, subject_id, principal_id,
                                   action, details, changes, attributes, occurred_at)
            VALUES (:id, :pid, :org, :skind, :sid, :principal, :action, :details, :changes::jsonb,
                    :attributes::jsonb, :occurred)
            """)
                .param("id", a.id())
                .param("pid", a.projectId())
                .param("org", a.organizationId())
                .param("skind", a.subjectKind())
                .param("sid", a.subjectId())
                .param("principal", a.principalId())
                .param("action", a.action())
                .param("details", a.details())
                .param("changes", a.changes())
                .param("attributes", a.attributes())
                .param("occurred", a.occurredAt())
                .update();
    }

    public List<AuditLog> findByProject(String projectId, int limit) {
        return jdbc.sql("SELECT " + COLS
                        + " FROM audit_log WHERE project_id = :pid ORDER BY occurred_at DESC, id DESC LIMIT :lim")
                .param("pid", projectId)
                .param("lim", Math.max(1, limit))
                .query(AuditLogRepository::map)
                .list();
    }

    /** The trail for one typed subject (e.g. all actions on a given API key), newest first. */
    public List<AuditLog> findBySubject(String projectId, String subjectKind, String subjectId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM audit_log WHERE project_id = :pid AND subject_kind = :sk "
                        + "AND subject_id = :sid ORDER BY occurred_at DESC, id DESC LIMIT :lim")
                .param("pid", projectId)
                .param("sk", subjectKind)
                .param("sid", subjectId)
                .param("lim", Math.max(1, limit))
                .query(AuditLogRepository::map)
                .list();
    }

    private static AuditLog map(ResultSet rs, int n) throws SQLException {
        return new AuditLog(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("organization_id"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("principal_id"),
                rs.getString("action"),
                rs.getString("details"),
                rs.getString("changes"),
                rs.getString("attributes"),
                rs.getString("occurred_at"));
    }
}
