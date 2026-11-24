// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Verdict;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The stored issues of a snapshot day and the hit user IDs of one issue that are silent now. An issue row names its
 * lineage's current case; a row with no case is not an issue the page lists.
 */
@Repository
public class IssueReadRepository {

    private static final String ISSUE_COLS = """
            i.lineage_key, i.day, i.rank, i.user_ids_hit, i.compared, i.lost, i.low, i.high, i.verdict, i.shown,
            i.group_id, i.curve, i.diagnostics, c.id AS case_id, c.seq, c.title, c.detector, c.subject_label
            """;

    private final JdbcClient jdbc;
    private final ReadJson json;

    public IssueReadRepository(JdbcClient jdbc, ReadJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record IssueRow(
            String lineageKey,
            int day,
            String caseId,
            long caseSeq,
            String title,
            String detector,
            String subjectLabel,
            @Nullable Integer rank,
            int usersHit,
            @Nullable Integer compared,
            @Nullable Double lost,
            @Nullable Double low,
            @Nullable Double high,
            Verdict verdict,
            boolean shown,
            @Nullable String groupId,
            @Nullable List<CurvePoint> curve,
            IssueDiagnostics diagnostics) {}

    /**
     * Keyset position in the drawer order: first hit day ascending, days silent descending, then {@code md5(user_key)},
     * which orders ties without putting the key in a URL.
     */
    public record SilentAfter(int firstHitDay, int silentOrder, String tie) {}

    /** @param silentOrder days silent with null as -1, the value the keyset compares */
    public record SilentIdRow(
            @Nullable String idText,
            int firstHitDay,
            @Nullable Integer daysSilent,
            Stage stage,
            @Nullable Grade grade,
            int silentOrder,
            String tie) {}

    /** Ranked first, then by case number. */
    public List<IssueRow> issues(String projectId, int day) {
        return jdbc.sql("SELECT " + ISSUE_COLS + """
                  FROM end_user_issue i
                  JOIN eval_case c ON c.project_id = i.project_id AND c.id = i.case_id
                 WHERE i.project_id = :pid AND i.day = :day
                 ORDER BY i.rank NULLS LAST, c.seq
                """)
                .param("pid", projectId)
                .param("day", day)
                .query((rs, n) -> issue(rs))
                .list();
    }

    /**
     * For each asked case, the issue whose current case it is, else the issue that holds one of its findings: an older
     * case of a lineage is no longer its current case. An asked case with no issue has no entry.
     */
    public Map<String, IssueRow> forCases(String projectId, int day, Collection<String> caseIds) {
        Map<String, IssueRow> out = new LinkedHashMap<>();
        jdbc.sql("SELECT DISTINCT ON (q.case_id) q.case_id AS asked, " + ISSUE_COLS + """
                  FROM unnest(CAST(:cases AS text[])) AS q(case_id)
                  JOIN end_user_issue i ON i.project_id = :pid AND i.day = :day
                   AND (i.case_id = q.case_id
                        OR i.finding_ids && ARRAY(SELECT f.id FROM finding f
                                                   WHERE f.project_id = :pid AND f.case_id = q.case_id))
                  JOIN eval_case c ON c.project_id = i.project_id AND c.id = i.case_id
                 ORDER BY q.case_id, i.case_id = q.case_id DESC, i.lineage_key
                """)
                .param("pid", projectId)
                .param("day", day)
                .param("cases", caseIds.toArray(String[]::new))
                .query((rs, n) -> Map.entry(rs.getString("asked"), issue(rs)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Hit user IDs of the issue whose stage is silent or stopped, in the drawer order. */
    public List<SilentIdRow> silentIds(String projectId, String lineageKey, @Nullable SilentAfter after, int limit) {
        return jdbc.sql("""
                SELECT u.id_text, h.first_hit_day, u.days_silent, u.stage, u.grade,
                       COALESCE(u.days_silent, -1) AS silent_order, md5(u.user_key) AS tie
                  FROM end_user_issue_hit h
                  JOIN end_user u ON u.project_id = h.project_id AND u.user_key = h.user_key
                 WHERE h.project_id = :pid AND h.lineage_key = :lineage AND u.stage IN ('silent', 'stopped')
                   AND (CAST(:afterDay AS integer) IS NULL
                        OR (h.first_hit_day, -COALESCE(u.days_silent, -1), md5(u.user_key))
                           > (CAST(:afterDay AS integer), CAST(:afterSilent AS integer), CAST(:afterTie AS text)))
                 ORDER BY h.first_hit_day, -COALESCE(u.days_silent, -1), md5(u.user_key)
                 LIMIT :limit
                """)
                .param("pid", projectId)
                .param("lineage", lineageKey)
                .param("afterDay", after == null ? null : after.firstHitDay())
                .param("afterSilent", after == null ? null : -after.silentOrder())
                .param("afterTie", after == null ? null : after.tie())
                .param("limit", limit)
                .query((rs, n) -> {
                    String grade = rs.getString("grade");
                    return new SilentIdRow(
                            rs.getString("id_text"),
                            rs.getInt("first_hit_day"),
                            rs.getObject("days_silent", Integer.class),
                            Stage.fromWire(rs.getString("stage")),
                            grade == null ? null : Grade.fromWire(grade),
                            rs.getInt("silent_order"),
                            rs.getString("tie"));
                })
                .list();
    }

    public int silentCount(String projectId, String lineageKey) {
        return jdbc.sql("""
                SELECT COUNT(*)::int
                  FROM end_user_issue_hit h
                  JOIN end_user u ON u.project_id = h.project_id AND u.user_key = h.user_key
                 WHERE h.project_id = :pid AND h.lineage_key = :lineage AND u.stage IN ('silent', 'stopped')
                """)
                .param("pid", projectId)
                .param("lineage", lineageKey)
                .query(Integer.class)
                .single();
    }

    private IssueRow issue(ResultSet rs) throws SQLException {
        return new IssueRow(
                rs.getString("lineage_key"),
                rs.getInt("day"),
                rs.getString("case_id"),
                rs.getLong("seq"),
                rs.getString("title"),
                rs.getString("detector"),
                rs.getString("subject_label"),
                rs.getObject("rank", Integer.class),
                rs.getInt("user_ids_hit"),
                rs.getObject("compared", Integer.class),
                rs.getObject("lost", Double.class),
                rs.getObject("low", Double.class),
                rs.getObject("high", Double.class),
                Verdict.fromWire(rs.getString("verdict")),
                rs.getBoolean("shown"),
                rs.getString("group_id"),
                json.readNullable(rs.getString("curve"), new TypeReference<List<CurvePoint>>() {}),
                json.read(rs.getString("diagnostics"), new TypeReference<IssueDiagnostics>() {}));
    }
}
