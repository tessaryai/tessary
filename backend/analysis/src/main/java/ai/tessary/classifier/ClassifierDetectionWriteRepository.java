// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.detection.DetectionTableRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes a fired detection into its classifier's own table, whose unique subject index sets the idempotency.
 * {@link #tableFor} is the one routing map; null means the classifier files findings directly. Insert-if-absent: a
 * re-sweep writes nothing and {@link #insert} reports {@code false}, which arming and pre-deploy registration key on.
 */
@Repository
public class ClassifierDetectionWriteRepository {

    /**
     * The same expression wherever a window is read, so the touched-windows query and the counting query cannot
     * disagree.
     */
    private static final String EVENT_WINDOW =
            "(floor(extract(epoch FROM d.subject_started_at) / :window)::bigint * :window)";

    /** NULL reads as high, as every detection read here does. */
    private static final String HIGH_BAND = " AND (confidence = 'high' OR confidence IS NULL)";

    private static final String SPAN_JOIN = " JOIN span s ON s.project_id = d.project_id"
            + " AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id";

    private final JdbcClient jdbc;
    private final DetectionTableRegistry tables;

    public ClassifierDetectionWriteRepository(JdbcClient jdbc, DetectionTableRegistry tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    public @Nullable String tableFor(String detectorKind) {
        return tables.tableFor(detectorKind);
    }

    private String requireTable(String detectorKind) {
        return Objects.requireNonNull(
                tableFor(detectorKind), () -> "no detection table for detector kind " + detectorKind);
    }

    public boolean writesDetections(String detectorKind) {
        return tables.writesDetections(detectorKind);
    }

    /** Returns 0 for a kind that writes none. */
    public int deleteByClassifier(String detectorKind, String projectId, String classifierId) {
        String table = tableFor(detectorKind);
        if (table == null) return 0;
        return jdbc.sql("DELETE FROM " + table + " WHERE project_id = :pid AND classifier_id = :sid")
                .param("pid", projectId)
                .param("sid", classifierId)
                .update();
    }

    /**
     * Insert one fired detection, or do nothing if this classifier has already spoken about this subject. Returns true
     * only for a genuinely new detection.
     */
    public boolean insert(
            String id,
            String detectorKind,
            String projectId,
            String classifierId,
            String classifierKey,
            @Nullable String projectVersionId,
            @Nullable String sessionId,
            String traceId,
            @Nullable String spanId,
            @Nullable String severity,
            @Nullable String confidence,
            @Nullable String evidenceJson) {
        String table = requireTable(detectorKind);
        // The table name is interpolated because it is not a bindable position; it comes from a DetectionTable
        // registration, which rejects anything but a bare identifier. subject_started_at uses the same expression as
        // migration 0012's backfill, so new and backfilled rows share a clock.
        int written = jdbc.sql("INSERT INTO " + table + " (id, project_id, classifier_id, classifier_key,"
                        + " project_version_id, subject_session_id, subject_trace_id, subject_span_id,"
                        + " severity, confidence, evidence, subject_started_at, created_at)"
                        + " VALUES (:id, :pid, :sid, :skey, :versionId, :sessionId, :traceId, :spanId,"
                        + " :severity, :confidence, CAST(:evidence AS jsonb),"
                        + " COALESCE((SELECT started_at FROM span"
                        + "            WHERE project_id = :pid AND trace_id = :traceId AND id = :spanId),"
                        + "          (SELECT started_at FROM trace WHERE project_id = :pid AND id = :traceId)),"
                        + " now())"
                        + " ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("skey", classifierKey)
                .param("versionId", projectVersionId)
                .param("sessionId", sessionId)
                .param("traceId", traceId)
                .param("spanId", spanId)
                .param("severity", severity)
                .param("confidence", confidence)
                .param("evidence", evidenceJson)
                .update();
        return written > 0;
    }

    /**
     * Newest first and capped. The call site is read through the span table, so a detection whose span has aged out of
     * retention is not listed.
     */
    public List<DetectionInWindow> listWitnessDetections(
            String detectorKind,
            String projectId,
            String classifierId,
            String callSiteId,
            String since,
            String until,
            int limit) {
        String table = requireTable(detectorKind);
        if (limit <= 0) return List.of();
        return jdbc.sql("SELECT d.subject_trace_id, d.subject_span_id, d.subject_session_id, d.severity,"
                        + " d.confidence, d.evidence::text AS evidence, d.subject_started_at FROM " + table + " d"
                        + " JOIN span s ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id"
                        + " AND s.id = d.subject_span_id"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid AND s.call_site_id = :callSite"
                        + " AND d.subject_started_at >= CAST(:since AS timestamptz)"
                        + " AND d.subject_started_at < CAST(:until AS timestamptz)"
                        + " ORDER BY d.subject_started_at DESC, d.subject_trace_id DESC, d.subject_span_id DESC"
                        + " LIMIT :limit")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("callSite", callSiteId)
                .param("since", since)
                .param("until", until)
                .param("limit", limit)
                .query((rs, n) -> new DetectionInWindow(
                        rs.getString("subject_trace_id"),
                        rs.getString("subject_span_id"),
                        rs.getString("subject_session_id"),
                        rs.getString("severity"),
                        rs.getString("confidence"),
                        rs.getString("evidence"),
                        rs.getObject("subject_started_at", OffsetDateTime.class)
                                .toInstant()
                                .toString()))
                .list();
    }

    public record DetectionInWindow(
            String traceId,
            String spanId,
            @Nullable String sessionId,
            @Nullable String severity,
            @Nullable String confidence,
            @Nullable String evidenceJson,
            String subjectStartedAt) {}

    /**
     * A session is {@code trace.session_id} on one call site ({@code evidence.call_site_id}); a trace in no
     * conversation is never returned.
     */
    public Set<CallSiteTurn> unclearedFlaggedSessions(
            String detectorKind, String projectId, String classifierId, Collection<CallSiteTurn> turns) {
        String table = requireTable(detectorKind);
        if (turns.isEmpty()) return Set.of();
        Set<CallSiteTurn> flagged = new HashSet<>(jdbc.sql(
                        "SELECT DISTINCT t.id, COALESCE(d.evidence ->> 'call_site_id', '')"
                                + " FROM trace t JOIN " + table + " d"
                                + "   ON d.project_id = t.project_id AND d.classifier_id = :sid"
                                + "  AND d.subject_session_id = t.session_id"
                                + "  AND d.cleared_at IS NULL"
                                + " WHERE t.project_id = :pid AND t.id IN (:traces)")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param(
                        "traces",
                        turns.stream().map(CallSiteTurn::traceId).distinct().toList())
                .query((rs, n) -> new CallSiteTurn(rs.getString(1), rs.getString(2)))
                .list());
        flagged.retainAll(new HashSet<>(turns));
        return flagged;
    }

    public record CallSiteTurn(String traceId, String callSiteId) {}

    /** The same conversation's flags on other call sites stand. Rows already cleared keep their first clear time. */
    public int clearSessions(
            String detectorKind,
            String projectId,
            String classifierId,
            String callSiteId,
            Collection<String> sessionIds,
            String now) {
        String table = requireTable(detectorKind);
        if (sessionIds.isEmpty()) return 0;
        return jdbc.sql("UPDATE " + table + " SET cleared_at = :now"
                        + " WHERE project_id = :pid AND classifier_id = :sid"
                        + " AND subject_session_id IN (:sessions)"
                        + " AND COALESCE(evidence ->> 'call_site_id', '') = :callSite AND cleared_at IS NULL")
                .param("now", now)
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("sessions", sessionIds)
                .param("callSite", callSiteId)
                .update();
    }

    /** Rows already cleared keep their first clear time. */
    public int clearSpans(
            String detectorKind, String projectId, String classifierId, Collection<SpanKey> spans, String now) {
        String table = requireTable(detectorKind);
        if (spans.isEmpty()) return 0;
        List<Object[]> keys =
                spans.stream().map(k -> new Object[] {k.traceId(), k.spanId()}).toList();
        return jdbc.sql("UPDATE " + table + " SET cleared_at = :now"
                        + " WHERE project_id = :pid AND classifier_id = :sid"
                        + " AND (subject_trace_id, subject_span_id) IN (:spans) AND cleared_at IS NULL")
                .param("now", now)
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("spans", keys)
                .update();
    }

    public record SpanKey(String traceId, String spanId) {}

    public record FiredFacet(
            String traceId, String spanId, @Nullable String callSiteId, String facet, long windowStartEpochSecond) {}

    public record FacetWindow(
            @Nullable String callSiteId,
            String facet,
            long windowStartEpochSecond,
            long observed,
            Instant lastSeenAt,
            boolean anyHigh) {}

    private record WindowKey(String callSiteId, String facet, long windowStartEpochSecond) {}

    /**
     * Buckets on {@code subject_started_at} (event time), not {@code created_at}: a backfill checks months of traffic
     * in an afternoon, so write time would report a quarter's firings as one day's.
     */
    public List<FiredFacet> firedFacets(
            String detectorKind,
            String projectId,
            String classifierId,
            Collection<SpanKey> spans,
            String facetKey,
            long windowSeconds,
            boolean highOnly) {
        String table = requireTable(detectorKind);
        if (spans.isEmpty()) return List.of();
        List<Object[]> keys =
                spans.stream().map(k -> new Object[] {k.traceId(), k.spanId()}).toList();
        return jdbc.sql("SELECT d.subject_trace_id, d.subject_span_id, s.call_site_id,"
                        + " d.evidence ->> :facetKey AS facet, " + EVENT_WINDOW + " AS window_start"
                        + " FROM " + table + " d" + SPAN_JOIN
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND (d.subject_trace_id, d.subject_span_id) IN (:spans)"
                        + " AND d.evidence ->> :facetKey IS NOT NULL"
                        + (highOnly ? HIGH_BAND : ""))
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("spans", keys)
                .param("facetKey", facetKey)
                .param("window", windowSeconds)
                .query((rs, n) -> new FiredFacet(
                        rs.getString("subject_trace_id"),
                        rs.getString("subject_span_id"),
                        rs.getString("call_site_id"),
                        rs.getString("facet"),
                        rs.getLong("window_start")))
                .list();
    }

    /** Counts across all sweeps, not only the one that touched the window, ordered by window start. */
    public List<FacetWindow> countFacetWindows(
            String detectorKind,
            String projectId,
            String classifierId,
            String facetKey,
            long windowSeconds,
            boolean highOnly,
            boolean distinctSessions,
            Collection<FiredFacet> touched) {
        String table = requireTable(detectorKind);
        if (touched.isEmpty()) return List.of();
        Set<WindowKey> distinct = new LinkedHashSet<>();
        for (FiredFacet f : touched) {
            distinct.add(
                    new WindowKey(f.callSiteId() == null ? "" : f.callSiteId(), f.facet(), f.windowStartEpochSecond()));
        }
        List<Object[]> keys = distinct.stream()
                .map(k -> new Object[] {k.callSiteId(), k.facet(), k.windowStartEpochSecond()})
                .toList();
        // Grouped by ordinal: each expression binds its parameter afresh, so Postgres would not match a repeated
        // expression in GROUP BY to the select list.
        return jdbc.sql("SELECT s.call_site_id, d.evidence ->> :facetKey AS facet, " + EVENT_WINDOW
                        + " AS window_start, "
                        + (distinctSessions ? "COUNT(DISTINCT d.subject_session_id)" : "COUNT(*)") + " AS observed,"
                        + " MAX(d.subject_started_at) AS last_seen_at, BOOL_OR(d.confidence = 'high') AS any_high"
                        + " FROM " + table + " d" + SPAN_JOIN
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND (COALESCE(s.call_site_id, ''), d.evidence ->> :facetKey, " + EVENT_WINDOW
                        + ") IN (:keys)"
                        + (highOnly ? HIGH_BAND : "")
                        + " GROUP BY 1, 2, 3 ORDER BY 3, 1 NULLS FIRST, 2")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("keys", keys)
                .param("facetKey", facetKey)
                .param("window", windowSeconds)
                .query((rs, n) -> new FacetWindow(
                        rs.getString("call_site_id"),
                        rs.getString("facet"),
                        rs.getLong("window_start"),
                        rs.getLong("observed"),
                        rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("any_high")))
                .list();
    }

    public record FiredWindow(String traceId, String spanId, long windowStartEpochSecond) {}

    public record CountedWindow(long windowStartEpochSecond, long observed, Instant lastSeenAt, boolean anyHigh) {}

    public List<FiredWindow> firedWindows(
            String detectorKind,
            String projectId,
            String classifierId,
            Collection<SpanKey> spans,
            long windowSeconds,
            boolean highOnly) {
        String table = requireTable(detectorKind);
        if (spans.isEmpty()) return List.of();
        List<Object[]> keys =
                spans.stream().map(k -> new Object[] {k.traceId(), k.spanId()}).toList();
        return jdbc.sql("SELECT d.subject_trace_id, d.subject_span_id, " + EVENT_WINDOW + " AS window_start"
                        + " FROM " + table + " d"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND (d.subject_trace_id, d.subject_span_id) IN (:spans)"
                        + (highOnly ? HIGH_BAND : ""))
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("spans", keys)
                .param("window", windowSeconds)
                .query((rs, n) -> new FiredWindow(
                        rs.getString("subject_trace_id"), rs.getString("subject_span_id"), rs.getLong("window_start")))
                .list();
    }

    /** Counts across all sweeps, not only the one that touched the window, ordered by window start. */
    public List<CountedWindow> countWindows(
            String detectorKind,
            String projectId,
            String classifierId,
            long windowSeconds,
            boolean highOnly,
            boolean distinctSessions,
            Collection<FiredWindow> touched) {
        String table = requireTable(detectorKind);
        if (touched.isEmpty()) return List.of();
        Set<Long> windowStarts = new LinkedHashSet<>();
        for (FiredWindow w : touched) windowStarts.add(w.windowStartEpochSecond());
        return jdbc.sql("SELECT " + EVENT_WINDOW + " AS window_start, "
                        + (distinctSessions ? "COUNT(DISTINCT d.subject_session_id)" : "COUNT(*)") + " AS observed,"
                        + " MAX(d.subject_started_at) AS last_seen_at, BOOL_OR(d.confidence = 'high') AS any_high"
                        + " FROM " + table + " d"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND " + EVENT_WINDOW + " IN (:windows)"
                        + (highOnly ? HIGH_BAND : "")
                        + " GROUP BY 1 ORDER BY 1")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("windows", windowStarts)
                .param("window", windowSeconds)
                .query((rs, n) -> new CountedWindow(
                        rs.getLong("window_start"),
                        rs.getLong("observed"),
                        rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("any_high")))
                .list();
    }

    /**
     * Null when the population has aged out from under the span join. Scoped by call site and facet together, the pair
     * {@code ClassifierArming} files one finding per.
     */
    public @Nullable SecretLeakSummary secretLeakSummary(
            String detectorKind,
            String projectId,
            String classifierId,
            String pattern,
            @Nullable String callSiteId,
            Instant sinceOnset) {
        String table = requireTable(detectorKind);
        return jdbc.sql("SELECT count(*) AS n, count(DISTINCT d.subject_trace_id) AS traces,"
                        + " min(s.started_at) AS first_at, max(s.started_at) AS last_at"
                        + " FROM " + table + " d" + SPAN_JOIN
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND d.evidence ->> 'pattern' = :pattern"
                        + " AND s.call_site_id IS NOT DISTINCT FROM CAST(:callSiteId AS text)"
                        + " AND s.started_at >= :onset")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("pattern", pattern)
                .param("callSiteId", callSiteId)
                .param("onset", OffsetDateTime.ofInstant(sinceOnset, ZoneOffset.UTC))
                .query((rs, n) -> new SecretLeakSummary(
                        rs.getLong("n"),
                        rs.getLong("traces"),
                        instantOrNull(rs, "first_at"),
                        instantOrNull(rs, "last_at")))
                .optional()
                .filter(s -> s.leakCount() > 0)
                .orElse(null);
    }

    /** Newest first. A key that could not be named groups under {@code unknown} rather than being dropped. */
    public List<SecretLeakKeySummary> secretLeakKeys(
            String detectorKind,
            String projectId,
            String classifierId,
            String pattern,
            @Nullable String callSiteId,
            Instant sinceOnset) {
        String table = requireTable(detectorKind);
        return jdbc.sql("SELECT COALESCE(d.evidence ->> 'masked', 'unknown') AS masked,"
                        + " count(*) AS n, count(DISTINCT d.subject_trace_id) AS traces,"
                        + " max(s.started_at) AS last_at,"
                        + " bool_or(d.evidence ->> 'stored' = 'raw') AS stored_raw"
                        + " FROM " + table + " d" + SPAN_JOIN
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND d.evidence ->> 'pattern' = :pattern"
                        + " AND s.call_site_id IS NOT DISTINCT FROM CAST(:callSiteId AS text)"
                        + " AND s.started_at >= :onset"
                        + " GROUP BY 1 ORDER BY max(s.started_at) DESC")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("pattern", pattern)
                .param("callSiteId", callSiteId)
                .param("onset", OffsetDateTime.ofInstant(sinceOnset, ZoneOffset.UTC))
                .query((rs, n) -> new SecretLeakKeySummary(
                        rs.getString("masked"),
                        rs.getLong("n"),
                        rs.getLong("traces"),
                        instantOrNull(rs, "last_at"),
                        rs.getBoolean("stored_raw")))
                .list();
    }

    /**
     * A witness whose span has aged out drops out rather than appearing with nulls; the summary and key reads are the
     * full-population counts.
     */
    public List<SecretLeakWitness> secretLeakWitnesses(
            String detectorKind, String projectId, String classifierId, Collection<SpanKey> spans) {
        String table = requireTable(detectorKind);
        if (spans.isEmpty()) return List.of();
        List<Object[]> keys =
                spans.stream().map(k -> new Object[] {k.traceId(), k.spanId()}).toList();
        return jdbc.sql("SELECT d.subject_trace_id, d.subject_span_id, s.started_at,"
                        + " d.evidence ->> 'masked' AS masked, d.evidence ->> 'stored' AS stored"
                        + " FROM " + table + " d" + SPAN_JOIN
                        + " WHERE d.project_id = :pid AND d.classifier_id = :sid"
                        + " AND (d.subject_trace_id, d.subject_span_id) IN (:spans)"
                        + " ORDER BY s.started_at DESC NULLS LAST")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("spans", keys)
                .query((rs, n) -> new SecretLeakWitness(
                        rs.getString("subject_trace_id"),
                        rs.getString("subject_span_id"),
                        instantOrNull(rs, "started_at"),
                        rs.getString("masked"),
                        rs.getString("stored")))
                .list();
    }

    private static @Nullable Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public record SecretLeakSummary(
            long leakCount,
            long traceCount,
            @Nullable Instant firstAt,
            @Nullable Instant lastAt) {}

    public record SecretLeakKeySummary(
            String masked,
            long leaks,
            long traces,
            @Nullable Instant lastAt,
            boolean storedRaw) {}

    public record SecretLeakWitness(
            String traceId,
            @Nullable String spanId,
            @Nullable Instant at,
            @Nullable String masked,
            @Nullable String stored) {}
}
