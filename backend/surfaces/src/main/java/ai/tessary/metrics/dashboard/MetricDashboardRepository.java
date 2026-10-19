// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import static ai.tessary.db.schema.MetricDashboardColumns.CREATED_AT;
import static ai.tessary.db.schema.MetricDashboardColumns.CREATED_BY;
import static ai.tessary.db.schema.MetricDashboardColumns.DEFINITION;
import static ai.tessary.db.schema.MetricDashboardColumns.DESCRIPTION;
import static ai.tessary.db.schema.MetricDashboardColumns.ID;
import static ai.tessary.db.schema.MetricDashboardColumns.NAME;
import static ai.tessary.db.schema.MetricDashboardColumns.PROJECT_ID;
import static ai.tessary.db.schema.MetricDashboardColumns.TABLE;
import static ai.tessary.db.schema.MetricDashboardColumns.UPDATED_AT;

import ai.tessary.db.schema.ProjectColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code metric_dashboard} storage. Every statement is keyed by project, so a dashboard is never read or written across
 * tenants. A duplicate name surfaces as {@code DuplicateKeyException} from the unique index; the service names it.
 */
@Repository
public class MetricDashboardRepository {

    private static final String COLS = ID + ", " + PROJECT_ID + ", " + NAME + ", " + DESCRIPTION + ", " + DEFINITION
            + ", " + CREATED_BY + ", " + CREATED_AT + ", " + UPDATED_AT;
    private static final String SELECT = "SELECT " + COLS + " FROM " + TABLE + " WHERE " + PROJECT_ID + " = :pid";

    private final JdbcClient jdbc;

    public MetricDashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MetricDashboardRow> list(String projectId) {
        return jdbc.sql(SELECT + " ORDER BY " + NAME + ", " + ID)
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<MetricDashboardRow> find(String projectId, String id) {
        return jdbc.sql(SELECT + " AND " + ID + " = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Holds the project row until the transaction ends, so two creates count the project's dashboards one after the
     * other. NO KEY UPDATE, so a foreign-key check on the row, which every insert under the project makes, does not wait.
     */
    public void lockProject(String projectId) {
        jdbc.sql("SELECT " + ProjectColumns.ID + " FROM " + ProjectColumns.TABLE + " WHERE " + ProjectColumns.ID
                        + " = :pid FOR NO KEY UPDATE")
                .param("pid", projectId)
                .query(String.class)
                .optional();
    }

    public int count(String projectId) {
        return jdbc.sql("SELECT count(*) FROM " + TABLE + " WHERE " + PROJECT_ID + " = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .single();
    }

    public void insert(MetricDashboardRow row) {
        jdbc.sql("INSERT INTO " + TABLE + " (" + COLS + ") VALUES (:id, :pid, :name, :description,"
                        + " CAST(:definition AS jsonb), :createdBy, :createdAt, :updatedAt)")
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("name", row.name())
                .param("description", row.description())
                .param("definition", row.definitionJson())
                .param("createdBy", row.createdBy())
                .param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt())
                .update();
    }

    public void update(MetricDashboardRow row) {
        jdbc.sql("UPDATE " + TABLE + " SET " + NAME + " = :name, " + DESCRIPTION + " = :description, " + DEFINITION
                        + " = CAST(:definition AS jsonb), " + UPDATED_AT + " = :updatedAt WHERE " + PROJECT_ID
                        + " = :pid AND " + ID + " = :id")
                .param("name", row.name())
                .param("description", row.description())
                .param("definition", row.definitionJson())
                .param("updatedAt", row.updatedAt())
                .param("pid", row.projectId())
                .param("id", row.id())
                .update();
    }

    /** Writes the name, description and clock and leaves the definition as stored; empty when no such row. */
    public Optional<MetricDashboardRow> rename(
            String projectId, String id, String name, String description, String updatedAt) {
        return jdbc.sql("UPDATE " + TABLE + " SET " + NAME + " = :name, " + DESCRIPTION + " = :description, "
                        + UPDATED_AT + " = :updatedAt WHERE " + PROJECT_ID + " = :pid AND " + ID + " = :id RETURNING "
                        + COLS)
                .param("name", name)
                .param("description", description)
                .param("updatedAt", updatedAt)
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public boolean delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM " + TABLE + " WHERE " + PROJECT_ID + " = :pid AND " + ID + " = :id")
                        .param("pid", projectId)
                        .param("id", id)
                        .update()
                > 0;
    }

    private static MetricDashboardRow map(ResultSet rs) throws SQLException {
        return new MetricDashboardRow(
                rs.getString(ID),
                rs.getString(PROJECT_ID),
                rs.getString(NAME),
                rs.getString(DESCRIPTION),
                rs.getString(DEFINITION),
                rs.getString(CREATED_BY),
                rs.getString(CREATED_AT),
                rs.getString(UPDATED_AT));
    }
}
