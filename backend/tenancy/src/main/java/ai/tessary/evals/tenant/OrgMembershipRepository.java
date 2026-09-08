// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrgMembershipRepository {

    private final JdbcClient jdbc;

    public OrgMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrgMembership> find(String orgId, String principalId) {
        return jdbc.sql("""
            SELECT * FROM org_membership WHERE org_id = :oid AND principal_id = :pid
            """)
                .param("oid", orgId)
                .param("pid", principalId)
                .query(OrgMembershipRepository::map)
                .optional();
    }

    public List<OrgMembership> findByOrg(String orgId) {
        return jdbc.sql("SELECT * FROM org_membership WHERE org_id = :oid")
                .param("oid", orgId)
                .query(OrgMembershipRepository::map)
                .list();
    }

    public record MemberRow(
            String orgId,
            String principalId,
            String role,
            String createdAt,
            String email,
            String displayName,
            String avatarUrl) {}

    public List<MemberRow> findByOrgWithUsers(String orgId) {
        return jdbc.sql("""
            SELECT m.org_id, m.principal_id, m.role, m.created_at,
                   u.email, u.display_name, u.avatar_url
              FROM org_membership m
              JOIN principal u ON u.id = m.principal_id
             WHERE m.org_id = :oid
             ORDER BY m.created_at
            """)
                .param("oid", orgId)
                .query((rs, n) -> new MemberRow(
                        rs.getString("org_id"),
                        rs.getString("principal_id"),
                        rs.getString("role"),
                        rs.getString("created_at"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("avatar_url")))
                .list();
    }

    public long countOwners(String orgId) {
        return jdbc.sql("SELECT COUNT(*) FROM org_membership WHERE org_id = :oid AND role = 'owner'")
                .param("oid", orgId)
                .query(Long.class)
                .single();
    }

    public List<OrgMembership> findByUser(String principalId) {
        return jdbc.sql("SELECT * FROM org_membership WHERE principal_id = :pid")
                .param("pid", principalId)
                .query(OrgMembershipRepository::map)
                .list();
    }

    public void insert(OrgMembership m) {
        jdbc.sql("""
            INSERT INTO org_membership (org_id, principal_id, role, scopes, attributes, created_at)
            VALUES (:oid, :pid, :role, :scopes::jsonb, :attributes::jsonb, :created)
            ON CONFLICT (org_id, principal_id) DO NOTHING
            """)
                .param("oid", m.orgId())
                .param("pid", m.principalId())
                .param("role", m.role())
                .param("scopes", m.scopes())
                .param("attributes", m.attributes())
                .param("created", m.createdAt())
                .update();
    }

    public void updateRole(String orgId, String principalId, String role) {
        jdbc.sql("UPDATE org_membership SET role = :role WHERE org_id = :oid AND principal_id = :pid")
                .param("role", role)
                .param("oid", orgId)
                .param("pid", principalId)
                .update();
    }

    public void delete(String orgId, String principalId) {
        jdbc.sql("DELETE FROM org_membership WHERE org_id = :oid AND principal_id = :pid")
                .param("oid", orgId)
                .param("pid", principalId)
                .update();
    }

    private static OrgMembership map(ResultSet rs, int n) throws SQLException {
        return new OrgMembership(
                rs.getString("org_id"),
                rs.getString("principal_id"),
                rs.getString("role"),
                rs.getString("scopes"),
                rs.getString("attributes"),
                rs.getString("created_at"));
    }
}
