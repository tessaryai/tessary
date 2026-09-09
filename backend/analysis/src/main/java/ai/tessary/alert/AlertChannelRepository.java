// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Per-project alert-channel config storage. Mirrors {@code git/GitIntegrationRepository},
 * but with many rows per project. The fan-out read is {@link #listEnabledByProject(String)}; CRUD is by
 * {@code (projectId, id)} so a channel can never be touched cross-tenant.
 */
@Repository
public class AlertChannelRepository {

    private static final String COLS =
            "id, project_id, kind, name, enabled, config_enc, attributes, created_at, updated_at";

    private final JdbcClient jdbc;

    public AlertChannelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlertChannelRow> listByProject(String projectId) {
        return jdbc.sql(String.format(
                        Locale.ROOT, "SELECT %s FROM alert_channel WHERE project_id = :pid ORDER BY created_at", COLS))
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** The channels a fired alert fans out to: enabled rows for the project. */
    public List<AlertChannelRow> listEnabledByProject(String projectId) {
        return jdbc.sql(String.format(
                        Locale.ROOT,
                        "SELECT %s FROM alert_channel WHERE project_id = :pid AND enabled = TRUE ORDER BY created_at",
                        COLS))
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<AlertChannelRow> find(String projectId, String id) {
        return jdbc.sql(String.format(
                        Locale.ROOT, "SELECT %s FROM alert_channel WHERE project_id = :pid AND id = :id", COLS))
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void insert(AlertChannelRow row) {
        jdbc.sql("""
            INSERT INTO alert_channel (id, project_id, kind, name, enabled, config_enc, attributes,
                                       created_at, updated_at)
            VALUES (:id, :pid, :kind, :name, :enabled, :config, CAST(:attributes AS JSONB), :created, :updated)
            """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("kind", row.kind())
                .param("name", row.name())
                .param("enabled", row.enabled())
                .param("config", row.configEnc())
                .param("attributes", row.attributes())
                .param("created", row.createdAt())
                .param("updated", row.updatedAt())
                .update();
    }

    public void update(AlertChannelRow row) {
        jdbc.sql("""
            UPDATE alert_channel
            SET name = :name, enabled = :enabled, config_enc = :config,
                attributes = CAST(:attributes AS JSONB), updated_at = :updated
            WHERE project_id = :pid AND id = :id
            """)
                .param("name", row.name())
                .param("enabled", row.enabled())
                .param("config", row.configEnc())
                .param("attributes", row.attributes())
                .param("updated", row.updatedAt())
                .param("pid", row.projectId())
                .param("id", row.id())
                .update();
    }

    public boolean delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM alert_channel WHERE project_id = :pid AND id = :id")
                        .param("pid", projectId)
                        .param("id", id)
                        .update()
                > 0;
    }

    private static AlertChannelRow map(ResultSet rs) throws SQLException {
        return new AlertChannelRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getString("config_enc"),
                rs.getString("attributes"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
