// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeviceLinkRepository {

    private static final String COLS = "id, device_code_prefix, device_code_hash, user_code, status, client_label, "
            + "org_id, project_id, user_id, mcp_token_id, created_at, expires_at, last_polled_at, poll_count";

    private final JdbcClient jdbc;

    public DeviceLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DeviceLink r) {
        jdbc.sql("""
            INSERT INTO device_link (id, device_code_prefix, device_code_hash, user_code, status,
                                     client_label, org_id, project_id, user_id, mcp_token_id,
                                     created_at, expires_at, last_polled_at, poll_count)
            VALUES (:id, :prefix, :hash, :userCode, :status, :clientLabel, :orgId, :projectId,
                    :userId, :mcpTokenId, :createdAt, :expiresAt, :lastPolledAt, :pollCount)
            """)
                .param("id", r.id())
                .param("prefix", r.deviceCodePrefix())
                .param("hash", r.deviceCodeHash())
                .param("userCode", r.userCode())
                .param("status", r.status())
                .param("clientLabel", r.clientLabel())
                .param("orgId", r.orgId())
                .param("projectId", r.projectId())
                .param("userId", r.userId())
                .param("mcpTokenId", r.mcpTokenId())
                .param("createdAt", r.createdAt())
                .param("expiresAt", r.expiresAt())
                .param("lastPolledAt", r.lastPolledAt())
                .param("pollCount", r.pollCount())
                .update();
    }

    public Optional<DeviceLink> findByPrefix(String prefix) {
        return jdbc.sql("SELECT " + COLS + " FROM device_link WHERE device_code_prefix = :p")
                .param("p", prefix)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<DeviceLink> findByUserCode(String userCode) {
        return jdbc.sql("SELECT " + COLS + " FROM device_link WHERE user_code = :c")
                .param("c", userCode)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void markStatus(String id, String status) {
        jdbc.sql("UPDATE device_link SET status = :s WHERE id = :id")
                .param("s", status)
                .param("id", id)
                .update();
    }

    /** Transition {@code from → to} only if the row is still in {@code from}. Returns rows changed (0 or 1). */
    public int transitionIf(String id, String from, String to) {
        return jdbc.sql("UPDATE device_link SET status = :to WHERE id = :id AND status = :from")
                .param("to", to)
                .param("from", from)
                .param("id", id)
                .update();
    }

    /** Confirm only while still pending (closes the confirm/deny race). Returns rows changed. */
    public int markConfirmed(String id, String orgId, String projectId, String userId) {
        return jdbc.sql("""
            UPDATE device_link SET status = :s, org_id = :orgId, project_id = :projectId, user_id = :userId
            WHERE id = :id AND status = :from
            """)
                .param("s", DeviceLink.CONFIRMED)
                .param("from", DeviceLink.PENDING)
                .param("orgId", orgId)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("id", id)
                .update();
    }

    /**
     * Atomically claim a confirmed link (confirmed → claimed). Returns 1 to the
     * single winner of a concurrent-poll race, 0 to everyone else — so the token
     * is minted exactly once. The token id is attached separately via {@link #setToken}.
     */
    public int beginClaim(String id) {
        return jdbc.sql("UPDATE device_link SET status = :to WHERE id = :id AND status = :from")
                .param("to", DeviceLink.CLAIMED)
                .param("from", DeviceLink.CONFIRMED)
                .param("id", id)
                .update();
    }

    public void setToken(String id, String mcpTokenId) {
        jdbc.sql("UPDATE device_link SET mcp_token_id = :tok WHERE id = :id")
                .param("tok", mcpTokenId)
                .param("id", id)
                .update();
    }

    public void recordPoll(String id, String at, int count) {
        jdbc.sql("UPDATE device_link SET last_polled_at = :at, poll_count = :n WHERE id = :id")
                .param("at", at)
                .param("n", count)
                .param("id", id)
                .update();
    }

    private static DeviceLink map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DeviceLink(
                rs.getString("id"),
                rs.getString("device_code_prefix"),
                rs.getString("device_code_hash"),
                rs.getString("user_code"),
                rs.getString("status"),
                rs.getString("client_label"),
                rs.getString("org_id"),
                rs.getString("project_id"),
                rs.getString("user_id"),
                rs.getString("mcp_token_id"),
                rs.getString("created_at"),
                rs.getString("expires_at"),
                rs.getString("last_polled_at"),
                rs.getInt("poll_count"));
    }
}
