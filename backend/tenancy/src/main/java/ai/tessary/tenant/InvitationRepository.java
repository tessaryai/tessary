// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvitationRepository {

    private final JdbcClient jdbc;

    public InvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Create a pending invite, or re-arm an existing (org, email) row back to pending. */
    public void upsertPending(OrgInvitation inv) {
        jdbc.sql("""
            INSERT INTO org_invitation
                (id, org_id, email, role, invited_by, workos_invitation_id, state, created_at)
            VALUES (:id, :oid, :email, :role, :invitedBy, :workosId, 'pending', :created)
            ON CONFLICT (org_id, email) DO UPDATE SET
                role = EXCLUDED.role,
                invited_by = EXCLUDED.invited_by,
                workos_invitation_id = EXCLUDED.workos_invitation_id,
                state = 'pending',
                created_at = EXCLUDED.created_at,
                accepted_at = NULL,
                revoked_at = NULL
            """)
                .param("id", inv.id())
                .param("oid", inv.orgId())
                .param("email", inv.email())
                .param("role", inv.role())
                .param("invitedBy", inv.invitedBy())
                .param("workosId", inv.workosInvitationId())
                .param("created", inv.createdAt())
                .update();
    }

    public List<OrgInvitation> findPendingByOrg(String orgId) {
        return jdbc.sql("""
            SELECT * FROM org_invitation
             WHERE org_id = :oid AND state = 'pending'
             ORDER BY created_at
            """)
                .param("oid", orgId)
                .query(InvitationRepository::map)
                .list();
    }

    public List<OrgInvitation> findPendingByEmail(String email) {
        return jdbc.sql("""
            SELECT * FROM org_invitation
             WHERE email = :email AND state = 'pending'
            """)
                .param("email", email)
                .query(InvitationRepository::map)
                .list();
    }

    public Optional<OrgInvitation> findById(String id) {
        return jdbc.sql("SELECT * FROM org_invitation WHERE id = :id")
                .param("id", id)
                .query(InvitationRepository::map)
                .optional();
    }

    public void markAccepted(String id, String now) {
        jdbc.sql("UPDATE org_invitation SET state = 'accepted', accepted_at = :now WHERE id = :id")
                .param("now", now)
                .param("id", id)
                .update();
    }

    public void markRevoked(String id, String now) {
        jdbc.sql("UPDATE org_invitation SET state = 'revoked', revoked_at = :now WHERE id = :id")
                .param("now", now)
                .param("id", id)
                .update();
    }

    private static OrgInvitation map(ResultSet rs, int n) throws SQLException {
        return new OrgInvitation(
                rs.getString("id"),
                rs.getString("org_id"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getString("invited_by"),
                rs.getString("workos_invitation_id"),
                rs.getString("state"),
                rs.getString("created_at"),
                rs.getString("accepted_at"),
                rs.getString("revoked_at"));
    }
}
