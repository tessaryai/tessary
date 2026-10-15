// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CRUD for {@code project_model_setting}. No row is the normal state, meaning the lane resolves automatically;
 * {@link ProjectModelSettings} works that out.
 */
@Repository
public class ProjectModelSettingRepository {

    private static final String COLS =
            "project_id, lane, model_key, service_tier, reasoning_effort, created_at, updated_at";

    private final JdbcClient jdbc;

    public ProjectModelSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lanes with no row are absent, not defaulted. */
    public List<ProjectModelSetting> findByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM project_model_setting WHERE project_id = :pid ORDER BY lane")
                .param("pid", projectId)
                .query(ProjectModelSettingRepository::map)
                .list();
    }

    /** {@code created_at} survives an update so the row keeps its opt-in date. */
    public void upsert(
            String projectId, ModelLane lane, String modelKey, ServiceTier tier, @Nullable String reasoningEffort) {
        String now = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO project_model_setting
                    (project_id, lane, model_key, service_tier, reasoning_effort, created_at, updated_at)
                VALUES (:pid, :lane, :model, :tier, :effort, :now, :now)
                ON CONFLICT (project_id, lane) DO UPDATE
                   SET model_key = :model, service_tier = :tier, reasoning_effort = :effort, updated_at = :now
                """)
                .param("pid", projectId)
                .param("lane", lane.wire())
                .param("model", modelKey)
                .param("tier", tier.wireName())
                // Written even when null, so a stale effort never stays attached to a newly chosen model that may not
                // accept it.
                .param("effort", reasoningEffort)
                .param("now", now)
                .update();
    }

    /** A no-op when there is no row. */
    public void delete(String projectId, ModelLane lane) {
        jdbc.sql("DELETE FROM project_model_setting WHERE project_id = :pid AND lane = :lane")
                .param("pid", projectId)
                .param("lane", lane.wire())
                .update();
    }

    private static ProjectModelSetting map(ResultSet rs, int n) throws SQLException {
        return new ProjectModelSetting(
                rs.getString("project_id"),
                ModelLane.fromWire(rs.getString("lane")),
                rs.getString("model_key"),
                ServiceTier.fromWire(rs.getString("service_tier")),
                rs.getString("reasoning_effort"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
