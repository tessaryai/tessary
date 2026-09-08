// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Principal} — the unified identity table. Lookups by WorkOS id / email
 * serve the human sign-in path; agent/service principals are created directly with their kind. Profile
 * updates and WorkOS re-binding live here too.
 */
@Repository
public class PrincipalRepository {

    private final JdbcClient jdbc;

    public PrincipalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether this deployment has any human account at all — the first-run signal {@code
     * GET /auth/mode} reports and {@code GET /auth/login}'s degrade branch redirects on, so a
     * stranger meeting an empty instance lands on the signup screen rather than a sign-in form no
     * account can satisfy. Agent and service principals are excluded deliberately: neither can sign
     * in, so neither ends first run.
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
                                   parent_principal_id, status, attributes, created_at, last_seen_at)
            VALUES (:id, :wid, :email, :dn, :avatar, :kind, :parent, :status, :attributes::jsonb, :created,
                    :lastSeen)
            """)
                .param("id", p.id())
                .param("wid", p.workosUserId())
                .param("email", p.email())
                .param("dn", p.displayName())
                .param("avatar", p.avatarUrl())
                .param("kind", p.kind())
                .param("parent", p.parentPrincipalId())
                .param("status", p.status())
                .param("attributes", p.attributes())
                .param("created", p.createdAt())
                .param("lastSeen", p.lastSeenAt())
                .update();
    }

    /**
     * Insert a principal minted by {@code PasswordAuthProvider} (backend/tenancy's
     * {@code ai.tessary.evals.auth} package), alongside its bcrypt password hash. Mirrors
     * {@link #insert}'s column list plus {@code password_hash} — {@link #insert} itself is
     * untouched and still writes {@code NULL} into that column for every WorkOS-backed row.
     *
     * <p>The hash never lives on the {@link Principal} record (see that record's Javadoc, and
     * {@code 0013-principal-password-hash.sql}), so this is the one write path that sees it.
     */
    public void insertWithPassword(Principal p, String passwordHash) {
        jdbc.sql("""
            INSERT INTO principal (id, workos_user_id, email, display_name, avatar_url, kind,
                                   parent_principal_id, status, attributes, created_at, last_seen_at,
                                   password_hash)
            VALUES (:id, :wid, :email, :dn, :avatar, :kind, :parent, :status, :attributes::jsonb, :created,
                    :lastSeen, :hash)
            """)
                .param("id", p.id())
                .param("wid", p.workosUserId())
                .param("email", p.email())
                .param("dn", p.displayName())
                .param("avatar", p.avatarUrl())
                .param("kind", p.kind())
                .param("parent", p.parentPrincipalId())
                .param("status", p.status())
                .param("attributes", p.attributes())
                .param("created", p.createdAt())
                .param("lastSeen", p.lastSeenAt())
                .param("hash", passwordHash)
                .update();
    }

    /**
     * The three fields {@code PasswordAuthProvider} needs to verify a login, and nothing else — in
     * particular never the full {@link Principal}, so a credential check can't accidentally end up
     * serializing a hash onto the wire the way a hash living on {@link Principal} itself could.
     */
    public record Credential(String principalId, @Nullable String workosUserId, String passwordHash) {}

    /** Look up the stored credential for an email, for {@code PasswordAuthProvider.authenticateWithCredentials}.
     *  Empty when the email is unknown, or known but has no local password (a WorkOS-only row). */
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
     * Point an existing row at a new WorkOS user id (and refresh its profile). Used when a known email
     * re-appears under a different WorkOS id — e.g. after a WorkOS environment switch — so the row is
     * re-bound instead of a duplicate insert tripping the {@code UNIQUE(email)} constraint.
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
                rs.getString("status"),
                rs.getString("attributes"),
                rs.getString("created_at"),
                rs.getString("last_seen_at"));
    }
}
