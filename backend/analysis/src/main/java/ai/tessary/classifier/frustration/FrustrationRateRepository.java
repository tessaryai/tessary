// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EarlierMessage;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * A conversation-hour is one trial: a conversation on one call site, in one UTC hour it has a scored turn there; it
 * fails when a turn in that hour was flagged and the flag stands. A flag on one call site never counts against another.
 * Rebuilt every pass, since a clear changes an old hour.
 */
@Repository
public class FrustrationRateRepository {

    /** Never alarms; the Tuning view counts it so the gap is visible. */
    public static final String UNASSIGNED = "";

    /**
     * A turn fails its hour only through its own detection, so a later turn's flag never fails an earlier hour of the
     * same conversation.
     */
    private static final String FLAGGED_TURN = """
            (a.frustrated AND EXISTS (
                SELECT 1 FROM {detections} d
                 WHERE d.project_id = a.project_id AND d.classifier_id = a.classifier_id
                   AND d.subject_trace_id = a.trace_id
                   AND COALESCE(d.evidence ->> 'call_site_id', '') = COALESCE(a.call_site_id, '')
                   AND d.cleared_at IS NULL))""";

    /** Hours are truncated in UTC: in a half-hour session zone every bucket would sit at :30. */
    static final String HOURLY_TALLIES = """
            WITH trial AS (
              SELECT a.conversation_id,
                     COALESCE(a.call_site_id, '') AS call_site_id,
                     date_trunc('hour', a.turn_started_at, 'UTC') AS bucket,
                     BOOL_OR({flaggedTurn}) AS flagged
                FROM frustration_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid
                 AND a.scorer_version = :scorerVersion AND a.turn_started_at >= :from
               GROUP BY 1, 2, 3)
            SELECT bucket, call_site_id,
                   COUNT(*) AS conversations,
                   COUNT(*) FILTER (WHERE flagged) AS frustrated
              FROM trial
             GROUP BY 1, 2
             ORDER BY 1, 2
            """.replace("{flaggedTurn}", FLAGGED_TURN);

    /**
     * Per conversation-hour, its earliest flagged turn when one stands, else its latest turn. Reads neither
     * {@code request} nor {@code response}.
     */
    static final String TRIALS_SINCE = """
            WITH turn AS (
              SELECT a.conversation_id, a.trace_id, a.turn_started_at,
                     date_trunc('hour', a.turn_started_at, 'UTC') AS bucket,
                     {flaggedTurn} AS flagged
                FROM frustration_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid AND a.scorer_version = :scorerVersion
                 AND COALESCE(a.call_site_id, '') = :callSite
                 AND a.turn_started_at >= :onset AND a.turn_started_at < :until),
            ranked AS (
              SELECT t.*, ROW_NUMBER() OVER (
                         PARTITION BY t.conversation_id, t.bucket
                         ORDER BY t.flagged DESC,
                                  CASE WHEN t.flagged THEN t.turn_started_at END ASC,
                                  t.turn_started_at DESC, t.trace_id DESC) AS rn
                FROM turn t)
            SELECT bucket, conversation_id, trace_id, flagged
              FROM ranked
             WHERE rn = 1
             ORDER BY bucket DESC, flagged DESC, turn_started_at DESC, conversation_id DESC
            """.replace("{flaggedTurn}", FLAGGED_TURN);

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

    /** One trial of a spell: the turn that stands for a conversation in one hour. */
    public record Trial(String bucket, String conversationId, String traceId, boolean flagged) {}

    /** @param message null once retention cleared the request or no assessment is kept */
    public record FlaggedTurn(
            String traceId,
            @Nullable String conversationId,
            @Nullable Double score,
            @Nullable String callSiteId,
            @Nullable String startedAt,
            boolean cleared,
            @Nullable String message) {}

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<EarlierMessage>> EARLIER = new TypeReference<>() {};

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

    /** {@code calls} is conversation-hours, {@code failures} frustrated ones. */
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

    /** Newest hour first; within an hour, frustrated conversations first. */
    public List<Trial> trialsSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant onset,
            Instant until) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.FRUSTRATION));
        return jdbc.sql(TRIALS_SINCE.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("callSite", callSiteId)
                .param("onset", Timestamp.from(onset))
                .param("until", Timestamp.from(until))
                .query((rs, n) -> new Trial(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        rs.getString("conversation_id"),
                        rs.getString("trace_id"),
                        rs.getBoolean("flagged")))
                .list();
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

    /**
     * The {@code earlier_messages} each turn was judged against, read from the stored scorer request, keyed by trace
     * id; the newest assessment of that trace on the call site decides. If retention cleared its request, the key has
     * no entry.
     */
    public Map<String, List<EarlierMessage>> earlierMessages(
            String projectId, String classifierId, String callSiteId, Collection<String> traceIds) {
        Map<String, List<EarlierMessage>> out = new HashMap<>();
        if (traceIds.isEmpty()) return out;
        Set<String> decided = new HashSet<>();
        jdbc.sql("SELECT a.trace_id, (a.request -> 'state' -> 'earlier_messages')::text AS earlier"
                        + " FROM frustration_assessment a"
                        + " WHERE a.project_id = :pid AND a.classifier_id = :cid"
                        + " AND COALESCE(a.call_site_id, '') = :callSite"
                        + " AND a.trace_id IN (:traces)"
                        + " ORDER BY a.created_at DESC, a.id DESC")
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("traces", Set.copyOf(traceIds))
                .query(rs -> {
                    String trace = rs.getString("trace_id");
                    List<EarlierMessage> messages = parseEarlier(rs.getString("earlier"));
                    if (decided.add(trace) && messages != null) out.put(trace, messages);
                });
        return out;
    }

    private static @Nullable List<EarlierMessage> parseEarlier(@Nullable String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, EARLIER);
        } catch (JsonProcessingException e) {
            return null;
        }
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
}
