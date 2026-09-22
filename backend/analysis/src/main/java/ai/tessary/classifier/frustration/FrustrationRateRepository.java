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
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the Frustration rate test replays: per call site and per hour, how many conversations were scored and how
 * many of them are frustrated now.
 *
 * <p><b>A conversation is one trial, on the call site of its first scored turn.</b> There is no conversation
 * table: every fact is a {@code GROUP BY conversation_id} over {@code frustration_assessment} under the current
 * scorer. A conversation is bucketed in the hour of its first scored turn and assigned to that turn's call site
 * for good, so a flag raised on a later turn, on any call site, counts against the stream the conversation was a
 * trial in. It is a failure while it carries an uncleared detection; a {@code false_alarm} resolve clears it and
 * the next replay counts it calm again.
 *
 * <p><b>Read, not accumulated.</b> An hour's tally is not final when first read (a later turn can flag an old
 * conversation), which is why the replay rebuilds every pass from these counts.
 */
@Repository
public class FrustrationRateRepository {

    /**
     * The key a conversation whose first scored turn has no call site is tallied under. It never alarms (the
     * service drops it before the replay); the Tuning view counts it so the gap is visible.
     */
    public static final String UNASSIGNED = "";

    /**
     * The replay aggregate. The {@code WHERE} runs on the {@code (project_id, classifier_id, turn_started_at)}
     * index; the {@code EXISTS} is evaluated only for conversations with a flagged turn and runs on the detection
     * table's partial index over uncleared rows. {@code {detections}} is the registered detection table. Hours
     * are truncated in UTC, not the session's zone, which in a half-hour zone would put every bucket at :30.
     */
    static final String HOURLY_TALLIES = """
            WITH conv AS (
              SELECT a.conversation_id,
                     MIN(a.turn_started_at) AS first_scored_at,
                     (ARRAY_AGG(COALESCE(a.call_site_id, '') ORDER BY a.turn_started_at, a.id))[1] AS call_site_id,
                     BOOL_OR(a.frustrated) AS any_flag
                FROM frustration_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid
                 AND a.scorer_version = :scorerVersion AND a.turn_started_at >= :from
               GROUP BY a.conversation_id)
            SELECT date_trunc('hour', c.first_scored_at, 'UTC') AS bucket, c.call_site_id,
                   COUNT(*) AS conversations,
                   COUNT(*) FILTER (WHERE c.any_flag AND EXISTS (
                       SELECT 1 FROM {detections} d
                        WHERE d.project_id = :pid AND d.classifier_id = :cid
                          AND d.subject_session_id = c.conversation_id AND d.cleared_at IS NULL)) AS frustrated
              FROM conv c
             GROUP BY 1, 2
             ORDER BY 1, 2
            """;

    /**
     * The frustrated conversations a finding cites: the tally's own grouping, so a conversation is listed on the
     * call site and in the hour it was a trial in, joined to its newest uncleared flag for the turn that fired.
     * {@code :windowFrom} is the replay window's start, so the first scored turn is found over the same rows the
     * tally read; {@code :onset} then keeps the conversations since the spell began. Reads neither {@code request}
     * nor {@code response}.
     */
    static final String FRUSTRATED_SINCE = """
            WITH conv AS (
              SELECT a.conversation_id,
                     MIN(a.turn_started_at) AS first_scored_at,
                     (ARRAY_AGG(COALESCE(a.call_site_id, '') ORDER BY a.turn_started_at, a.id))[1] AS call_site_id
                FROM frustration_assessment a
               WHERE a.project_id = :pid AND a.classifier_id = :cid
                 AND a.scorer_version = :scorerVersion AND a.turn_started_at >= :windowFrom
               GROUP BY a.conversation_id
              HAVING BOOL_OR(a.frustrated))
            SELECT c.conversation_id, d.subject_trace_id
              FROM conv c
              JOIN LATERAL (
                SELECT d.subject_trace_id
                  FROM {detections} d
                 WHERE d.project_id = :pid AND d.classifier_id = :cid
                   AND d.subject_session_id = c.conversation_id AND d.cleared_at IS NULL
                 ORDER BY d.subject_started_at DESC NULLS LAST, d.id DESC
                 LIMIT 1) d ON true
             WHERE c.call_site_id = :callSite AND c.first_scored_at >= :onset
             ORDER BY c.first_scored_at DESC, c.conversation_id DESC
             LIMIT :limit
            """;

    /** One frustrated conversation a finding cites, and the flagged turn inside it. */
    public record FrustratedConversation(String conversationId, String flaggedTraceId) {}

    /**
     * One flagged turn as its detection row recorded it: the conversation it belongs to, the score, the turn's
     * own call site and when it happened. {@code cleared} once a {@code false_alarm} resolve cleared the
     * conversation.
     */
    /**
     * One flagged turn.
     *
     * @param message the user message as it was scored, for a one-line preview; null once retention cleared
     *     the stored request, or when no assessment is kept for the turn
     */
    public record FlaggedTurn(
            String traceId,
            @Nullable String conversationId,
            @Nullable Double score,
            @Nullable String callSiteId,
            @Nullable String startedAt,
            boolean cleared,
            @Nullable String message) {}

    /** A human reset on one call site's state row, for the Tuning view. */
    public record Reset(String resetAt, @Nullable String note) {}

    private final JdbcClient jdbc;
    private final ClassifierDetectionWriteRepository detections;
    private final ToolErrorStateRepository states;

    public FrustrationRateRepository(JdbcClient jdbc, ClassifierDetectionWriteRepository detections) {
        this.jdbc = jdbc;
        this.detections = detections;
        this.states = ToolErrorStateRepository.forTable(jdbc, "frustration_state", "call_site_id");
    }

    /** The CUSUM state the shared engine carries between passes, one row per call site. */
    public ToolErrorStateRepository states() {
        return states;
    }

    /**
     * The newest scored turn under {@code scorerVersion}: the anchor the replay window reads back from, as
     * tool_error anchors on its newest call, so a project whose traffic is old still has a window holding it.
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

    /**
     * Scored and frustrated conversations per call site per hour since {@code from}, oldest first, as the tallies
     * {@code ToolErrorTrend} replays: {@code calls} is conversations, {@code failures} is frustrated ones. A
     * conversation with no call site on its first scored turn is keyed {@link #UNASSIGNED}. Empty while no
     * frustration detection table is registered, since no conversation can then be a failure or a trial anyone
     * acts on.
     */
    public List<HourlyToolTally> hourlyTallies(
            String projectId, String classifierId, String scorerVersion, Instant from) {
        String table = detections.tableFor(BuiltInDetector.Kind.FRUSTRATION);
        if (table == null) return List.of();
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

    /**
     * The frustrated conversations of one call site's stream since {@code onset}, newest first, at most
     * {@code limit}. Empty while no frustration detection table is registered.
     */
    public List<FrustratedConversation> frustratedSince(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSiteId,
            Instant windowFrom,
            Instant onset,
            int limit) {
        String table = detections.tableFor(BuiltInDetector.Kind.FRUSTRATION);
        if (table == null || limit <= 0) return List.of();
        return jdbc.sql(FRUSTRATED_SINCE.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("windowFrom", Timestamp.from(windowFrom))
                .param("callSite", callSiteId)
                .param("onset", Timestamp.from(onset))
                .param("limit", limit)
                .query((rs, n) ->
                        new FrustratedConversation(rs.getString("conversation_id"), rs.getString("subject_trace_id")))
                .list();
    }

    /**
     * The detection rows behind {@code traceIds}, by trace: what a finding's page shows beside each witness. Rows
     * of any clear state, so a conversation a resolve cleared still reads as what it was when the finding cited
     * it.
     */
    public Map<String, FlaggedTurn> flaggedTurns(String projectId, String classifierId, List<String> traceIds) {
        String table = detections.tableFor(BuiltInDetector.Kind.FRUSTRATION);
        Map<String, FlaggedTurn> out = new HashMap<>();
        if (table == null || traceIds.isEmpty()) return out;
        jdbc.sql("SELECT d.subject_trace_id, d.subject_session_id, d.evidence ->> 'score' AS score,"
                        + " d.evidence ->> 'call_site_id' AS call_site_id, d.subject_started_at, d.cleared_at,"
                        + " (SELECT a.request -> 'state' ->> 'current_user_message' FROM frustration_assessment a"
                        + "   WHERE a.project_id = d.project_id AND a.classifier_id = d.classifier_id"
                        + "     AND a.trace_id = d.subject_trace_id"
                        + "   ORDER BY a.created_at DESC LIMIT 1) AS message"
                        + " FROM " + table + " d"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :cid AND d.subject_trace_id IN (:traces)")
                .param("pid", projectId)
                .param("cid", classifierId)
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
     * The turns shown around a flagged one: the flagged trace's session, for a link to the whole
     * conversation, and the ids of the {@code before} top-level traces that started before it in the same
     * conversation, oldest first.
     *
     * @param sessionId null when the flagged trace carries no session
     */
    public record ConversationContext(@Nullable String sessionId, List<String> priorTraceIds) {

        public ConversationContext {
            priorTraceIds = List.copyOf(priorTraceIds);
        }
    }

    /**
     * {@link ConversationContext} for each of {@code traceIds}, keyed by trace id. The conversation key is
     * {@code COALESCE(thread_id, session_id)}, the grain the classifier scored at, read in event time so
     * an upload that stored its turns out of order still reads them in the order they happened.
     */
    public Map<String, ConversationContext> conversationContext(String projectId, List<String> traceIds, int before) {
        Map<String, ConversationContext> out = new HashMap<>();
        if (traceIds.isEmpty() || before <= 0) return out;
        Map<String, String> sessions = new HashMap<>();
        Map<String, List<String>> prior = new HashMap<>();
        jdbc.sql("""
                        SELECT f.id AS flagged_id, f.session_id AS session_id, p.id AS prior_id, p.started_at AS prior_at
                          FROM trace f
                          LEFT JOIN LATERAL (
                                SELECT t.id, t.started_at FROM trace t
                                 WHERE t.project_id = f.project_id
                                   AND t.parent_trace_id IS NULL
                                   AND COALESCE(t.thread_id, t.session_id) = COALESCE(f.thread_id, f.session_id)
                                   AND (t.started_at, t.id) < (f.started_at, f.id)
                                 ORDER BY t.started_at DESC, t.id DESC
                                 LIMIT :before) p ON TRUE
                         WHERE f.project_id = :pid AND f.id IN (:traces)
                         ORDER BY f.id, p.started_at, p.id
                        """)
                .param("pid", projectId)
                .param("traces", traceIds)
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

    /** Each call site's last human reset, by call site. */
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
