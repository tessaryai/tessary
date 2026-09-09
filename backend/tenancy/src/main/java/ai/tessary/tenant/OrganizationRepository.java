// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {

    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Organization> findById(String id) {
        return jdbc.sql("SELECT * FROM organization WHERE id = :id")
                .param("id", id)
                .query(OrganizationRepository::map)
                .optional();
    }

    public Optional<Organization> findBySlug(String slug) {
        return jdbc.sql("SELECT * FROM organization WHERE slug = :slug")
                .param("slug", slug)
                .query(OrganizationRepository::map)
                .optional();
    }

    public Optional<Organization> findByWorkosOrgId(String workosOrgId) {
        return jdbc.sql("SELECT * FROM organization WHERE workos_org_id = :wid")
                .param("wid", workosOrgId)
                .query(OrganizationRepository::map)
                .optional();
    }

    /** The install's first organization: the one whose settings govern instance-wide policy. */
    public Optional<Organization> findOldest() {
        return jdbc.sql("SELECT * FROM organization ORDER BY created_at ASC, id ASC LIMIT 1")
                .query(OrganizationRepository::map)
                .optional();
    }

    /** Every organization on this install, regardless of owner — the telemetry heartbeat's
     *  {@code org_count_bucket} input (devdocs/reference/telemetry-contract.md §1). No archived-row
     *  exclusion: unlike {@code ProjectRepository#findActive}, the ping counts what exists, not what
     *  is currently in active use. */
    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM organization").query(Long.class).single();
    }

    /**
     * Serialize org creation per owner for the rest of the CURRENT transaction. The
     * owned-org cap ({@code OrgCreationLimit}) is a check-then-insert; without this, two concurrent
     * creates by the same user both pass the count before either inserts and the cap is exceeded.
     * {@code pg_advisory_xact_lock} blocks a second caller with the same key until the first
     * transaction commits or rolls back, and needs no schema change — {@code hashtext} folds the
     * ULID to the {@code bigint} key the lock takes. Must be called INSIDE the transaction that
     * does the count and the insert, or it serializes nothing.
     */
    /**
     * How many organizations this user OWNS — one query, not a membership scan plus a lookup per
     * org. Mirrors {@link #findByUserId}'s own filters so the two never disagree
     * about which orgs count. Meant to run under {@link #lockOrgCreationFor} so the answer is exact.
     */
    public long countOwnedBy(String userId) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM org_membership m
              JOIN organization o ON o.id = m.org_id
             WHERE m.principal_id = :pid AND m.role = :role
            """)
                .param("pid", userId)
                .param("role", OrgMembership.OWNER)
                .query(Long.class)
                .single();
    }

    public void lockOrgCreationFor(String ownerUserId) {
        // Same idiom as CaseRepository#lockProject — one advisory-lock shape in the codebase.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:uid))")
                .param("uid", ownerUserId)
                .query()
                .singleValue();
    }

    public List<Organization> findByUserId(String userId) {
        return jdbc.sql("""
            SELECT o.* FROM organization o
            JOIN org_membership m ON m.org_id = o.id
            WHERE m.principal_id = :uid
            ORDER BY o.created_at ASC
            """)
                .param("uid", userId)
                .query(OrganizationRepository::map)
                .list();
    }

    public void insert(Organization o) {
        jdbc.sql("""
            INSERT INTO organization (id, workos_org_id, slug, name, created_at, archived_at, settings)
            VALUES (:id, :wid, :slug, :name, :created, :archived, :settings)
            """)
                .param("id", o.id())
                .param("wid", o.workosOrgId())
                .param("slug", o.slug())
                .param("name", o.name())
                .param("created", o.createdAt())
                .param("archived", o.archivedAt())
                .param("settings", o.settings())
                .update();
    }

    /**
     * Partial update: only overwrite a column when the caller actually supplies a value.
     * A {@code null} name/settings leaves the existing value untouched (COALESCE-merge), so a
     * name-only PATCH never wipes the settings blob and vice-versa.
     */
    public void update(String id, String name, String settings) {
        jdbc.sql("""
            UPDATE organization
               SET name     = COALESCE(:name, name),
                   settings = COALESCE(:settings, settings)
             WHERE id = :id
            """)
                .param("name", name)
                .param("settings", settings)
                .param("id", id)
                .update();
    }

    /** Set or clear the soft-archive marker (null un-archives). */
    public void setArchived(String id, String archivedAt) {
        jdbc.sql("UPDATE organization SET archived_at = :archived WHERE id = :id")
                .param("archived", archivedAt)
                .param("id", id)
                .update();
    }

    /** Hard delete — cascades to project/membership/api_key/... via ON DELETE CASCADE. */
    public boolean deleteById(String id) {
        return jdbc.sql("DELETE FROM organization WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    private static Organization map(ResultSet rs, int n) throws SQLException {
        return new Organization(
                rs.getString("id"),
                rs.getString("workos_org_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("created_at"),
                rs.getString("archived_at"),
                rs.getString("settings"));
    }
}
