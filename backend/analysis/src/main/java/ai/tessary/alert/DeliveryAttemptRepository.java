// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Outbound delivery-attempt log. {@link #claim} inserts a {@code pending} row idempotently
 * on {@code (alert_event_id, channel_id)} — first write wins, so a re-fired event never sends twice;
 * {@link #markDelivered}/{@link #markFailed} record the outcome.
 */
@Repository
public class DeliveryAttemptRepository {

    private static final String COLS =
            "id, alert_event_id, channel_id, project_id, status, " + "http_status, error, attempted_at, completed_at";

    private final JdbcClient jdbc;

    public DeliveryAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserve the (event, channel) slot. Returns {@code true} when this attempt is the first — the caller
     * should send. {@code false} means a prior attempt already claimed it (in-flight or done): the caller
     * must NOT send.
     */
    public boolean claim(String id, String alertEventId, String channelId, String projectId) {
        return jdbc.sql("""
            INSERT INTO alert_delivery_attempt (id, alert_event_id, channel_id, project_id, status, attempted_at)
            VALUES (:id, :eid, :cid, :pid, 'pending', :now)
            ON CONFLICT (alert_event_id, channel_id) DO NOTHING
            """)
                        .param("id", id)
                        .param("eid", alertEventId)
                        .param("cid", channelId)
                        .param("pid", projectId)
                        .param("now", Instant.now().toString())
                        .update()
                > 0;
    }

    public void markDelivered(String id, @Nullable Integer httpStatus) {
        jdbc.sql("""
            UPDATE alert_delivery_attempt
            SET status = 'delivered', http_status = :http, error = NULL, completed_at = :now
            WHERE id = :id
            """)
                .param("http", httpStatus)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    public void markFailed(String id, @Nullable Integer httpStatus, String error) {
        jdbc.sql("""
            UPDATE alert_delivery_attempt
            SET status = 'failed', http_status = :http, error = :err, completed_at = :now
            WHERE id = :id
            """)
                .param("http", httpStatus)
                .param("err", error.length() > 2000 ? error.substring(0, 2000) : error)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    public List<DeliveryAttemptRow> listByProject(String projectId, int limit) {
        return jdbc.sql(String.format(
                        Locale.ROOT,
                        "SELECT %s FROM alert_delivery_attempt WHERE project_id = :pid ORDER BY attempted_at DESC LIMIT :limit",
                        COLS))
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    private static DeliveryAttemptRow map(ResultSet rs) throws SQLException {
        return new DeliveryAttemptRow(
                rs.getString("id"),
                rs.getString("alert_event_id"),
                rs.getString("channel_id"),
                rs.getString("project_id"),
                rs.getString("status"),
                (Integer) rs.getObject("http_status"),
                rs.getString("error"),
                rs.getString("attempted_at"),
                rs.getString("completed_at"));
    }
}
