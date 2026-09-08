// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for {@code behavior_baseline_event} — the baseline changelog. Append-only by
 * design: this table is the module's answer to the boiling-frog problem, and an editable history
 * would not be one. No observability tool today can tell a customer how their agent's behaviour has
 * changed over a quarter; these rows can.
 */
@Repository
public class BehaviorBaselineEventRepository {

    private static final String COLS =
            "id, profile_id, baseline_id, project_id, event, workflow_key, gram_key, occurred_at, detail";

    private final JdbcClient jdbc;

    public BehaviorBaselineEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(BehaviorBaselineEventRow row) {
        jdbc.sql("INSERT INTO behavior_baseline_event (" + COLS + ") VALUES "
                        + "(:id, :profileId, :baselineId, :projectId, :event, :workflowKey, :gramKey, :at, "
                        + ":detail::jsonb)")
                .param("id", row.id())
                .param("profileId", row.profileId())
                .param("baselineId", row.baselineId())
                .param("projectId", row.projectId())
                .param("event", row.event())
                .param("workflowKey", row.workflowKey())
                .param("gramKey", row.gramKey())
                .param("at", row.occurredAt())
                .param("detail", row.detailJson())
                .update();
    }

    /**
     * The reason the epoch last stopped scoring, or empty if it never has.
     *
     * <p>The lifecycle already records this ({@code {"reason": "volume_collapse"}} or
     * {@code "alphabet_churn"}) — it was simply never read, so the UI hedged with "either … or" over
     * an answer the database had. {@code profile_stale} stays filtered out of the customer changelog
     * as an ops kind; this reads it for the one place it actually answers a user's question.
     */
    public Optional<String> latestStaleReason(String profileId) {
        return jdbc.sql("""
            SELECT detail->>'reason' FROM behavior_baseline_event
             WHERE profile_id = :pid AND event = :event AND detail->>'reason' IS NOT NULL
             ORDER BY occurred_at DESC LIMIT 1
            """)
                .param("pid", profileId)
                .param("event", BehaviorBaselineEventRow.Event.PROFILE_STALE)
                .query(String.class)
                .optional();
    }

    public List<BehaviorBaselineEventRow> listByProject(String projectId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM behavior_baseline_event WHERE project_id = :pid "
                        + "ORDER BY occurred_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    private static BehaviorBaselineEventRow map(ResultSet rs) throws SQLException {
        return new BehaviorBaselineEventRow(
                rs.getString("id"),
                rs.getString("profile_id"),
                rs.getString("baseline_id"),
                rs.getString("project_id"),
                rs.getString("event"),
                rs.getString("workflow_key"),
                rs.getString("gram_key"),
                rs.getString("occurred_at"),
                rs.getString("detail"));
    }
}
