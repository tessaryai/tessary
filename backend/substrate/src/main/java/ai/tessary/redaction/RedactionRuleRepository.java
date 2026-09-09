// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** DB access for {@code pii_redaction_rule}, scoped by {@code project_id}. */
@Repository
public class RedactionRuleRepository {

    private final JdbcClient jdbc;

    public RedactionRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All rules for a project, ordered for deterministic application (sort_order, then created_at). */
    public List<RedactionRuleRow> findByProject(String projectId) {
        return jdbc.sql(
                        "SELECT * FROM pii_redaction_rule WHERE project_id = :pid ORDER BY sort_order ASC, created_at ASC")
                .param("pid", projectId)
                .query(RedactionRuleRepository::map)
                .list();
    }

    /** Only the enabled rules for a project, in application order — the set the write-path guard uses. */
    public List<RedactionRuleRow> findEnabledByProject(String projectId) {
        return jdbc.sql("SELECT * FROM pii_redaction_rule WHERE project_id = :pid AND enabled = TRUE "
                        + "ORDER BY sort_order ASC, created_at ASC")
                .param("pid", projectId)
                .query(RedactionRuleRepository::map)
                .list();
    }

    public Optional<RedactionRuleRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT * FROM pii_redaction_rule WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query(RedactionRuleRepository::map)
                .optional();
    }

    public void insert(RedactionRuleRow r) {
        jdbc.sql(
                        "INSERT INTO pii_redaction_rule "
                                + "(id, project_id, name, pattern, replacement, enabled, built_in, sort_order, created_at, updated_at) "
                                + "VALUES (:id, :pid, :name, :pattern, :replacement, :enabled, :builtIn, :sortOrder, :cat, :uat)")
                .param("id", r.id())
                .param("pid", r.projectId())
                .param("name", r.name())
                .param("pattern", r.pattern())
                .param("replacement", r.replacement())
                .param("enabled", r.enabled())
                .param("builtIn", r.builtIn())
                .param("sortOrder", r.sortOrder())
                .param("cat", r.createdAt())
                .param("uat", r.updatedAt())
                .update();
    }

    public boolean update(RedactionRuleRow r) {
        return jdbc.sql("UPDATE pii_redaction_rule SET "
                                + "name = :name, pattern = :pattern, replacement = :replacement, "
                                + "enabled = :enabled, sort_order = :sortOrder, updated_at = :uat "
                                + "WHERE id = :id AND project_id = :pid")
                        .param("name", r.name())
                        .param("pattern", r.pattern())
                        .param("replacement", r.replacement())
                        .param("enabled", r.enabled())
                        .param("sortOrder", r.sortOrder())
                        .param("uat", r.updatedAt())
                        .param("id", r.id())
                        .param("pid", r.projectId())
                        .update()
                > 0;
    }

    /** Toggle a single rule's enabled flag — the only mutation permitted on a built-in rule. */
    public boolean setEnabled(String projectId, String id, boolean enabled, String updatedAt) {
        return jdbc.sql("UPDATE pii_redaction_rule SET enabled = :enabled, updated_at = :uat "
                                + "WHERE id = :id AND project_id = :pid")
                        .param("enabled", enabled)
                        .param("uat", updatedAt)
                        .param("id", id)
                        .param("pid", projectId)
                        .update()
                > 0;
    }

    public boolean delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM pii_redaction_rule WHERE id = :id AND project_id = :pid")
                        .param("id", id)
                        .param("pid", projectId)
                        .update()
                > 0;
    }

    private static RedactionRuleRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RedactionRuleRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("pattern"),
                rs.getString("replacement"),
                rs.getBoolean("enabled"),
                rs.getBoolean("built_in"),
                rs.getInt("sort_order"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
