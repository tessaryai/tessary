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

    /** Every live project, oldest first — the sweep set for the background workers that reconcile
     *  per-project state rather than claiming queued jobs. Archived projects are excluded: nothing
     *  should be detected, paged or reconciled for an organization that has been put away. Projects
     *  being deleted are excluded for a stronger reason — reconciling one races the purge worker,
     *  and every row it writes is work that has to be deleted again. */
    public List<Project> findActive() {
        return jdbc.sql("SELECT * FROM project WHERE archived_at IS NULL AND deleting_at IS NULL"
                        + " ORDER BY created_at ASC, id ASC")
                .query(ProjectRepository::map)
                .list();
    }

    /**
     * Projects marked for deletion, oldest mark first — the purge worker's recovery read.
     *
     * <p>The delete endpoint sets {@code deleting_at} and enqueues in two statements, so a backend that
     * dies between them leaves a project nobody is purging. This is how that project is found again; the
     * marker, not the job row, is the durable record that a delete was accepted.
     */
    public List<Project> findDeleting() {
        return jdbc.sql("SELECT * FROM project WHERE deleting_at IS NOT NULL ORDER BY deleting_at ASC, id ASC")
                .query(ProjectRepository::map)
                .list();
    }

    /** The organization's guaranteed default project, if one is set. */
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

    /** Every project on this install — the telemetry heartbeat's {@code project_count_bucket} input
     *  (devdocs/reference/telemetry-contract.md §1). Mirrors {@link OrganizationRepository#countAll}:
     *  the whole install, not just {@link #findActive}. */
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

    /**
     * Partial update: only overwrite a column when the caller actually supplies a value.
     * A {@code null} name/description/settings leaves the existing value untouched
     * (COALESCE-merge), so a name-only PATCH never wipes the description or settings blob.
     */
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

    /** Set or clear the soft-archive marker (null un-archives). */
    public void setArchived(String id, String archivedAt) {
        jdbc.sql("UPDATE project SET archived_at = :archived WHERE id = :id")
                .param("archived", archivedAt)
                .param("id", id)
                .update();
    }

    /** Clear the default flag on every project in an organization (call before promoting a new default). */
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

    /**
     * Stamp the one-way delete marker. Returns false if the project was already marked, which is what
     * makes a retried DELETE idempotent rather than a second accepted request.
     */
    public boolean markDeleting(String id, String deletingAt) {
        return jdbc.sql("UPDATE project SET deleting_at = :at WHERE id = :id AND deleting_at IS NULL")
                        .param("at", deletingAt)
                        .param("id", id)
                        .update()
                > 0;
    }

    /**
     * Undo {@link #markDeleting} after the purge worker has given up on a project ({@code
     * ProjectPurgeWorker#tick} dead-lettering past its retry budget). Restores the project to normal
     * visibility — it reappears in {@link #findByOrg}/{@link #findActive} and every project-scoped route
     * accepts it again — so a human notices instead of the UI polling a "deleting" project forever.
     *
     * <p>Deliberately does NOT touch {@code api_key}: revocation is the security-critical half of a
     * delete and a purge that merely failed to finish is not proof the delete request was wrong, so keys
     * stay revoked and the operator re-issues new ones once they've looked at {@code job.last_error} and
     * decided the project is safe to keep.
     */
    public void clearDeleting(String id) {
        jdbc.sql("UPDATE project SET deleting_at = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Drop the project row itself.
     *
     * <p>This is the purge worker's LAST statement, not a delete path of its own. By the time it runs,
     * the worker has already emptied the tables that hold the volume, so the {@code ON DELETE CASCADE}
     * fan-out left for this statement covers only small ones. Calling it on a populated project is what
     * used to take forty minutes inside an HTTP request.
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
