// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import ai.tessary.classifier.worker.ClassifierArming;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the refs each finding hit and writes {@code end_user_hit} and {@code end_user_hit_summary}. A ref resolves to
 * {@code COALESCE(span.user_id, trace.user_id)}, the route of its trace (trace name, call site) and the UTC day of the
 * trace start. User keys are lower-case hex.
 */
@Repository
public class EndUserHitRepository {

    /** The route of a ref to a session only; registered routes start at 1. */
    public static final int SESSION_ONLY_ROUTE = 0;

    public static final String EVIDENCE = "evidence";
    public static final String DETECTION = "detection";

    private static final HexFormat HEX = HexFormat.of();

    private static final String DAY = "floor(extract(epoch FROM t.started_at) / 86400)::int";

    private static final TypeReference<List<SessionRoutes>> SESSIONS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public EndUserHitRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.named = named;
        this.mapper = mapper;
    }

    public record Finding(String id, String classifierKey, String causeKind, boolean faceted, String subjectId) {

        public HitFinding hitFinding() {
            return new HitFinding(classifierKey, causeKind, faceted);
        }
    }

    /** Refs of one role that resolve to one user ID text, trace route and UTC day. */
    public record RefGroup(
            String role,
            @Nullable String userId,
            @Nullable String traceName,
            @Nullable String callSiteId,
            int day,
            int refs) {}

    public record SessionRefs(String role, String sessionId, int refs) {}

    /**
     * @param userId the ID of the session's earliest trace that has one
     * @param lastDay the UTC day of the session's last trace
     */
    public record SessionOwner(String sessionId, @Nullable String userId, int lastDay) {}

    /**
     * @param sessionRoutes on {@link #SESSION_ONLY_ROUTE}, one entry per session merged into the row; else empty
     */
    public record HitRow(
            String role,
            String userKey,
            int day,
            int routeNo,
            int refs,
            String source,
            List<SessionRoutes> sessionRoutes) {}

    /**
     * The refs of one session-only ref group and the traces of its session per route number, as the reference's
     * {@code sess_routes}: every trace of the session, with or without a user ID.
     */
    public record SessionRoutes(int refs, Map<Integer, Integer> routes) {}

    /** The traces of one session on one route. */
    public record SessionRouteCount(
            String sessionId,
            @Nullable String traceName,
            @Nullable String callSiteId,
            int traces) {}

    public record Summary(
            String findingId, int refsTotal, int refsNoUserId, int sessionOnlyRefs, boolean complete, String hitRule) {}

    /**
     * {@code created_at} is text written by {@code Instant.toString()}, which drops a zero fraction, so the exact bound
     * is compared as {@code timestamptz}. The date of {@code from} is a prefix every later rendering sorts at or after,
     * which keeps the scan on {@code ix_finding_evidence_project_created}.
     */
    public List<String> findingsWithEvidenceSince(String projectId, Instant from) {
        return jdbc.sql("""
                SELECT DISTINCT finding_id FROM finding_evidence
                 WHERE project_id = :pid AND created_at >= :fromDate AND CAST(created_at AS timestamptz) >= :from
                 ORDER BY finding_id
                """)
                .param("pid", projectId)
                .param("fromDate", from.atOffset(ZoneOffset.UTC).toLocalDate().toString())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .query(String.class)
                .list();
    }

    public List<String> openFindings(String projectId) {
        return jdbc.sql("SELECT id FROM finding WHERE project_id = :pid AND status = 'open' ORDER BY id")
                .param("pid", projectId)
                .query(String.class)
                .list();
    }

    public List<Finding> findings(String projectId, Collection<String> ids) {
        return jdbc.sql("""
                SELECT id, classifier_key, COALESCE(payload ->> 'cause_kind', classifier_key) AS cause_kind,
                       payload ->> 'facet' IS NOT NULL AS faceted, subject_id
                  FROM finding
                 WHERE project_id = :pid AND id = ANY(:ids)
                 ORDER BY id
                """)
                .param("pid", projectId)
                .param("ids", ids.toArray(String[]::new))
                .query((rs, n) -> new Finding(
                        rs.getString("id"),
                        rs.getString("classifier_key"),
                        rs.getString("cause_kind"),
                        rs.getBoolean("faceted"),
                        rs.getString("subject_id")))
                .list();
    }

    /** With {@code onePerTrace}, the trace row of a trace is kept over its span rows. */
    public List<RefGroup> evidenceGroups(String projectId, String findingId, boolean onePerTrace) {
        String refs = onePerTrace ? """
                  SELECT DISTINCT ON (role, trace_id) project_id, role, trace_id, span_id FROM finding_evidence
                   WHERE project_id = :pid AND finding_id = :fid AND trace_id IS NOT NULL
                   ORDER BY role, trace_id, span_id NULLS FIRST
                  """ : """
                  SELECT project_id, role, trace_id, span_id FROM finding_evidence
                   WHERE project_id = :pid AND finding_id = :fid AND trace_id IS NOT NULL
                  """;
        return jdbc.sql(grouped("e.role") + " FROM (" + refs + ") e"
                        + " JOIN trace t ON t.project_id = e.project_id AND t.id = e.trace_id AND NOT t.is_deleted"
                        + " LEFT JOIN span s ON s.project_id = e.project_id AND s.trace_id = e.trace_id"
                        + " AND s.id = e.span_id AND NOT s.is_deleted"
                        + " GROUP BY 1, 2, 3, 4, 5")
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> group(rs))
                .list();
    }

    public List<SessionRefs> sessionRefs(String projectId, String findingId) {
        return jdbc.sql("""
                SELECT role, session_id, COUNT(*)::int AS refs FROM finding_evidence
                 WHERE project_id = :pid AND finding_id = :fid AND trace_id IS NULL
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """)
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> new SessionRefs(rs.getString("role"), rs.getString("session_id"), rs.getInt("refs")))
                .list();
    }

    /** Sessions with no live trace are left out. */
    public List<SessionOwner> sessionOwners(String projectId, Collection<String> sessionIds) {
        return jdbc.sql("""
                SELECT session_id,
                       (array_agg(user_id ORDER BY started_at, id) FILTER (WHERE btrim(user_id) <> ''))[1] AS user_id,
                       floor(extract(epoch FROM max(started_at)) / 86400)::int AS last_day
                  FROM trace
                 WHERE project_id = :pid AND session_id = ANY(:sessions) AND NOT is_deleted
                 GROUP BY session_id
                """)
                .param("pid", projectId)
                .param("sessions", sessionIds.toArray(String[]::new))
                .query((rs, n) ->
                        new SessionOwner(rs.getString("session_id"), rs.getString("user_id"), rs.getInt("last_day")))
                .list();
    }

    /** The live traces of each session per route, read with the filter of {@link #sessionOwners}. */
    public List<SessionRouteCount> sessionRouteCounts(String projectId, Collection<String> sessionIds) {
        return jdbc.sql("""
                SELECT session_id, name, call_site_id, COUNT(*)::int AS traces
                  FROM trace
                 WHERE project_id = :pid AND session_id = ANY(:sessions) AND NOT is_deleted
                 GROUP BY 1, 2, 3
                """)
                .param("pid", projectId)
                .param("sessions", sessionIds.toArray(String[]::new))
                .query((rs, n) -> new SessionRouteCount(
                        rs.getString("session_id"),
                        rs.getString("name"),
                        rs.getString("call_site_id"),
                        rs.getInt("traces")))
                .list();
    }

    /**
     * The failing outputs the detector counts: the filters of {@code MalformedOutputRateRepository.hourlyTallies} (a
     * call site with an output schema, a non-empty output, a span stored before the classifier's checked-before
     * cursor), on the finding's call site from its onset, and stored before the finding was last refreshed.
     */
    public List<RefGroup> malformedOutputGroups(String projectId, String findingId) {
        return jdbc.sql(grouped("'" + Role.WITNESS + "'") + """
                  FROM finding f
                  JOIN malformed_output_detection d ON d.project_id = f.project_id AND d.classifier_id = f.subject_id
                  JOIN span s ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id
                   AND s.id = d.subject_span_id
                  JOIN call_site cs ON cs.project_id = s.project_id AND cs.id = s.call_site_id
                   AND cs.output_schema IS NOT NULL
                  JOIN job j ON j.kind = 'classifier' AND j.project_id = d.project_id AND j.dedupe_key = d.classifier_id
                  JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id AND NOT t.is_deleted
                 WHERE f.project_id = :pid AND f.id = :fid
                   AND s.call_site_id = f.call_site_id
                   AND s.started_at >= CAST(f.onset_at AS timestamptz)
                   AND s.created_at < CAST(j.cursor_at AS timestamptz)
                   AND s.created_at < CAST(f.last_seen_at AS timestamptz)
                   AND s.output_preview IS NOT NULL AND s.output_preview <> ''
                 GROUP BY 1, 2, 3, 4, 5
                """)
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> group(rs))
                .list();
    }

    /**
     * The detections of the finding's facet, as {@code ClassifierDetectionWriteRepository.countFacetWindows} counts
     * them: its pattern at its call site, in the event-time windows from its onset through the window of its last
     * detection, at high confidence when the arming reads only that.
     */
    public List<RefGroup> secretLeakGroups(String projectId, String findingId, boolean highOnly) {
        return jdbc.sql(grouped("'" + Role.WITNESS + "'") + """
                  FROM finding f
                  JOIN secret_leak_detection d ON d.project_id = f.project_id AND d.classifier_id = f.subject_id
                  JOIN span s ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id
                   AND s.id = d.subject_span_id
                  JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id AND NOT t.is_deleted
                 WHERE f.project_id = :pid AND f.id = :fid
                   AND d.evidence ->> :facetKey = f.payload ->> 'facet'
                   AND s.call_site_id IS NOT DISTINCT FROM f.call_site_id
                   AND d.subject_started_at >= CAST(f.onset_at AS timestamptz)
                   AND d.subject_started_at < to_timestamp(
                         (floor(extract(epoch FROM CAST(f.last_seen_at AS timestamptz))
                                / CAST(f.payload ->> 'window_seconds' AS bigint)) + 1)
                         * CAST(f.payload ->> 'window_seconds' AS bigint))
                   AND (NOT :highOnly OR d.confidence = 'high' OR d.confidence IS NULL)
                 GROUP BY 1, 2, 3, 4, 5
                """)
                .param("pid", projectId)
                .param("fid", findingId)
                .param("facetKey", Objects.requireNonNull(ClassifierArming.facetKey(BuiltInDetector.Kind.SECRET_LEAK)))
                .param("highOnly", highOnly)
                .query((rs, n) -> group(rs))
                .list();
    }

    /** Held by the caller's transaction, so two rebuilds of one finding do not interleave. */
    public void lock(String projectId, String findingId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .param("key", "end_user_hit:" + projectId + ":" + findingId)
                .query()
                .singleValue();
    }

    /**
     * Replaces the finding's rows on days from {@code oldestLiveDay} on (every day when null) with {@code rows}. Rows on
     * older days stay: their traces are partly or wholly deleted, so their evidence can no longer be read in full.
     */
    public void replace(String projectId, String findingId, @Nullable Integer oldestLiveDay, List<HitRow> rows) {
        jdbc.sql("""
                DELETE FROM end_user_hit
                 WHERE project_id = :pid AND finding_id = :fid
                   AND (CAST(:oldest AS integer) IS NULL OR day >= CAST(:oldest AS integer))
                """)
                .param("pid", projectId)
                .param("fid", findingId)
                .param("oldest", oldestLiveDay)
                .update();
        named.batchUpdate(
                """
                INSERT INTO end_user_hit (project_id, finding_id, role, user_key, day, route_no, refs, source,
                                          session_routes)
                VALUES (:pid, :fid, :role, :key, :day, :route, :refs, :source, CAST(:sessions AS jsonb))
                ON CONFLICT (project_id, finding_id, role, user_key, day, route_no)
                DO UPDATE SET refs = EXCLUDED.refs, source = EXCLUDED.source, session_routes = EXCLUDED.session_routes
                """,
                rows.stream()
                        .map(row -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("fid", findingId)
                                .addValue("role", row.role())
                                .addValue("key", HEX.parseHex(row.userKey()))
                                .addValue("day", row.day())
                                .addValue("route", row.routeNo())
                                .addValue("refs", row.refs())
                                .addValue("source", row.source())
                                .addValue("sessions", writeSessions(row)))
                        .toArray(SqlParameterSource[]::new));
    }

    public void writeSummary(String projectId, Summary summary) {
        jdbc.sql("""
                INSERT INTO end_user_hit_summary (project_id, finding_id, refs_total, refs_no_user_id,
                                                  session_only_refs, complete, hit_rule, updated_at)
                VALUES (:pid, :fid, :total, :noId, :sessionOnly, :complete, :rule, now())
                ON CONFLICT (project_id, finding_id) DO UPDATE SET
                    refs_total = EXCLUDED.refs_total, refs_no_user_id = EXCLUDED.refs_no_user_id,
                    session_only_refs = EXCLUDED.session_only_refs, complete = EXCLUDED.complete,
                    hit_rule = EXCLUDED.hit_rule, updated_at = EXCLUDED.updated_at
                """)
                .param("pid", projectId)
                .param("fid", summary.findingId())
                .param("total", summary.refsTotal())
                .param("noId", summary.refsNoUserId())
                .param("sessionOnly", summary.sessionOnlyRefs())
                .param("complete", summary.complete())
                .param("rule", summary.hitRule())
                .update();
    }

    public List<HitRow> stored(String projectId, String findingId) {
        return jdbc.sql("""
                SELECT role, encode(user_key, 'hex') AS user_key, day, route_no, refs, source,
                       session_routes::text AS session_routes
                  FROM end_user_hit
                 WHERE project_id = :pid AND finding_id = :fid
                 ORDER BY role, user_key, day, route_no
                """)
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> new HitRow(
                        rs.getString("role"),
                        rs.getString("user_key"),
                        rs.getInt("day"),
                        rs.getInt("route_no"),
                        rs.getInt("refs"),
                        rs.getString("source"),
                        readSessions(rs.getString("session_routes"))))
                .list();
    }

    private @Nullable String writeSessions(HitRow row) {
        return row.routeNo() == SESSION_ONLY_ROUTE
                ? mapper.valueToTree(row.sessionRoutes()).toString()
                : null;
    }

    private List<SessionRoutes> readSessions(@Nullable String json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json, SESSIONS);
        } catch (JsonProcessingException e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }

    private static String grouped(String role) {
        return "SELECT " + role + " AS role, COALESCE(s.user_id, t.user_id) AS user_id, t.name AS trace_name,"
                + " t.call_site_id, " + DAY + " AS day, COUNT(*)::int AS refs";
    }

    private static RefGroup group(ResultSet rs) throws SQLException {
        return new RefGroup(
                rs.getString("role"),
                rs.getString("user_id"),
                rs.getString("trace_name"),
                rs.getString("call_site_id"),
                rs.getInt("day"),
                rs.getInt("refs"));
    }
}
