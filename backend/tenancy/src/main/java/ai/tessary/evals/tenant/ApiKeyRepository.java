// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepository {

    private static final String COLS = "id, project_id, principal_id, name, token_prefix, token_hash, created_at, "
            + "last_used_at, revoked_at, scope, scopes, expires_at, attributes";

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiKey> findById(String id) {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE id = :id")
                .param("id", id)
                .query(ApiKeyRepository::map)
                .optional();
    }

    public Optional<ApiKey> findByPrefix(String prefix) {
        return jdbc.sql("SELECT " + COLS + " FROM api_key WHERE token_prefix = :p")
                .param("p", prefix)
                .query(ApiKeyRepository::map)
                .optional();
    }

    public List<ApiKey> findByProject(String projectId, boolean includeRevoked) {
        String sql = includeRevoked
                ? "SELECT " + COLS + " FROM api_key WHERE project_id = :pid ORDER BY created_at DESC"
                : "SELECT " + COLS
                        + " FROM api_key WHERE project_id = :pid AND revoked_at IS NULL ORDER BY created_at DESC";
        return jdbc.sql(sql)
                .param("pid", projectId)
                .query(ApiKeyRepository::map)
                .list();
    }

    public void insert(ApiKey t) {
        jdbc.sql("""
            INSERT INTO api_key (id, project_id, principal_id, name, token_prefix, token_hash,
                                 created_at, last_used_at, revoked_at, scope,
                                 scopes, expires_at, attributes)
            VALUES (:id, :pid, :uid, :name, :prefix, :hash, :created, :lastUsed, :revoked, :scope,
                    :scopes::jsonb, :expires, :attributes::jsonb)
            """)
                .param("id", t.id())
                .param("pid", t.projectId())
                .param("uid", t.principalId())
                .param("name", t.name())
                .param("prefix", t.tokenPrefix())
                .param("hash", t.tokenHash())
                .param("created", t.createdAt())
                .param("lastUsed", t.lastUsedAt())
                .param("revoked", t.revokedAt())
                .param("scope", t.scope())
                .param("scopes", t.scopes())
                .param("expires", t.expiresAt())
                .param("attributes", t.attributes())
                .update();
    }

    public void markLastUsed(String id, String when) {
        jdbc.sql("UPDATE api_key SET last_used_at = :when WHERE id = :id")
                .param("id", id)
                .param("when", when)
                .update();
    }

    public boolean revoke(String id, String when) {
        return jdbc.sql("UPDATE api_key SET revoked_at = :when WHERE id = :id AND revoked_at IS NULL")
                        .param("id", id)
                        .param("when", when)
                        .update()
                > 0;
    }

    /**
     * Revoke every live key of a project at once; returns how many were still live. One indexed UPDATE
     * ({@code api_key.project_id} is indexed), which is what lets the delete endpoint do this
     * synchronously rather than leaving it to the purge worker.
     *
     * <p>This is the whole access story for a project being deleted, and it is deliberately the part
     * that does NOT run in the background. Ingest, MCP tokens and device links all authenticate through
     * an {@code api_key} row, so revoking here means nothing new lands in a project on its way out —
     * whereas a revocation queued behind the purge would leave a window, minutes wide, in which the
     * deleted project is still accepting writes.
     */
    public int revokeAllForProject(String projectId, String when) {
        return jdbc.sql("UPDATE api_key SET revoked_at = :when WHERE project_id = :pid AND revoked_at IS NULL")
                .param("pid", projectId)
                .param("when", when)
                .update();
    }

    private static ApiKey map(ResultSet rs, int n) throws SQLException {
        return new ApiKey(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("principal_id"),
                rs.getString("name"),
                rs.getString("token_prefix"),
                rs.getString("token_hash"),
                rs.getString("created_at"),
                rs.getString("last_used_at"),
                rs.getString("revoked_at"),
                rs.getString("scope"),
                rs.getString("scopes"),
                rs.getString("expires_at"),
                rs.getString("attributes"));
    }
}
