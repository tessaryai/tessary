// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The aggregates Malformed Output's rate is recomputed from: per call site and per hour, how many outputs the
 * sweep checked against a schema and how many of those failed.
 *
 * <p><b>Recomputed from source on every pass, never accumulated.</b> The sweep writes a detection only for an
 * output that failed; the outputs that passed leave no row. So the denominator is counted from the spans
 * themselves, which is also what makes a re-run harmless: the counts are read, not added to, the property
 * {@code ToolErrorRepository#hourlyTallies} holds for the same reason.
 */
@Repository
public class MalformedOutputRateRepository {

    private final JdbcClient jdbc;

    private final ToolErrorStateRepository states;

    public MalformedOutputRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.states = ToolErrorStateRepository.forTable(jdbc, "malformed_output_state", "call_site_id");
    }

    /** The CUSUM state tool_error's engine carries between passes, one row per call site. */
    public ToolErrorStateRepository states() {
        return states;
    }

    /**
     * Checked and failed outputs per call site per hour since {@code from}, oldest first, as the tallies
     * {@code ToolErrorTrend} replays, keyed on the call site.
     *
     * <p>A span counts as checked when the detector would have validated it: its call site carries a schema
     * and its output is not empty. The preview column stands in for the output so this never reads a payload;
     * the writer cuts it from the same text and leaves it null exactly when the output is.
     *
     * <p><b>Only spans stored before {@code checkedBefore}.</b> The sweep checks in storage order, so a span
     * stored after its cursor is one it has not reached, and counting it would add a pass it never recorded.
     * Hours are on the span's own clock, because a CUSUM fed out of order is not a CUSUM.
     */
    public List<HourlyToolTally> hourlyTallies(
            String projectId, String classifierId, Instant from, String checkedBefore) {
        return jdbc.sql("""
                        SELECT date_trunc('hour', s.started_at) AS bucket,
                               s.call_site_id                   AS call_site_id,
                               COUNT(*)                         AS checked,
                               COUNT(d.id)                      AS malformed
                          FROM span s
                          JOIN call_site cs
                            ON cs.project_id = s.project_id AND cs.id = s.call_site_id
                           AND cs.output_schema IS NOT NULL
                          LEFT JOIN malformed_output_detection d
                            ON d.project_id = s.project_id AND d.classifier_id = :cid
                           AND d.subject_trace_id = s.trace_id AND d.subject_span_id = s.id
                         WHERE s.project_id = :pid
                           AND s.started_at >= CAST(:from AS timestamptz)
                           AND s.created_at < CAST(:checkedBefore AS timestamptz)
                           AND s.output_preview IS NOT NULL AND s.output_preview <> ''
                         GROUP BY 1, 2
                         ORDER BY 1 ASC, 2 ASC
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("from", from.toString())
                .param("checkedBefore", checkedBefore)
                .query((rs, n) -> new HourlyToolTally(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        rs.getString("call_site_id"),
                        rs.getLong("checked"),
                        rs.getLong("malformed")))
                .list();
    }

    /**
     * The most recent failing spans at one call site since a spell began, newest first: instances a reader can
     * open, not the population.
     */
    public List<FindingEvidenceRepository.Ref> recentFailures(
            String projectId, String classifierId, String callSiteId, String since, int limit) {
        return jdbc.sql("""
                        SELECT d.subject_trace_id, d.subject_span_id
                          FROM malformed_output_detection d
                          JOIN span s
                            ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id
                           AND s.id = d.subject_span_id
                         WHERE d.project_id = :pid AND d.classifier_id = :cid AND s.call_site_id = :callSite
                           AND s.started_at >= CAST(:since AS timestamptz)
                         ORDER BY s.started_at DESC
                         LIMIT :limit
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("since", since)
                .param("limit", limit)
                .query((rs, n) -> FindingEvidenceRepository.Ref.span(
                        rs.getString("subject_trace_id"), rs.getString("subject_span_id")))
                .list();
    }
}
