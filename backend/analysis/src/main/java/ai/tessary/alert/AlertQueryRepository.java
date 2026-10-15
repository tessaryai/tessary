// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.detection.DetectionTableRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The alerting worker's reads: opened cases and per-classifier activity, over {@link DetectionTableRegistry}'s union of
 * the detection tables.
 */
@Repository
public class AlertQueryRepository {

    private final JdbcClient jdbc;
    private final DetectionTableRegistry detectionTables;

    public AlertQueryRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        this.detectionTables = detectionTables;
    }

    /**
     * One query rather than assembled from services: it runs on the heartbeat for every project. @param basis the
     * detector's own account of why this crossed its bar @param rulingSummary the Layer-2 prose, or null when a human
     * ruled directly or nothing has
     */
    public record OpenedCase(
            String id,
            String reference,
            String detector,
            String title,
            String basis,
            @Nullable String callSiteId,
            String openedAt,
            @Nullable String rulingVerdict,
            @Nullable String rulingSummary,
            boolean ruledByHuman,
            String orgSlug,
            String projectSlug) {}

    /**
     * Strictly greater than {@code since}: the anchor is the last delivered case's {@code opened_at}. Compared as
     * timestamps because {@code Instant.toString()} omits a zero fraction and breaks text ordering. Closed cases are
     * excluded: paging someone about a case they just dealt with teaches them the alerts are noise.
     */
    public List<OpenedCase> casesOpenedBetween(String projectId, String since, String until, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.seq, c.detector, c.title, c.basis, c.call_site_id, c.opened_at,
                       f.triage_verdict, f.triage_summary, f.human_verdict_at,
                       o.slug AS org_slug, p.slug AS project_slug
                FROM eval_case c
                  JOIN project p ON p.id = c.project_id
                  JOIN organization o ON o.id = p.org_id
                  LEFT JOIN LATERAL (
                      SELECT * FROM finding
                       WHERE case_id = c.id AND project_id = c.project_id
                       ORDER BY created_at DESC LIMIT 1
                  ) f ON true
                WHERE c.project_id = :pid
                  AND c.state = 'open'
                  AND c.opened_at::timestamptz > :since::timestamptz
                  AND c.opened_at::timestamptz <= :until::timestamptz
                ORDER BY c.opened_at::timestamptz ASC
                LIMIT :limit
                """)
                .param("pid", projectId)
                .param("since", since)
                .param("until", until)
                .param("limit", limit)
                .query((rs, n) -> {
                    return new OpenedCase(
                            rs.getString("id"),
                            "C-" + rs.getLong("seq"),
                            rs.getString("detector"),
                            rs.getString("title"),
                            rs.getString("basis"),
                            rs.getString("call_site_id"),
                            rs.getString("opened_at"),
                            rs.getString("triage_verdict"),
                            rs.getString("triage_summary"),
                            rs.getString("human_verdict_at") != null,
                            rs.getString("org_slug"),
                            rs.getString("project_slug"));
                })
                .list();
    }

    public record ClassifierActivity(String classifierKey, String classifierName, long eventCount) {}

    /** Worst-first; only classifiers with a detection in {@code [start, end)}. Counts every band. */
    public List<ClassifierActivity> projectActivityInWindow(String projectId, String start, String end) {
        return jdbc.sql("SELECT sg.classifier_key AS classifier_key,"
                        + " sg.name AS classifier_name, COUNT(*) AS event_count "
                        + "FROM (" + detectionTables.unionSql() + ") d "
                        + "  JOIN classifier sg ON sg.project_id = d.project_id AND sg.classifier_key = d.classifier_id "
                        + "WHERE d.project_id = :pid "
                        + "  AND d.created_at >= :start::timestamptz AND d.created_at < :end::timestamptz "
                        + "GROUP BY sg.id, sg.classifier_key, sg.name "
                        + "ORDER BY COUNT(*) DESC, sg.classifier_key ASC")
                .param("pid", projectId)
                .param("start", start)
                .param("end", end)
                .query((rs, n) -> new ClassifierActivity(
                        rs.getString("classifier_key"), rs.getString("classifier_name"), rs.getLong("event_count")))
                .list();
    }
}
