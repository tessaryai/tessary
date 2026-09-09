// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for the project-scoped classifier <em>definition</em>. */
@Repository
public class ClassifierRepository {

    private static final String COLS = "id, project_id, classifier_key, name, description, detector, "
            + "config_json, built_in, version, enabled, mode, created_at, updated_at";

    private final JdbcClient jdbc;

    public ClassifierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClassifierRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM classifier WHERE project_id = :pid ORDER BY classifier_key")
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public List<ClassifierRow> listEnabled(String projectId) {
        return jdbc.sql("SELECT " + COLS
                        + " FROM classifier WHERE project_id = :pid AND enabled = TRUE ORDER BY classifier_key")
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<ClassifierRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM classifier WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<ClassifierRow> findByKey(String projectId, String classifierKey) {
        return jdbc.sql("SELECT " + COLS + " FROM classifier WHERE project_id = :pid AND classifier_key = :key")
                .param("pid", projectId)
                .param("key", classifierKey)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void insert(ClassifierRow row) {
        jdbc.sql("""
            INSERT INTO classifier (id, project_id, classifier_key, name, description, detector, config_json,
                                built_in, version, enabled, mode, created_at, updated_at)
            VALUES (:id, :pid, :key, :name, :desc, :detector, :config,
                    :builtIn, :version, :enabled, :mode, :createdAt, :updatedAt)
            """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("key", row.classifierKey())
                .param("name", row.name())
                .param("desc", row.description())
                .param("detector", row.detector())
                .param("config", row.configJson())
                .param("builtIn", row.builtIn())
                .param("version", row.version())
                .param("enabled", row.enabled())
                .param("mode", row.mode())
                .param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt())
                .update();
    }

    /** Flip the enabled lifecycle flag. Returns rows affected (0 = not found). */
    public int setEnabled(String projectId, String id, boolean enabled) {
        return jdbc.sql("UPDATE classifier SET enabled = :en, updated_at = :now WHERE project_id = :pid AND id = :id")
                .param("en", enabled)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /**
     * Set the operating point: {@code discovery} (high recall) or {@code tracking} (high
     * precision). Tenant-controlled like {@code enabled}; does NOT bump {@code version}. Returns rows
     * affected (0 = not found).
     */
    public int setMode(String projectId, String id, String mode) {
        return jdbc.sql("UPDATE classifier SET mode = :mode, updated_at = :now WHERE project_id = :pid AND id = :id")
                .param("mode", mode)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /**
     * Overwrite just {@code config_json} — the tenant-tuning write ({@code ClassifierService#setTuning}),
     * deliberately narrower than {@link #updateDefinition}: that method re-syncs a built-in's whole
     * definition (including bumping {@code version}) from the catalog, and a per-project tuning edit is
     * neither a catalog change nor a version bump. Returns rows affected (0 = not found).
     */
    public int updateConfig(String projectId, String id, String configJson) {
        return jdbc.sql(
                        "UPDATE classifier SET config_json = :config, updated_at = :now WHERE project_id = :pid AND id = :id")
                .param("config", configJson)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /** Bump a built-in's definition (catalog re-seed) and its version, keyed by (project, classifier_key). */
    public void updateDefinition(ClassifierRow row) {
        jdbc.sql("""
            UPDATE classifier SET name = :name, description = :desc, detector = :detector,
                              config_json = :config, version = :version, updated_at = :now
            WHERE project_id = :pid AND classifier_key = :key
            """)
                .param("name", row.name())
                .param("desc", row.description())
                .param("detector", row.detector())
                .param("config", row.configJson())
                .param("version", row.version())
                .param("now", Instant.now().toString())
                .param("pid", row.projectId())
                .param("key", row.classifierKey())
                .update();
    }

    private static ClassifierRow map(ResultSet rs) throws SQLException {
        return new ClassifierRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("classifier_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("detector"),
                rs.getString("config_json"),
                rs.getBoolean("built_in"),
                rs.getInt("version"),
                rs.getBoolean("enabled"),
                rs.getString("mode"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
