// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Fired-alert storage. A bare {@code ON CONFLICT DO NOTHING}, with no conflict target, covers both partial unique keys
 * ({@code (alert_rule_id, window_start)} and {@code (alert_rule_id, case_id)}), so N backends fire once and a third key
 * would not need this insert edited.
 */
@Repository
public class AlertEventRepository {

    private static final String COLS = "id, project_id, alert_rule_id, classifier_id, rule_type, basis, state, "
            + "window_start, window_end, value, threshold, payload_json, case_id, occurred_at, created_at";

    private final JdbcClient jdbc;

    public AlertEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns {@code true} when a new firing was written. */
    public boolean insertIfAbsent(AlertEventRow row) {
        return jdbc.sql("""
            INSERT INTO alert_event (id, project_id, alert_rule_id, classifier_id, rule_type, basis, state,
                                     window_start, window_end, value, threshold, payload_json, case_id,
                                     occurred_at, created_at)
            VALUES (:id, :pid, :rid, :sid, :rt, :basis, :state, :wstart, :wend, :value, :threshold, :payload,
                    :caseId, :occurredAt, :createdAt)
            ON CONFLICT DO NOTHING
            """)
                        .param("id", row.id())
                        .param("pid", row.projectId())
                        .param("rid", row.alertRuleId())
                        .param("sid", row.classifierId())
                        .param("rt", row.ruleType())
                        .param("basis", row.basis())
                        .param("state", row.state())
                        .param("wstart", row.windowStart())
                        .param("wend", row.windowEnd())
                        .param("value", row.value())
                        .param("threshold", row.threshold())
                        .param("payload", row.payloadJson())
                        .param("caseId", row.caseId())
                        .param("occurredAt", row.occurredAt())
                        .param("createdAt", row.createdAt())
                        .update()
                > 0;
    }

    public List<AlertEventRow> listByProject(String projectId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_event WHERE project_id = :pid "
                        + "ORDER BY occurred_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    public List<AlertEventRow> listByRule(String projectId, String alertRuleId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_event WHERE project_id = :pid AND alert_rule_id = :rid "
                        + "ORDER BY occurred_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("rid", alertRuleId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    public List<AlertEventRow> listByClassifier(String projectId, String classifierId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_event WHERE project_id = :pid AND classifier_id = :sid "
                        + "ORDER BY occurred_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    private static AlertEventRow map(ResultSet rs) throws SQLException {
        return new AlertEventRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("alert_rule_id"),
                rs.getString("classifier_id"),
                rs.getString("rule_type"),
                rs.getString("basis"),
                rs.getString("state"),
                rs.getString("window_start"),
                rs.getString("window_end"),
                (Integer) rs.getObject("value"),
                (Integer) rs.getObject("threshold"),
                rs.getString("payload_json"),
                rs.getString("case_id"),
                rs.getString("occurred_at"),
                rs.getString("created_at"));
    }
}
