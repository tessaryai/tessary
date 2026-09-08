// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.gate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for registered pre-deploy checks. Idempotent insert: {@code ON CONFLICT DO NOTHING}
 * against the unique index {@code (project_id, classifier_id, surface)} makes a re-registration a
 * first-write-wins no-op. A {@code dismissed} row is preserved as-is on a re-registration
 * (insert-if-absent only, never an update), so a human's dismissal is never stomped.
 *
 * <p>There is one origin. The {@code feedback}-origin path went with the substrate's feedback table
 * (0083), and 0092 dropped the {@code source} discriminator it left behind along with the predicate it
 * put on this index.
 */
@Repository
public class PreDeployCheckRepository {

    private static final String COLS =
            "id, project_id, classifier_id, surface, failure_mode_id, intensity, status, created_at, updated_at";

    private final JdbcClient jdbc;

    public PreDeployCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // The conflict target is inferred from the index's columns. `ON CONFLICT ON CONSTRAINT` would not
    // resolve it: ux_pre_deploy_check_classifier is an index, not a table constraint.
    private static final String CONFLICT_CLASSIFIER = "(project_id, classifier_id, surface)";

    /**
     * Idempotent insert on the {@code (project_id, classifier_id, surface)} unique index. Returns
     * {@code true} when a new check row was written (first write wins).
     */
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

    /** Every registered check for the project, newest first (the list/dismiss read surface). */
    public List<PreDeployCheckRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM pre_deploy_check WHERE project_id = :pid "
                        + "ORDER BY created_at DESC")
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The DISTINCT active-check surfaces for the project — the read-side join the risk forecast unions
     * into a future PR's resolved surfaces. {@code dismissed} checks are excluded.
     */
    public List<String> activeSurfaces(String projectId) {
        return jdbc.sql("SELECT DISTINCT surface FROM pre_deploy_check "
                        + "WHERE project_id = :pid AND status = :active ORDER BY surface")
                .param("pid", projectId)
                .param("active", PreDeployCheckRow.Status.ACTIVE)
                .query(String.class)
                .list();
    }

    /** Flip a check's lifecycle status (active ↔ dismissed). Returns rows affected (0 = no such check). */
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
