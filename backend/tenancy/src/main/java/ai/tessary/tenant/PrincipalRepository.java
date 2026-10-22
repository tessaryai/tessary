// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PrincipalRepository {

    private final JdbcClient jdbc;

    public PrincipalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The first-run signal for {@code GET /auth/mode} and the login degrade branch. Agent and service principals are
     * excluded: neither can sign in.
     */
    public boolean anyHumanExists() {
        return jdbc.sql("SELECT 1 FROM principal WHERE kind = :kind LIMIT 1")
                .param("kind", Principal.Kind.HUMAN)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Optional<Principal> findById(String id) {
        return jdbc.sql("SELECT * FROM principal WHERE id = :id")
                .param("id", id)
                .query(PrincipalRepository::map)
                .optional();
    }

    /** Ids that name no principal are skipped. */
    public List<Principal> findByIds(Set<String> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM principal WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(String[]::new))
                .query(PrincipalRepository::map)
                .list();
    }

    public Optional<Principal> findByWorkosId(String workosUserId) {
        return jdbc.sql("SELECT * FROM principal WHERE workos_user_id = :wid")
                .param("wid", workosUserId)
                .query(PrincipalRepository::map)
                .optional();
    }

    public Optional<Principal> findByEmail(String email) {
        return jdbc.sql("SELECT * FROM principal WHERE email = :email")
                .param("email", email)
                .query(PrincipalRepository::map)
                .optional();
    }

    public void insert(Principal p) {
        jdbc.sql("""
            INSERT INTO principal (id, workos_user_id, email, display_name, avatar_url, kind,
                                   parent_principal_id, attributes, created_at, last_seen_at)
            VALUES (:id, :wid, :email, :dn, :avatar, :kind, :parent, :attributes::jsonb, :created,
                    :lastSeen)
            """)
                .param("id", p.id())
                .param("wid", p.workosUserId())
                .param("email", p.email())
                .param("dn", p.displayName())
                .param("avatar", p.avatarUrl())
                .param("kind", p.kind())
                .param("parent", p.parentPrincipalId())
                .param("attributes", p.attributes())
                .param("created", p.createdAt())
                .param("lastSeen", p.lastSeenAt())
                .update();
    }

    /**
     * {@link #insert} plus {@code password_hash}. The hash never lives on {@link Principal}, so this is the one write
     * path that sees it.
     */
    public void insertWithPassword(Principal p, String passwordHash) {
        jdbc.sql("""
            INSERT INTO principal (id, workos_user_id, email, display_name, avatar_url, kind,
                                   parent_principal_id, attributes, created_at, last_seen_at,
                                   password_hash)
            VALUES (:id, :wid, :email, :dn, :avatar, :kind, :parent, :attributes::jsonb, :created,
                    :lastSeen, :hash)
            """)
                .param("id", p.id())
                .param("wid", p.workosUserId())
                .param("email", p.email())
                .param("dn", p.displayName())
                .param("avatar", p.avatarUrl())
                .param("kind", p.kind())
                .param("parent", p.parentPrincipalId())
                .param("attributes", p.attributes())
                .param("created", p.createdAt())
                .param("lastSeen", p.lastSeenAt())
                .param("hash", passwordHash)
                .update();
    }

    /** Never the full {@link Principal}, so a credential check cannot serialize a hash onto the wire. */
    public record Credential(String principalId, @Nullable String workosUserId, String passwordHash) {}

    /** Empty when the email is unknown or has no local password (a WorkOS-only row). */
    public Optional<Credential> findCredentialByEmail(String email) {
        return jdbc.sql("SELECT id, workos_user_id, password_hash FROM principal WHERE email = :email AND "
                        + "password_hash IS NOT NULL")
                .param("email", email)
                .query((rs, n) -> new Credential(
                        rs.getString("id"), rs.getString("workos_user_id"), rs.getString("password_hash")))
                .optional();
    }

    public void updateProfile(String id, String displayName, String avatarUrl, String lastSeenAt) {
        jdbc.sql("""
            UPDATE principal
               SET display_name = :dn, avatar_url = :avatar, last_seen_at = :lastSeen
             WHERE id = :id
            """)
                .param("id", id)
                .param("dn", displayName)
                .param("avatar", avatarUrl)
                .param("lastSeen", lastSeenAt)
                .update();
    }

    /**
     * For a known email under a new WorkOS id (e.g. an environment switch), so the row is re-bound instead of tripping
     * {@code UNIQUE(email)}.
     */
    public void rebindWorkosId(
            String id, String workosUserId, String displayName, String avatarUrl, String lastSeenAt) {
        jdbc.sql("""
            UPDATE principal
               SET workos_user_id = :wid, display_name = :dn, avatar_url = :avatar, last_seen_at = :lastSeen
             WHERE id = :id
            """)
                .param("id", id)
                .param("wid", workosUserId)
                .param("dn", displayName)
                .param("avatar", avatarUrl)
                .param("lastSeen", lastSeenAt)
                .update();
    }

    private static Principal map(ResultSet rs, int n) throws SQLException {
        return new Principal(
                rs.getString("id"),
                rs.getString("workos_user_id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getString("kind"),
                rs.getString("parent_principal_id"),
                rs.getString("attributes"),
                rs.getString("created_at"),
                rs.getString("last_seen_at"));
    }
}
