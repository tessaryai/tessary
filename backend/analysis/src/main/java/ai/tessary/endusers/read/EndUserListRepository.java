// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One keyset page of the user IDs in a funnel stage, longest silent first. Ties are ordered by {@code md5(user_key)},
 * so the cursor never carries the key itself: access logs keep full URIs.
 */
@Repository
public class EndUserListRepository {

    private final JdbcClient jdbc;

    public EndUserListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param silentOrder days silent with null as -1, the value the keyset compares */
    public record IdAfter(int silentOrder, String tie) {}

    /** @param hitBy references of the cases whose issue claims a loss and hit this user ID */
    public record IdRow(
            @Nullable String idText,
            @Nullable UserIdState state,
            @Nullable Substate substate,
            @Nullable Grade grade,
            @Nullable Integer daysSilent,
            @Nullable Double pStop,
            List<String> hitBy,
            int silentOrder,
            String tie) {}

    public List<IdRow> page(String projectId, Stage stage, @Nullable IdAfter after, int limit) {
        return jdbc.sql("""
                SELECT u.id_text, u.state, u.substate, u.grade, u.days_silent, u.p_stop,
                       COALESCE(u.days_silent, -1) AS silent_order, md5(u.user_key) AS tie,
                       ARRAY(SELECT 'C-' || c.seq
                               FROM end_user_issue_hit h
                               JOIN end_user_issue i ON i.project_id = h.project_id AND i.lineage_key = h.lineage_key
                               JOIN eval_case c ON c.project_id = i.project_id AND c.id = i.case_id
                              WHERE h.project_id = u.project_id AND h.user_key = u.user_key
                                AND i.verdict IN (:claims)
                              ORDER BY c.seq) AS hit_by
                  FROM end_user u
                 WHERE u.project_id = :pid AND u.stage = :stage
                   AND (CAST(:afterSilent AS integer) IS NULL
                        OR (-COALESCE(u.days_silent, -1), md5(u.user_key))
                           > (CAST(:afterSilent AS integer), CAST(:afterTie AS text)))
                 ORDER BY -COALESCE(u.days_silent, -1), md5(u.user_key)
                 LIMIT :limit
                """)
                .param("pid", projectId)
                .param("stage", stage.wire())
                .param("claims", List.of(Verdict.EVIDENCE_OF_LOSS.wire(), Verdict.FEW_ACCOUNTS.wire()))
                .param("afterSilent", after == null ? null : -after.silentOrder())
                .param("afterTie", after == null ? null : after.tie())
                .param("limit", limit)
                .query((rs, n) -> row(rs))
                .list();
    }

    private static IdRow row(ResultSet rs) throws SQLException {
        String state = rs.getString("state");
        String substate = rs.getString("substate");
        String grade = rs.getString("grade");
        return new IdRow(
                rs.getString("id_text"),
                state == null ? null : UserIdState.fromWire(state),
                substate == null ? null : Substate.fromWire(substate),
                grade == null ? null : Grade.fromWire(grade),
                rs.getObject("days_silent", Integer.class),
                rs.getObject("p_stop", Double.class),
                Arrays.asList((String[]) rs.getArray("hit_by").getArray()),
                rs.getInt("silent_order"),
                rs.getString("tie"));
    }
}
