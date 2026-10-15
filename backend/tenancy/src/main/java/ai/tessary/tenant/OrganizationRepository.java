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

    /** The install's first organization, whose settings govern instance-wide policy. */
    public Optional<Organization> findOldest() {
        return jdbc.sql("SELECT * FROM organization ORDER BY created_at ASC, id ASC LIMIT 1")
                .query(OrganizationRepository::map)
                .optional();
    }

    /**
     * Serialize org creation per owner for the rest of the current transaction: the owned-org cap is check-then-insert,
     * so two concurrent creates would both pass the count. Must run inside the transaction that counts and inserts.
     */
    /**
     * Mirrors {@link #findByUserId}'s filters so the two never disagree. Exact only under {@link #lockOrgCreationFor}.
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
     * A {@code null} name or settings leaves the column untouched, so a name-only PATCH never wipes the settings blob.
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
