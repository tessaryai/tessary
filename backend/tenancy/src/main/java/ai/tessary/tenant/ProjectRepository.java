// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Project> findById(String id) {
        return jdbc.sql("SELECT * FROM project WHERE id = :id")
                .param("id", id)
                .query(ProjectRepository::map)
                .optional();
    }

    public Optional<Project> findByOrgAndSlug(String orgId, String slug) {
        return jdbc.sql("SELECT * FROM project WHERE org_id = :oid AND slug = :slug")
                .param("oid", orgId)
                .param("slug", slug)
                .query(ProjectRepository::map)
                .optional();
    }

    public List<Project> findByOrg(String orgId) {
        // (created_at, id) tiebreak keeps "earliest project" deterministic when created_at ties.
        return jdbc.sql("SELECT * FROM project WHERE org_id = :oid ORDER BY created_at ASC, id ASC")
                .param("oid", orgId)
                .query(ProjectRepository::map)
                .list();
    }

    /** Archived and deleting projects are excluded: reconciling a deleting project races the purge worker. */
    public List<Project> findActive() {
        return jdbc.sql("SELECT * FROM project WHERE archived_at IS NULL AND deleting_at IS NULL"
                        + " ORDER BY created_at ASC, id ASC")
                .query(ProjectRepository::map)
                .list();
    }

    public Optional<Project> findDefaultForOrg(String orgId) {
        return jdbc.sql("SELECT * FROM project WHERE org_id = :oid AND is_default = TRUE")
                .param("oid", orgId)
                .query(ProjectRepository::map)
                .optional();
    }

    public long countByOrg(String orgId) {
        return jdbc.sql("SELECT COUNT(*) FROM project WHERE org_id = :oid")
                .param("oid", orgId)
                .query(Long.class)
                .single();
    }

    /** The telemetry heartbeat's {@code counts.projects}. */
    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM project").query(Long.class).single();
    }

    public void insert(Project p) {
        jdbc.sql("""
            INSERT INTO project (id, org_id, slug, name, description, created_at, archived_at, settings, is_default)
            VALUES (:id, :oid, :slug, :name, :desc, :created, :archived, :settings, :isDefault)
            """)
                .param("id", p.id())
                .param("oid", p.orgId())
                .param("slug", p.slug())
                .param("name", p.name())
                .param("desc", p.description())
                .param("created", p.createdAt())
                .param("archived", p.archivedAt())
                .param("settings", p.settings())
                .param("isDefault", p.isDefault())
                .update();
    }

    /** COALESCE-merge: a null field leaves the stored value, so a name-only PATCH never wipes the rest. */
    public void update(String id, String name, String description, String settings) {
        jdbc.sql("""
            UPDATE project
               SET name        = COALESCE(:name, name),
                   description = COALESCE(:desc, description),
                   settings    = COALESCE(:settings, settings)
             WHERE id = :id
            """)
                .param("name", name)
                .param("desc", description)
                .param("settings", settings)
                .param("id", id)
                .update();
    }

    /** Null un-archives. */
    public void setArchived(String id, String archivedAt) {
        jdbc.sql("UPDATE project SET archived_at = :archived WHERE id = :id")
                .param("archived", archivedAt)
                .param("id", id)
                .update();
    }

    /** Call before promoting a new default. */
    public void clearDefaultForOrg(String orgId) {
        jdbc.sql("UPDATE project SET is_default = FALSE WHERE org_id = :oid AND is_default = TRUE")
                .param("oid", orgId)
                .update();
    }

    public void setDefault(String id, boolean isDefault) {
        jdbc.sql("UPDATE project SET is_default = :d WHERE id = :id")
                .param("d", isDefault)
                .param("id", id)
                .update();
    }

    /** False if already marked, which makes a retried DELETE idempotent. */
    public boolean markDeleting(String id, String deletingAt) {
        return jdbc.sql("UPDATE project SET deleting_at = :at WHERE id = :id AND deleting_at IS NULL")
                        .param("at", deletingAt)
                        .param("id", id)
                        .update()
                > 0;
    }

    /**
     * Restores visibility after the purge worker gives up, so a human notices instead of the UI polling forever. Keys
     * stay revoked: a failed purge is not proof the delete was wrong.
     */
    public void clearDeleting(String id) {
        jdbc.sql("UPDATE project SET deleting_at = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * The purge worker's last statement, once the large tables are emptied; on a populated project the cascade takes
     * tens of minutes.
     */
    public boolean deleteById(String id) {
        return jdbc.sql("DELETE FROM project WHERE id = :id").param("id", id).update() > 0;
    }

    private static Project map(ResultSet rs, int n) throws SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("org_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("created_at"),
                rs.getString("archived_at"),
                rs.getString("settings"),
                rs.getBoolean("is_default"),
                rs.getString("deleting_at"));
    }
}
