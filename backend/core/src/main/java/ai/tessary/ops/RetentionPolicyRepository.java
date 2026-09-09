// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ops;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for retention policies (P6.8a). {@link #upsert} is keyed on {@code (project_id,
 * data_class)} so setting a class's TTL is idempotent (one policy per class).
 */
@Repository
public class RetentionPolicyRepository {

    private static final String COLS = "id, project_id, data_class, ttl_days, cold_after_days, created_at, attributes";

    private final JdbcClient jdbc;

    public RetentionPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(RetentionPolicyRow row) {
        jdbc.sql("""
                        INSERT INTO retention_policy (id, project_id, data_class, ttl_days, cold_after_days,
                                                      created_at, attributes)
                        VALUES (:id, :pid, :dataClass, :ttlDays, :coldAfterDays, :createdAt, :attributes::jsonb)
                        ON CONFLICT (project_id, data_class) DO UPDATE
                          SET ttl_days = EXCLUDED.ttl_days,
                              cold_after_days = EXCLUDED.cold_after_days
                        """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("dataClass", row.dataClass())
                .param("ttlDays", row.ttlDays())
                .param("coldAfterDays", row.coldAfterDays())
                .param("createdAt", row.createdAt())
                .param("attributes", row.attributes())
                .update();
    }

    /** Removes a project's override for one data class, so the platform default applies again. */
    public boolean delete(String projectId, String dataClass) {
        return jdbc.sql("DELETE FROM retention_policy WHERE project_id = :pid AND data_class = :dataClass")
                        .param("pid", projectId)
                        .param("dataClass", dataClass)
                        .update()
                > 0;
    }

    public List<RetentionPolicyRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM retention_policy WHERE project_id = :pid ORDER BY data_class")
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    private static RetentionPolicyRow map(ResultSet rs) throws SQLException {
        return new RetentionPolicyRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("data_class"),
                rs.getInt("ttl_days"),
                (Integer) rs.getObject("cold_after_days"),
                rs.getString("created_at"),
                rs.getString("attributes"));
    }
}
