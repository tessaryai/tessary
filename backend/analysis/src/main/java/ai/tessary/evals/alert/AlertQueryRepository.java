// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import ai.tessary.evals.detection.DetectionTableRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The read surface the alerting worker assembles its messages from: the cases that opened, and the
 * per-classifier activity a digest rolls up.
 *
 * <p>The window-aggregate threshold counts that used to live here are gone with the threshold path
 * itself — a classifier now decides for itself when its own window crosses a bar (see
 * {@code ClassifierArming}), so alerting no longer counts another slice's rows to make a judgement
 * about them.
 *
 * <p>What remains reads {@link DetectionTableRegistry}'s runtime-stitched union over the
 * per-classifier detection tables (the query-time replacement for the old six-arm stitching view
 * that used to serve this read), rather than {@code verdict}. The {@code alert/} slice OWNS these queries rather than
 * appending them to the classifier module (ArchUnit only requires raw {@code JdbcClient} to live in a
 * {@code *Repository}, which this is — the same cross-feature-read pattern
 * {@code classifier/SubstrateReadRepository} uses).
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
     * A case that opened, with everything a message about it needs — the fields launch requirement I2
     * means by "enough detail to triage from the message".
     *
     * <p>Read in one query rather than assembled from services, because it runs on the alerting heartbeat
     * for every project: a per-case round trip through the case surface would put the case page's whole
     * read (evidence series, exemplars, curation markers) behind a Slack notification.
     *
     * @param basis the detector's own account of why this crossed ITS bar. The single most useful line in
     *     the message, and the reason the message can be triaged without opening anything: it already
     *     names the statistic, the reference and the gate.
     * @param rulingSummary the Layer-2 prose, or null when a human ruled directly or nothing has.
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
     * Cases that opened in {@code (since, until]}, oldest first, excluding any already resolved or muted.
     *
     * <p><b>Strictly greater than {@code since}</b>: the anchor is the {@code opened_at} of the last case
     * delivered, so an inclusive bound would re-deliver it on every tick. The idempotent
     * {@code (rule, case_id)} insert would catch that, but relying on the write to fix a read that is
     * wrong on purpose is how a bug survives a schema change.
     *
     * <p><b>Compared as timestamps, not as text</b>, even though {@code opened_at} is a TEXT column.
     * {@code Instant.toString()} omits a zero fraction, so {@code …T10:00:00.123Z} sorts BEFORE
     * {@code …T10:00:00Z} lexicographically ({@code '.'} &lt; {@code 'Z'}) — a case opened a hundred
     * milliseconds after the anchor would read as older than it and never be delivered. The cast costs an
     * index scan on a set already narrowed to one project's open cases.
     *
     * <p>Muted and resolved cases are excluded rather than filtered later. A case can be muted or closed
     * between opening and the next heartbeat — most often because a human was already looking at Triage
     * when it appeared — and paging someone about a case they have just dealt with is the fastest way to
     * teach them the alerts are noise.
     */
    public List<OpenedCase> casesOpenedBetween(String projectId, String since, String until, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.seq, c.detector, c.title, c.basis, c.call_site_id, c.opened_at,
                       f.triage_verdict, f.triage_summary, f.status AS finding_status,
                       o.slug AS org_slug, p.slug AS project_slug
                FROM eval_case c
                  JOIN project p ON p.id = c.project_id
                  JOIN organization o ON o.id = p.org_id
                  LEFT JOIN finding f ON f.id = c.finding_id AND f.project_id = c.project_id
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
                            "blocked".equals(rs.getString("finding_status")),
                            rs.getString("org_slug"),
                            rs.getString("project_slug"));
                })
                .list();
    }

    /** A per-classifier detection count in a window — the rolled-up unit a digest/brief is assembled from. */
    public record ClassifierActivity(
            String classifierId, String classifierKey, String classifierName, long eventCount) {}

    /**
     * Per-classifier detection counts over the window {@code [start, end)} for a whole project, worst-first —
     * the body of a digest/brief roll-up. Only classifiers with at least one detection in the window appear.
     * Counts every band (a roll-up is a recall-oriented activity summary, not a precision metric).
     */
    public List<ClassifierActivity> projectActivityInWindow(String projectId, String start, String end) {
        return jdbc.sql("SELECT sg.id AS classifier_id, sg.classifier_key AS classifier_key,"
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
                        rs.getString("classifier_id"),
                        rs.getString("classifier_key"),
                        rs.getString("classifier_name"),
                        rs.getLong("event_count")))
                .list();
    }
}
