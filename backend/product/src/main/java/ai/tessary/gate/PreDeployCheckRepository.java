// SPDX-License-Identifier: Apache-2.0
package ai.tessary.gate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for pre-deploy checks. Insert-if-absent on {@code (project_id, classifier_id, surface)}, never an update,
 * so a human's dismissal is never stomped by a re-registration.
 */
@Repository
public class PreDeployCheckRepository {

    private static final String COLS =
            "id, project_id, classifier_id, surface, failure_mode_id, intensity, status, created_at, updated_at";

    private final JdbcClient jdbc;

    public PreDeployCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Inferred from the index's columns: ON CONFLICT ON CONSTRAINT would not resolve an index that is not a table
    // constraint.
    private static final String CONFLICT_CLASSIFIER = "(project_id, classifier_id, surface)";

    /** Returns {@code true} when a new row was written. */
    public boolean insertClassifierIfAbsent(PreDeployCheckRow row) {
        return insert(row, CONFLICT_CLASSIFIER);
    }

    private boolean insert(PreDeployCheckRow row, String conflictTarget) {
        return jdbc.sql("INSERT INTO pre_deploy_check (id, project_id, classifier_id, "
                                + "surface, failure_mode_id, intensity, "
                                + "status, created_at, updated_at) "
                                + "VALUES (:id, :pid, :sid, :surface, :fm, "
                                + ":intensity, :status, :createdAt, :updatedAt) "
                                + "ON CONFLICT " + conflictTarget + " DO NOTHING")
                        .param("id", row.id())
                        .param("pid", row.projectId())
                        .param("sid", row.classifierId())
                        .param("surface", row.surface())
                        .param("fm", row.failureModeId())
                        .param("intensity", row.intensity())
                        .param("status", row.status())
                        .param("createdAt", row.createdAt())
                        .param("updatedAt", row.updatedAt())
                        .update()
                > 0;
    }

    public List<PreDeployCheckRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM pre_deploy_check WHERE project_id = :pid "
                        + "ORDER BY created_at DESC")
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Returns rows affected. */
    public int setStatus(String projectId, String id, String status, String updatedAt) {
        return jdbc.sql("UPDATE pre_deploy_check SET status = :status, updated_at = :updatedAt "
                        + "WHERE project_id = :pid AND id = :id")
                .param("status", status)
                .param("updatedAt", updatedAt)
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    private static PreDeployCheckRow map(ResultSet rs) throws SQLException {
        return new PreDeployCheckRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("classifier_id"),
                rs.getString("surface"),
                rs.getString("failure_mode_id"),
                rs.getString("intensity"),
                rs.getString("status"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
