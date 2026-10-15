// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CRUD for {@code project_model_setting} — a project's per-{@link ModelLane} model choices.
 *
 * <p>Reads return only the rows that exist, and a row only ever exists because someone chose it. A
 * lane with <b>no row</b> is the normal state, not an error and not a null-model: it means the lane
 * is resolved automatically from the org's configured providers. Working that out is
 * {@link ProjectModelSettings}' job, not this class's.
 */
@Repository
public class ProjectModelSettingRepository {

    private static final String COLS = "project_id, lane, model_key, created_at, updated_at";

    private final JdbcClient jdbc;

    public ProjectModelSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every lane this project has explicitly set. Lanes with no row are absent, not defaulted. */
    public List<ProjectModelSetting> findByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM project_model_setting WHERE project_id = :pid ORDER BY lane")
                .param("pid", projectId)
                .query(ProjectModelSettingRepository::map)
                .list();
    }

    /**
     * Set (or replace) one lane's choice. {@code created_at} survives an update so the row keeps its
     * original opt-in date.
     */
    public void upsert(String projectId, ModelLane lane, String modelKey) {
        String now = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO project_model_setting (project_id, lane, model_key, created_at, updated_at)
                VALUES (:pid, :lane, :model, :now, :now)
                ON CONFLICT (project_id, lane) DO UPDATE SET model_key = :model, updated_at = :now
                """)
                .param("pid", projectId)
                .param("lane", lane.wire())
                .param("model", modelKey)
                .param("now", now)
                .update();
    }

    /** Drop one lane's choice, returning it to the automatic answer. A no-op when there is no row. */
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
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
