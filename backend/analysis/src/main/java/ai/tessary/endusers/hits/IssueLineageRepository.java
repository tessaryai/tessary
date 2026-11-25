// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.cases.CaseKey;
import ai.tessary.cases.CaseRow;
import ai.tessary.classifier.finding.FindingRow;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The findings that belong to a case, with the case's key. A finding ruled negative and a case resolved as a false
 * alarm are left out: they do not belong to the issue.
 */
@Repository
public class IssueLineageRepository {

    private final JdbcClient jdbc;

    public IssueLineageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CaseFinding(
            String findingId, HitFinding finding, String caseId, long seq, boolean live, CaseKey key) {}

    /** Ordered by case number, then finding. */
    public List<CaseFinding> caseFindings(String projectId) {
        return jdbc.sql("""
                SELECT f.id AS finding_id, f.classifier_key,
                       COALESCE(f.payload ->> 'cause_kind', f.classifier_key) AS cause_kind,
                       f.payload ->> 'facet' IS NOT NULL AS faceted,
                       c.id AS case_id, c.seq, c.state <> :resolved AS live,
                       c.detector, c.subject_kind, c.subject_id, c.metric
                  FROM finding f
                  JOIN eval_case c ON c.project_id = f.project_id AND c.id = f.case_id
                 WHERE f.project_id = :pid
                   AND f.triage_verdict IS DISTINCT FROM :negative
                   AND c.disposition IS DISTINCT FROM :falseAlarm
                 ORDER BY c.seq, f.id
                """)
                .param("pid", projectId)
                .param("resolved", CaseRow.State.RESOLVED)
                .param("negative", FindingRow.TriageVerdict.NEGATIVE)
                .param("falseAlarm", CaseRow.Disposition.FALSE_ALARM)
                .query((rs, n) -> new CaseFinding(
                        rs.getString("finding_id"),
                        new HitFinding(
                                rs.getString("classifier_key"), rs.getString("cause_kind"), rs.getBoolean("faceted")),
                        rs.getString("case_id"),
                        rs.getLong("seq"),
                        rs.getBoolean("live"),
                        new CaseKey(
                                rs.getString("detector"),
                                rs.getString("subject_kind"),
                                rs.getString("subject_id"),
                                rs.getString("metric"))))
                .list();
    }
}
