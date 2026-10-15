// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClassifierRepository {

    private static final String COLS = "id, project_id, classifier_key, name, description, detector, "
            + "config_json, built_in, version, enabled, mode, created_at, updated_at, call_site_ids";

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

    public void insert(ClassifierRow row) {
        jdbc.sql("""
            INSERT INTO classifier (id, project_id, classifier_key, name, description, detector, config_json,
                                built_in, version, enabled, mode, created_at, updated_at, call_site_ids)
            VALUES (:id, :pid, :key, :name, :desc, :detector, :config,
                    :builtIn, :version, :enabled, :mode, :createdAt, :updatedAt, :callSiteIds)
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
                .param("callSiteIds", toArray(row.callSiteIds()))
                .update();
    }

    /** Returns rows affected (0 = not found). */
    public int setEnabled(String projectId, String id, boolean enabled) {
        return jdbc.sql("UPDATE classifier SET enabled = :en, updated_at = :now WHERE project_id = :pid AND id = :id")
                .param("en", enabled)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /** {@code null} for every call site. Does NOT bump {@code version}. Returns rows affected (0 = not found). */
    public int setCallSiteIds(String projectId, String id, @Nullable List<String> callSiteIds) {
        return jdbc.sql("UPDATE classifier SET call_site_ids = :ids, updated_at = :now"
                        + " WHERE project_id = :pid AND id = :id")
                .param("ids", toArray(callSiteIds))
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /**
     * Narrower than {@link #updateDefinition}, which re-syncs a built-in from the catalog and bumps {@code version}.
     * Returns rows affected (0 = not found).
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

    public Optional<ClassifierPause> findPause(String projectId, String id) {
        return jdbc.sql("SELECT paused_reason, paused_at FROM classifier"
                        + " WHERE project_id = :pid AND id = :id AND paused_reason IS NOT NULL")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) ->
                        new ClassifierPause(rs.getString("paused_reason"), Instant.parse(rs.getString("paused_at"))))
                .optional();
    }

    /** Restamps {@code paused_at}. */
    public int pause(String projectId, String id, String reason, Instant at) {
        return jdbc.sql("UPDATE classifier SET paused_reason = :reason, paused_at = :at"
                        + " WHERE project_id = :pid AND id = :id")
                .param("reason", reason)
                .param("at", at.toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /** Returns rows affected (0 = it was not paused). */
    public int unpause(String projectId, String id) {
        return jdbc.sql("UPDATE classifier SET paused_reason = NULL, paused_at = NULL"
                        + " WHERE project_id = :pid AND id = :id AND paused_reason IS NOT NULL")
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /** Keyed by (project, classifier_key). */
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
                rs.getString("updated_at"),
                callSiteIds(rs.getArray("call_site_ids")));
    }

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null = every call site; empty is refused
    private static String @Nullable [] toArray(@Nullable List<String> callSiteIds) {
        return callSiteIds == null ? null : callSiteIds.toArray(String[]::new);
    }

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null = every call site; empty is refused
    private static @Nullable List<String> callSiteIds(@Nullable Array array) throws SQLException {
        return array == null ? null : List.of((String[]) array.getArray());
    }
}
