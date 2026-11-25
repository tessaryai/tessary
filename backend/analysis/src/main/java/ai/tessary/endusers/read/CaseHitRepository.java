// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.hits.HitFinding;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Live counts of the user IDs a case hit, read from {@code end_user_hit} under each finding's hit rule, and the refs
 * behind them from {@code end_user_hit_summary}. The all-zero key holds refs with no user ID and is never a user ID.
 */
@Repository
public class CaseHitRepository {

    private static final byte[] NO_USER_KEY = new byte[EndUserKeys.KEY_BYTES];

    private final JdbcClient jdbc;

    public CaseHitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param roles the evidence roles stored for the finding */
    public record FindingRoles(String findingId, HitFinding finding, Set<String> roles) {}

    /** A finding counted for a case: one of its lineage's findings. */
    public record CaseFinding(String caseId, String findingId) {}

    /** One finding role that counts as a hit for one case. */
    public record Pick(String caseId, String findingId, String role) {}

    /** @param sinceDay the first day a counted user ID was hit */
    public record CaseHits(String caseId, int userIds, int sinceDay) {}

    /** @param refs refs that count as hits @param refsNoUserId those of them with no user ID */
    public record CaseRefs(String caseId, int refs, int refsNoUserId) {}

    /** Findings with no stored hit row are left out. */
    public List<FindingRoles> findingRoles(String projectId, Collection<String> findingIds) {
        return jdbc.sql("""
                SELECT f.id, f.classifier_key, COALESCE(f.payload ->> 'cause_kind', f.classifier_key) AS cause_kind,
                       f.payload ->> 'facet' IS NOT NULL AS faceted, array_agg(DISTINCT h.role) AS roles
                  FROM finding f
                  JOIN end_user_hit h ON h.project_id = f.project_id AND h.finding_id = f.id
                 WHERE f.project_id = :pid AND f.id = ANY(:ids)
                 GROUP BY f.id, f.classifier_key, f.payload
                 ORDER BY f.id
                """)
                .param("pid", projectId)
                .param("ids", findingIds.toArray(String[]::new))
                .query((rs, n) -> new FindingRoles(
                        rs.getString("id"),
                        new HitFinding(
                                rs.getString("classifier_key"), rs.getString("cause_kind"), rs.getBoolean("faceted")),
                        Set.copyOf(Arrays.asList((String[]) rs.getArray("roles").getArray()))))
                .list();
    }

    /**
     * Distinct user IDs per case over the picked finding roles. With {@code coveredOnly}, only user IDs the judge did
     * not set aside count. A case with no counted user ID has no row.
     */
    public List<CaseHits> caseHits(String projectId, List<Pick> picks, boolean coveredOnly) {
        return jdbc.sql("""
                SELECT p.case_id, COUNT(DISTINCT h.user_key)::int AS user_ids, MIN(h.day) AS since_day
                  FROM unnest(CAST(:cases AS text[]), CAST(:findings AS text[]), CAST(:roles AS text[]))
                       AS p(case_id, finding_id, role)
                  JOIN end_user_hit h ON h.project_id = :pid AND h.finding_id = p.finding_id AND h.role = p.role
                 WHERE h.user_key <> :noKey
                   AND (NOT :coveredOnly OR EXISTS (
                         SELECT 1 FROM end_user u
                          WHERE u.project_id = h.project_id AND u.user_key = h.user_key
                            AND u.set_aside_reason IS NULL))
                 GROUP BY p.case_id
                 ORDER BY p.case_id
                """)
                .param("pid", projectId)
                .param("cases", picks.stream().map(Pick::caseId).toArray(String[]::new))
                .param("findings", picks.stream().map(Pick::findingId).toArray(String[]::new))
                .param("roles", picks.stream().map(Pick::role).toArray(String[]::new))
                .param("noKey", NO_USER_KEY)
                .param("coveredOnly", coveredOnly)
                .query((rs, n) -> new CaseHits(rs.getString("case_id"), rs.getInt("user_ids"), rs.getInt("since_day")))
                .list();
    }

    /** Summed over each case's findings. A case none of whose findings has a summary has no row. */
    public List<CaseRefs> caseRefs(String projectId, List<CaseFinding> counted) {
        return jdbc.sql("""
                SELECT p.case_id, SUM(s.refs_total)::int AS refs, SUM(s.refs_no_user_id)::int AS refs_no_user_id
                  FROM unnest(CAST(:cases AS text[]), CAST(:findings AS text[])) AS p(case_id, finding_id)
                  JOIN end_user_hit_summary s ON s.project_id = :pid AND s.finding_id = p.finding_id
                 GROUP BY p.case_id
                 ORDER BY p.case_id
                """)
                .param("pid", projectId)
                .param("cases", counted.stream().map(CaseFinding::caseId).toArray(String[]::new))
                .param("findings", counted.stream().map(CaseFinding::findingId).toArray(String[]::new))
                .query((rs, n) ->
                        new CaseRefs(rs.getString("case_id"), rs.getInt("refs"), rs.getInt("refs_no_user_id")))
                .list();
    }
}
