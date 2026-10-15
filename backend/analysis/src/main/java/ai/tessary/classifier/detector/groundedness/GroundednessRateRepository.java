// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A trace is one trial on each call site it was scored on; two flagged answers in one trace are one failure. Rebuilt
 * every pass, since a backfill spreads a trace across uploads and a resolve clears flags.
 */
@Repository
public class GroundednessRateRepository {

    /** Never alarms: the service drops it before the replay. */
    public static final String UNASSIGNED = "";

    /** Hours are truncated in UTC. */
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

    static final String SCORED_SINCE = TRACES + """
            SELECT t.subject_trace_id
              FROM tr t
             WHERE t.call_site_id = :callSite AND t.first_scored_at >= :onset AND t.first_scored_at < :until
             ORDER BY t.first_scored_at DESC, t.subject_trace_id DESC
            """;

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

    /**
     * Trace-grain witness rows are not read, so a trace with two flagged answers is two rows here and one failure in
     * the rate.
     */
    private static final String ANSWER_PAGE = """
            SELECT e.trace_id, e.span_id, d.subject_session_id, d.subject_started_at, d.evidence::text AS evidence,
                   d.cleared_at, COUNT(*) OVER () AS total
              FROM finding_evidence e
              LEFT JOIN LATERAL (
                SELECT d.subject_session_id, d.subject_started_at, d.evidence, d.cleared_at
                  FROM {detections} d
                 WHERE d.project_id = e.project_id AND d.classifier_id = :cid
                   AND d.subject_trace_id = e.trace_id AND d.subject_span_id = e.span_id
                 ORDER BY d.subject_started_at DESC NULLS LAST, d.id DESC
                 LIMIT 1) d ON true
             WHERE e.project_id = :pid AND e.finding_id = :fid AND e.role = 'witness'
               AND e.trace_id IS NOT NULL AND e.span_id IS NOT NULL{filter}
             ORDER BY d.subject_started_at DESC NULLS LAST, e.trace_id DESC, e.span_id DESC
             LIMIT :limit OFFSET :offset
            """;

    /** A groundedness cause cites traces, so every flagged answer of a named trace is its share. */
    private static final String CAUSE_FILTER = """

               AND EXISTS (
                   SELECT 1 FROM rca_report r
                    WHERE r.id = :report AND r.project_id = e.project_id AND r.finding_id = e.finding_id
                      AND e.trace_id IN (SELECT jsonb_array_elements_text(
                              r.causes -> CAST(:cause AS int) -> 'evidence_trace_ids')))""";

    /** The same row {@link #ANSWER_PAGE} reads, so both reads cite one set of flagged sentences. */
    private static final String DETECTION_EVIDENCE = """
            SELECT DISTINCT ON (d.subject_trace_id, d.subject_span_id)
                   d.subject_trace_id, d.subject_span_id, d.evidence::text AS evidence
              FROM {detections} d
             WHERE d.project_id = :pid AND d.classifier_id = :cid
               AND d.subject_trace_id IN (:tids) AND d.subject_span_id IN (:sids)
             ORDER BY d.subject_trace_id, d.subject_span_id, d.subject_started_at DESC NULLS LAST, d.id DESC
            """;

    public record CauseRef(String reportId, int index) {}

    public record FlaggedAnswer(String traceId, String spanId) {}

    /**
     * Rows of any clear state, so a cleared answer still reads as it was when cited. @param flaggedAt null once the
     * span and trace aged out @param evidenceJson null when the detection row is gone
     */
    public record CitedAnswer(
            String traceId,
            String spanId,
            @Nullable String sessionId,
            @Nullable String flaggedAt,
            @Nullable String evidenceJson,
            boolean cleared) {}

    public record AnswerPage(List<CitedAnswer> rows, long total) {

        public AnswerPage {
            rows = List.copyOf(rows);
        }
    }

    private final JdbcClient jdbc;
    private final ClassifierDetectionWriteRepository detections;
    private final ToolErrorStateRepository states;

    public GroundednessRateRepository(JdbcClient jdbc, ClassifierDetectionWriteRepository detections) {
        this.jdbc = jdbc;
        this.detections = detections;
        this.states = ToolErrorStateRepository.forTable(jdbc, "groundedness_state", "call_site_id");
    }

    public ToolErrorStateRepository states() {
        return states;
    }

    /**
     * Anchors the replay window on the newest answer, so a project whose traffic is old still has a window holding it.
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

    /** {@code calls} is traces, {@code failures} flagged ones. */
    public List<HourlyToolTally> hourlyTallies(
            String projectId, String classifierId, String scorerVersion, Instant from) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS));
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
     * {@code windowFrom} is the replay window's start, so a trace's first scored answer is found over the rows the
     * tally read.
     */
    public List<String> scoredSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            Instant until) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS));
        return spell(SCORED_SINCE.replace("{detections}", table), projectId, classifierId, scorerVersion)
                .param("callSite", callSiteId)
                .param("from", Timestamp.from(windowFrom))
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) -> rs.getString("subject_trace_id"))
                .list();
    }

    public List<FlaggedAnswer> flaggedSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            Instant until) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS));
        return spell(FLAGGED_SINCE.replace("{detections}", table), projectId, classifierId, scorerVersion)
                .param("callSite", callSiteId)
                .param("from", Timestamp.from(windowFrom))
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) -> new FlaggedAnswer(rs.getString("subject_trace_id"), rs.getString("subject_span_id")))
                .list();
    }

    public AnswerPage answerPage(
            String projectId, String classifierId, String findingId, @Nullable CauseRef cause, int limit, int offset) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS));
        if (limit <= 0) return new AnswerPage(List.of(), 0);
        long[] total = {0};
        JdbcClient.StatementSpec spec = jdbc.sql(ANSWER_PAGE
                        .replace("{detections}", table)
                        .replace("{filter}", cause == null ? "" : CAUSE_FILTER))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("fid", findingId)
                .param("limit", limit)
                .param("offset", Math.max(0, offset));
        if (cause != null) {
            spec = spec.param("report", cause.reportId()).param("cause", cause.index());
        }
        List<CitedAnswer> rows = spec.query((rs, n) -> {
                    total[0] = rs.getLong("total");
                    OffsetDateTime started = rs.getObject("subject_started_at", OffsetDateTime.class);
                    return new CitedAnswer(
                            rs.getString("trace_id"),
                            rs.getString("span_id"),
                            rs.getString("subject_session_id"),
                            started == null ? null : started.toInstant().toString(),
                            rs.getString("evidence"),
                            rs.getString("cleared_at") != null);
                })
                .list();
        if (rows.isEmpty() && offset > 0) {
            return new AnswerPage(
                    List.of(),
                    answerPage(projectId, classifierId, findingId, cause, 1, 0).total());
        }
        return new AnswerPage(rows, total[0]);
    }

    public Map<SpanRef, String> detectionEvidence(String projectId, String classifierId, Collection<SpanRef> spans) {
        if (spans.isEmpty()) return Map.of();
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS));
        Set<SpanRef> wanted = new HashSet<>(spans);
        List<DetectionEvidence> rows = jdbc.sql(DETECTION_EVIDENCE.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("tids", wanted.stream().map(SpanRef::traceId).distinct().toList())
                .param("sids", wanted.stream().map(SpanRef::spanId).distinct().toList())
                .query((rs, n) -> new DetectionEvidence(
                        new SpanRef(rs.getString("subject_trace_id"), rs.getString("subject_span_id")),
                        rs.getString("evidence")))
                .list();
        Map<SpanRef, String> out = new HashMap<>();
        for (DetectionEvidence row : rows) {
            if (row.evidenceJson() != null && wanted.contains(row.ref())) out.put(row.ref(), row.evidenceJson());
        }
        return out;
    }

    private record DetectionEvidence(SpanRef ref, @Nullable String evidenceJson) {}

    private JdbcClient.StatementSpec spell(String sql, String projectId, String classifierId, String scorerVersion) {
        return jdbc.sql(sql).param("pid", projectId).param("cid", classifierId).param("scorerVersion", scorerVersion);
    }
}
