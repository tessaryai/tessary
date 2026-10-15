// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A session is one trial: a conversation on one call site, bucketed in the hour of its first scored turn there; a flag
 * on one call site never counts against another. Rebuilt from these counts every pass, since a later turn can flag an
 * old conversation.
 */
@Repository
public class FrustrationRateRepository {

    /** Never alarms; the Tuning view counts it so the gap is visible. */
    public static final String UNASSIGNED = "";

    /** Hours are truncated in UTC: in a half-hour session zone every bucket would sit at :30. */
    static final String HOURLY_TALLIES = """
            WITH conv AS (
              SELECT a.conversation_id,
                     COALESCE(a.call_site_id, '') AS call_site_id,
                     MIN(a.turn_started_at) AS first_scored_at,
                     BOOL_OR(a.frustrated) AS any_flag
                FROM frustration_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid
                 AND a.scorer_version = :scorerVersion AND a.turn_started_at >= :from
               GROUP BY a.conversation_id, COALESCE(a.call_site_id, ''))
            SELECT date_trunc('hour', c.first_scored_at, 'UTC') AS bucket, c.call_site_id,
                   COUNT(*) AS conversations,
                   COUNT(*) FILTER (WHERE c.any_flag AND EXISTS (
                       SELECT 1 FROM {detections} d
                        WHERE d.project_id = :pid AND d.classifier_id = :cid
                          AND d.subject_session_id = c.conversation_id
                          AND COALESCE(d.evidence ->> 'call_site_id', '') = c.call_site_id
                          AND d.cleared_at IS NULL)) AS frustrated
              FROM conv c
             GROUP BY 1, 2
             ORDER BY 1, 2
            """;

    /**
     * {@code :windowFrom} is the replay window's start, so the first scored turn is found over the rows the tally read.
     * Reads neither {@code request} nor {@code response}.
     */
    private static final String SPELL_SESSIONS = """
            WITH conv AS (
              SELECT a.conversation_id,
                     MIN(a.turn_started_at) AS first_scored_at
                FROM frustration_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid
                 AND a.scorer_version = :scorerVersion AND a.turn_started_at >= :windowFrom
                 AND COALESCE(a.call_site_id, '') = :callSite
               GROUP BY a.conversation_id
              {frustrated})
            SELECT c.conversation_id{flaggedColumn}
              FROM conv c
              {flaggedJoin}
             WHERE c.first_scored_at >= :onset AND c.first_scored_at < :until
             ORDER BY c.first_scored_at DESC, c.conversation_id DESC
            """;

    private static final String FLAGGED_JOIN = """
            JOIN LATERAL (
                SELECT d.subject_trace_id
                  FROM {detections} d
                 WHERE d.project_id = :pid AND d.classifier_id = :cid
                   AND d.subject_session_id = c.conversation_id
                   AND COALESCE(d.evidence ->> 'call_site_id', '') = :callSite AND d.cleared_at IS NULL
                 ORDER BY d.subject_started_at DESC NULLS LAST, d.id DESC
                 LIMIT 1) d ON true""";

    static final String FRUSTRATED_SINCE = SPELL_SESSIONS
            .replace("{frustrated}", "HAVING BOOL_OR(a.frustrated)")
            .replace("{flaggedColumn}", ", d.subject_trace_id")
            .replace("{flaggedJoin}", FLAGGED_JOIN);

    static final String SCORED_SINCE = SPELL_SESSIONS
            .replace("{frustrated}", "")
            .replace("{flaggedColumn}", "")
            .replace("{flaggedJoin}", "");

    /**
     * With {@code {filter}} set, only one RCA cause's share, read off the stored report so the request carries two
     * short values rather than every id.
     */
    private static final String WITNESS_PAGE = """
            SELECT e.trace_id, COUNT(*) OVER () AS total
              FROM finding_evidence e
              LEFT JOIN LATERAL (
                SELECT d.subject_session_id, d.subject_started_at
                  FROM {detections} d
                 WHERE d.project_id = e.project_id AND d.classifier_id = :cid AND d.subject_trace_id = e.trace_id
                   AND COALESCE(d.evidence ->> 'call_site_id', '') = :callSite
                 ORDER BY d.subject_started_at DESC NULLS LAST, d.id DESC
                 LIMIT 1) d ON true
             WHERE e.project_id = :pid AND e.finding_id = :fid AND e.role = 'witness'
               AND e.trace_id IS NOT NULL AND e.span_id IS NULL
               {filter}
             ORDER BY d.subject_started_at DESC NULLS LAST, e.trace_id DESC
             LIMIT :limit OFFSET :offset
            """;

    public record WitnessPage(List<String> traceIds, long total) {

        public WitnessPage {
            traceIds = List.copyOf(traceIds);
        }
    }

    public record FrustratedConversation(String conversationId, String flaggedTraceId) {}

    /** @param message null once retention cleared the request or no assessment is kept */
    public record FlaggedTurn(
            String traceId,
            @Nullable String conversationId,
            @Nullable Double score,
            @Nullable String callSiteId,
            @Nullable String startedAt,
            boolean cleared,
            @Nullable String message) {}

    public record Reset(String resetAt, @Nullable String note) {}

    private final JdbcClient jdbc;
    private final ClassifierDetectionWriteRepository detections;
    private final ToolErrorStateRepository states;

    public FrustrationRateRepository(JdbcClient jdbc, ClassifierDetectionWriteRepository detections) {
        this.jdbc = jdbc;
        this.detections = detections;
        this.states = ToolErrorStateRepository.forTable(jdbc, "frustration_state", "call_site_id");
    }

    public ToolErrorStateRepository states() {
        return states;
    }

    /**
     * Anchors the replay window on the newest turn, so a project whose traffic is old still has a window holding it.
     */
    public Optional<Instant> newestTurnAt(String projectId, String classifierId, String scorerVersion) {
        return jdbc.sql("""
                        SELECT MAX(turn_started_at) AS newest
                          FROM frustration_assessment
                         WHERE project_id = :pid AND classifier_id = :cid AND scorer_version = :scorerVersion
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .query((rs, n) -> rs.getObject("newest", OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /** {@code calls} is sessions, {@code failures} frustrated ones. */
    public List<HourlyToolTally> hourlyTallies(
            String projectId, String classifierId, String scorerVersion, Instant from) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.FRUSTRATION));
        return jdbc.sql(HOURLY_TALLIES.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("from", Timestamp.from(from))
                .query((rs, n) -> new HourlyToolTally(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        rs.getString("call_site_id"),
                        rs.getLong("conversations"),
                        rs.getLong("frustrated")))
                .list();
    }

    public List<FrustratedConversation> frustratedSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            Instant until) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.FRUSTRATION));
        return spellSessions(FRUSTRATED_SINCE.replace("{detections}", table), projectId, classifierId, scorerVersion)
                .param("callSite", callSiteId)
                .param("windowFrom", Timestamp.from(windowFrom))
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) ->
                        new FrustratedConversation(rs.getString("conversation_id"), rs.getString("subject_trace_id")))
                .list();
    }

    public List<String> scoredSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            Instant until) {
        return spellSessions(SCORED_SINCE, projectId, classifierId, scorerVersion)
                .param("callSite", callSiteId)
                .param("windowFrom", Timestamp.from(windowFrom))
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) -> rs.getString("conversation_id"))
                .list();
    }

    private JdbcClient.StatementSpec spellSessions(
            String sql, String projectId, String classifierId, String scorerVersion) {
        return jdbc.sql(sql).param("pid", projectId).param("cid", classifierId).param("scorerVersion", scorerVersion);
    }

    private static final String CAUSE_FILTER = """
            AND EXISTS (
                SELECT 1 FROM rca_report r
                 WHERE r.id = :report AND r.project_id = e.project_id AND r.finding_id = e.finding_id
                   AND (d.subject_session_id IN (
                            SELECT jsonb_array_elements_text(r.causes -> CAST(:cause AS int) -> 'evidence_session_ids'))
                        OR e.trace_id IN (
                            SELECT jsonb_array_elements_text(r.causes -> CAST(:cause AS int) -> 'evidence_trace_ids'))))""";

    public record CauseRef(String reportId, int index) {}

    /** {@code callSiteId} is the finding's: a witness trace can also hold a flag on another call site. */
    public WitnessPage witnessPage(
            String projectId,
            String classifierId,
            String callSiteId,
            String findingId,
            @Nullable CauseRef cause,
            int limit,
            int offset) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.FRUSTRATION));
        if (limit <= 0) return new WitnessPage(List.of(), 0);
        JdbcClient.StatementSpec spec = jdbc.sql(WITNESS_PAGE
                        .replace("{detections}", table)
                        .replace("{filter}", cause == null ? "" : CAUSE_FILTER))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("fid", findingId)
                .param("limit", limit)
                .param("offset", Math.max(0, offset));
        if (cause != null) {
            spec = spec.param("report", cause.reportId()).param("cause", cause.index());
        }
        long[] total = {0};
        List<String> ids = spec.query((rs, n) -> {
                    total[0] = rs.getLong("total");
                    return rs.getString("trace_id");
                })
                .list();
        if (ids.isEmpty() && offset > 0) {
            return new WitnessPage(
                    List.of(),
                    witnessPage(projectId, classifierId, callSiteId, findingId, cause, 1, 0)
                            .total());
        }
        return new WitnessPage(ids, total[0]);
    }

    /** Rows of any clear state, so a cleared session still reads as it was when cited. */
    public Map<String, FlaggedTurn> flaggedTurns(
            String projectId, String classifierId, String callSiteId, List<String> traceIds) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.FRUSTRATION));
        Map<String, FlaggedTurn> out = new HashMap<>();
        if (traceIds.isEmpty()) return out;
        jdbc.sql("SELECT d.subject_trace_id, d.subject_session_id, d.evidence ->> 'score' AS score,"
                        + " d.evidence ->> 'call_site_id' AS call_site_id, d.subject_started_at, d.cleared_at,"
                        + " (SELECT a.request -> 'state' ->> 'current_user_message' FROM frustration_assessment a"
                        + "   WHERE a.project_id = d.project_id AND a.classifier_id = d.classifier_id"
                        + "     AND a.trace_id = d.subject_trace_id AND COALESCE(a.call_site_id, '') = :callSite"
                        + "   ORDER BY a.created_at DESC LIMIT 1) AS message"
                        + " FROM " + table + " d"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :cid AND d.subject_trace_id IN (:traces)"
                        + " AND COALESCE(d.evidence ->> 'call_site_id', '') = :callSite")
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("traces", traceIds)
                .query((rs, n) -> {
                    String score = rs.getString("score");
                    OffsetDateTime started = rs.getObject("subject_started_at", OffsetDateTime.class);
                    return new FlaggedTurn(
                            rs.getString("subject_trace_id"),
                            rs.getString("subject_session_id"),
                            score == null ? null : parseScore(score),
                            rs.getString("call_site_id"),
                            started == null ? null : started.toInstant().toString(),
                            rs.getString("cleared_at") != null,
                            rs.getString("message"));
                })
                .list()
                .forEach(t -> out.put(t.traceId(), t));
        return out;
    }

    /** @param sessionId null when the flagged trace carries no session */
    public record ConversationContext(@Nullable String sessionId, List<String> priorTraceIds) {

        public ConversationContext {
            priorTraceIds = List.copyOf(priorTraceIds);
        }
    }

    /** Ordered in event time, so an upload that stored its turns out of order still reads them in order. */
    public Map<String, ConversationContext> conversationContext(
            String projectId, String callSiteId, List<String> traceIds, int before) {
        Map<String, ConversationContext> out = new HashMap<>();
        if (traceIds.isEmpty()) return out;
        Map<String, String> sessions = new HashMap<>();
        Map<String, List<String>> prior = new HashMap<>();
        jdbc.sql("""
                        SELECT f.id AS flagged_id, f.session_id AS session_id, p.id AS prior_id, p.started_at AS prior_at
                          FROM trace f
                          LEFT JOIN LATERAL (
                                SELECT t.id, t.started_at FROM trace t
                                 WHERE t.project_id = f.project_id
                                   AND t.parent_trace_id IS NULL
                                   AND t.session_id = f.session_id
                                   AND (t.started_at, t.id) < (f.started_at, f.id)
                                   AND EXISTS (SELECT 1 FROM span c
                                                WHERE c.project_id = t.project_id AND c.trace_id = t.id
                                                  AND c.call_site_id = :callSite AND c.kind IN ('llm', 'agent'))
                                 ORDER BY t.started_at DESC, t.id DESC
                                 LIMIT :before) p ON TRUE
                         WHERE f.project_id = :pid AND f.id IN (:traces)
                         ORDER BY f.id, p.started_at, p.id
                        """)
                .param("pid", projectId)
                .param("traces", traceIds)
                .param("callSite", callSiteId)
                .param("before", before)
                .query(rs -> {
                    String flagged = rs.getString("flagged_id");
                    sessions.put(flagged, rs.getString("session_id"));
                    List<String> ids = prior.computeIfAbsent(flagged, k -> new ArrayList<>());
                    String priorId = rs.getString("prior_id");
                    if (priorId != null) ids.add(priorId);
                });
        for (Map.Entry<String, List<String>> e : prior.entrySet()) {
            out.put(e.getKey(), new ConversationContext(sessions.get(e.getKey()), e.getValue()));
        }
        return out;
    }

    private static @Nullable Double parseScore(String score) {
        try {
            return Double.valueOf(score);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Reset> resets(String projectId) {
        Map<String, Reset> out = new HashMap<>();
        jdbc.sql("""
                        SELECT call_site_id, reset_at, reset_note
                          FROM frustration_state
                         WHERE project_id = :pid AND reset_at IS NOT NULL
                        """)
                .param("pid", projectId)
                .query((rs, n) -> Map.entry(
                        rs.getString("call_site_id"), new Reset(rs.getString("reset_at"), rs.getString("reset_note"))))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
