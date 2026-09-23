// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the Groundedness rate test replays: per call site and per hour, how many traces had an answer scored
 * and how many of them are flagged now.
 *
 * <p><b>A trace is one trial on each call site it was scored on.</b> There is no trace table of its own: every
 * fact is a {@code GROUP BY (subject_trace_id, call_site_id)} over {@code groundedness_assessment} under the
 * current scorer, bucketed in the hour of the trace's first scored answer there. A trace fails when one of its
 * flagged answers still has an uncleared detection, so two flagged answers in one trace are one failure, and a
 * {@code false_alarm} resolve that clears them makes the next replay count the trace clean.
 *
 * <p><b>Read, not accumulated.</b> An hour's tally is not final when first read (a backfill lands one trace's
 * spans across several uploads, and a resolve clears flags), which is why the replay rebuilds every pass.
 */
@Repository
public class GroundednessRateRepository {

    /**
     * The key an answer with no call site is tallied under. It never alarms: the service drops it before the
     * replay, as frustration drops its unassigned conversations.
     */
    public static final String UNASSIGNED = "";

    /**
     * The per-trace grouping every read here shares. {@code {detections}} is the registered detection table;
     * the {@code EXISTS} runs on its unique subject index. Hours are truncated in UTC.
     */
    private static final String TRACES = """
            WITH tr AS (
              SELECT a.subject_trace_id, a.call_site_id,
                     MIN(a.observation_started_at) AS first_scored_at,
                     BOOL_OR(a.flagged AND EXISTS (
                         SELECT 1 FROM {detections} d
                          WHERE d.project_id = a.project_id AND d.classifier_id = a.classifier_id
                            AND d.subject_trace_id = a.subject_trace_id AND d.subject_span_id = a.subject_span_id
                            AND d.cleared_at IS NULL)) AS failed
                FROM groundedness_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid
                 AND a.scorer_version = :scorerVersion AND a.observation_started_at >= :from
               GROUP BY a.subject_trace_id, a.call_site_id)
            """;

    static final String HOURLY_TALLIES = TRACES + """
            SELECT date_trunc('hour', t.first_scored_at, 'UTC') AS bucket, t.call_site_id,
                   COUNT(*) AS traces,
                   COUNT(*) FILTER (WHERE t.failed) AS flagged
              FROM tr t
             GROUP BY 1, 2
             ORDER BY 1, 2
            """;

    /** Every scored trace of one call site's stream in {@code [onset, until)}, newest first: the members. */
    static final String SCORED_SINCE = TRACES + """
            SELECT t.subject_trace_id
              FROM tr t
             WHERE t.call_site_id = :callSite AND t.first_scored_at >= :onset AND t.first_scored_at < :until
             ORDER BY t.first_scored_at DESC, t.subject_trace_id DESC
            """;

    /**
     * Every flagged answer of the failed traces of one call site's stream in {@code [onset, until)}: the
     * witnesses, as trace and span pairs, newest trace first.
     */
    static final String FLAGGED_SINCE = TRACES + """
            SELECT a.subject_trace_id, a.subject_span_id
              FROM tr t
              JOIN groundedness_assessment a
                ON a.project_id = :pid AND a.classifier_id = :cid AND a.scorer_version = :scorerVersion
               AND a.subject_trace_id = t.subject_trace_id AND a.call_site_id = t.call_site_id
               AND a.observation_started_at >= :from AND a.flagged
             WHERE t.failed AND t.call_site_id = :callSite
               AND t.first_scored_at >= :onset AND t.first_scored_at < :until
               AND EXISTS (
                   SELECT 1 FROM {detections} d
                    WHERE d.project_id = a.project_id AND d.classifier_id = a.classifier_id
                      AND d.subject_trace_id = a.subject_trace_id AND d.subject_span_id = a.subject_span_id
                      AND d.cleared_at IS NULL)
             ORDER BY t.first_scored_at DESC, a.subject_trace_id DESC, a.observation_started_at, a.subject_span_id
            """;

    /** One flagged answer a finding cites: the trace it is a trial in and the span that was flagged. */
    public record FlaggedAnswer(String traceId, String spanId) {}

    private final JdbcClient jdbc;
    private final ClassifierDetectionWriteRepository detections;
    private final ToolErrorStateRepository states;

    public GroundednessRateRepository(JdbcClient jdbc, ClassifierDetectionWriteRepository detections) {
        this.jdbc = jdbc;
        this.detections = detections;
        this.states = ToolErrorStateRepository.forTable(jdbc, "groundedness_state", "call_site_id");
    }

    /** The CUSUM state the shared engine carries between passes, one row per call site. */
    public ToolErrorStateRepository states() {
        return states;
    }

    /**
     * The newest scored answer under {@code scorerVersion}: the anchor the replay window reads back from, as
     * frustration anchors on its newest scored turn, so a project whose traffic is old still has a window holding
     * it.
     */
    public Optional<Instant> newestObservationAt(String projectId, String classifierId, String scorerVersion) {
        return jdbc.sql("""
                        SELECT MAX(observation_started_at) AS newest
                          FROM groundedness_assessment
                         WHERE project_id = :pid AND classifier_id = :cid AND scorer_version = :scorerVersion
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .query((rs, n) -> rs.getObject("newest", OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Scored and flagged traces per call site per hour since {@code from}, oldest first, as the tallies {@code
     * ToolErrorTrend} replays: {@code calls} is traces, {@code failures} is flagged ones. An answer with no call
     * site is keyed {@link #UNASSIGNED}. Empty while no groundedness detection table is registered.
     */
    public List<HourlyToolTally> hourlyTallies(
            String projectId, String classifierId, String scorerVersion, Instant from) {
        String table = detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS);
        if (table == null) return List.of();
        return jdbc.sql(HOURLY_TALLIES.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("from", Timestamp.from(from))
                .query((rs, n) -> new HourlyToolTally(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        rs.getString("call_site_id"),
                        rs.getLong("traces"),
                        rs.getLong("flagged")))
                .list();
    }

    /**
     * Every scored trace of one call site's stream in {@code [onset, until)}, newest first: the spell's members,
     * the population its rate is a fraction of. {@code windowFrom} is the replay window's start, so a trace's
     * first scored answer is found over the same rows the tally read.
     */
    public List<String> scoredSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            Instant until) {
        String table = detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS);
        if (table == null) return List.of();
        return spell(SCORED_SINCE.replace("{detections}", table), projectId, classifierId, scorerVersion)
                .param("callSite", callSiteId)
                .param("from", Timestamp.from(windowFrom))
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) -> rs.getString("subject_trace_id"))
                .list();
    }

    /**
     * Every flagged answer of the flagged traces of one call site's stream in {@code [onset, until)}: the spell's
     * witnesses. Empty while no groundedness detection table is registered.
     */
    public List<FlaggedAnswer> flaggedSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            Instant until) {
        String table = detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS);
        if (table == null) return List.of();
        return spell(FLAGGED_SINCE.replace("{detections}", table), projectId, classifierId, scorerVersion)
                .param("callSite", callSiteId)
                .param("from", Timestamp.from(windowFrom))
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) -> new FlaggedAnswer(rs.getString("subject_trace_id"), rs.getString("subject_span_id")))
                .list();
    }

    private JdbcClient.StatementSpec spell(String sql, String projectId, String classifierId, String scorerVersion) {
        return jdbc.sql(sql).param("pid", projectId).param("cid", classifierId).param("scorerVersion", scorerVersion);
    }
}
