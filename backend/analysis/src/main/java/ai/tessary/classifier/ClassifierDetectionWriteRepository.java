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
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes a fired detection into its classifier's own table (migration {@code 0088}).
 *
 * <p>A detection used to be a {@code verdict} row with {@code source='automatic'}, sharing a table with
 * grader scores, human rulings and escalation runs. It no longer is. Each per-span classifier has a
 * table, each table declares the grain that classifier judges at, and the idempotency follows from that
 * grain instead of from whatever the shared {@code ux_verdict_online_subject} index happened to key on.
 *
 * <p><b>The routing is by detector kind, not by table name guessing.</b> {@link #tableFor} is the one
 * place that maps a classifier onto its store; a kind with no table is a classifier that does not write
 * per-span detections (the drift/conformance families file findings directly), and the writer says so by
 * returning null rather than inventing a destination.
 *
 * <p>The name says WRITE because {@link ClassifierDetectionRepository} is the read side: that one
 * projects the stitched view for the events surface, this one owns the six tables the view is stitched
 * from and the window counts a classifier's arming gate reads back.
 *
 * <p><b>Insert-if-absent, first-write-wins.</b> {@code ON CONFLICT DO NOTHING} against the per-table
 * unique subject index, so a re-sweep over the same window writes nothing and
 * {@link #insert} reports {@code false}: a genuinely new detection is the one that inserted. That is the
 * same contract the old online-verdict upsert had, and the arming counter and the pre-deploy
 * registration both still key on it.
 */
@Repository
public class ClassifierDetectionWriteRepository {

    /**
     * The window a detection falls in, on its own {@code subject_started_at} (migration {@code 0012}):
     * {@code floor(subject_started_at / W) * W} in epoch seconds. Bound to {@code :window}, and the same
     * expression wherever a facet or whole-classifier window is read, so the query that finds which
     * windows a sweep touched and the one that counts them cannot disagree.
     */
    private static final String EVENT_WINDOW =
            "(floor(extract(epoch FROM d.subject_started_at) / :window)::bigint * :window)";

    /** The HIGH band. NULL reads as high, the convention every detection read here uses. */
    private static final String HIGH_BAND = " AND (confidence = 'high' OR confidence IS NULL)";

    private static final String SPAN_JOIN = " JOIN span s ON s.project_id = d.project_id"
            + " AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id";

    private final JdbcClient jdbc;
    private final DetectionTableRegistry tables;

    public ClassifierDetectionWriteRepository(JdbcClient jdbc, DetectionTableRegistry tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    /** The detection table for {@code detectorKind}, or null when that classifier writes no detections. */
    public @Nullable String tableFor(String detectorKind) {
        return tables.tableFor(detectorKind);
    }

    /** True when {@code detectorKind} has a detection table — i.e. it is one of the per-span classifiers. */
    public boolean writesDetections(String detectorKind) {
        return tables.writesDetections(detectorKind);
    }

    /**
     * Insert one fired detection, or do nothing if this classifier has already spoken about this subject.
     *
     * @return true when the row was written — a genuinely new detection
     * @throws IllegalArgumentException when the classifier has no detection table (a routing bug, not a
     *     runtime condition: the caller checks {@link #writesDetections} before it scores anything)
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
        String table = tableFor(detectorKind);
        if (table == null) {
            throw new IllegalArgumentException("no detection table for detector kind " + detectorKind);
        }
        // The table name is interpolated because it is not a bind-able position; every value that
        // reaches SQL from outside this class is a parameter, and the name itself comes from a
        // DetectionTable registration, whose constructor rejects anything but a bare identifier.
        //
        // subject_started_at is the span's own started_at, falling back to the trace's when there is no
        // span (a future trace-grain table; :spanId is null there) — the same expression migration 0012's
        // backfill used, so a row written today reads on the same clock as one backfilled at the cutover.
        // A span or trace that has since aged out of retention leaves it NULL rather than guessed.
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
     * Of {@code sessionIds}, the ones this classifier has ALREADY flagged at the HIGH band.
     *
     * <p>The turn-grain sweep reads this to stop re-scoring a conversation that is already flagged. A
     * conversation is one event, not one per turn: interview-coach carried 8,012 frustration detections
     * over 987 conversations (2026-08-20) — 8.12 rows per conversation, each a separate encoder call,
     * all saying the same thing about the same conversation.
     *
     * <p>HIGH specifically, because HIGH is the CEILING: no later turn can move a conversation already
     * flagged at the top band, so scoring one is work with no possible outcome. A conversation flagged
     * only at LOW stays eligible so it can still escalate. NULL reads as high, the same convention every
     * other detection read here uses.
     */
    public Set<String> sessionsAlreadyFlaggedHigh(
            String detectorKind, String projectId, String classifierId, Collection<String> sessionIds) {
        String table = tableFor(detectorKind);
        if (table == null || sessionIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT DISTINCT subject_session_id FROM " + table
                        + " WHERE project_id = :pid AND classifier_id = :sid"
                        + " AND subject_session_id IN (:sessions)"
                        + " AND (confidence = 'high' OR confidence IS NULL)")
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("sessions", sessionIds)
                .query(String.class)
                .list());
    }

    /** A span, by both halves of its composite key. */
    public record SpanKey(String traceId, String spanId) {}

    /**
     * One fired detection, resolved to the call site and facet it is about and the event-time window its
     * span falls in.
     */
    public record FiredFacet(
            String traceId, String spanId, @Nullable String callSiteId, String facet, long windowStartEpochSecond) {}

    /** One call site's detections of one facet inside one event-time window. */
    public record FacetWindow(
            @Nullable String callSiteId,
            String facet,
            long windowStartEpochSecond,
            long observed,
            Instant lastSeenAt,
            boolean anyHigh) {}

    private record WindowKey(String callSiteId, String facet, long windowStartEpochSecond) {}

    /**
     * Resolve the spans this classifier just fired on to the facet each detection is about and the window
     * its span falls in.
     *
     * <p><b>Event time, not write time.</b> A detection's {@code created_at} is when the sweep checked the
     * span, and a backfill checks months of traffic in an afternoon: bucketing on it would report a
     * quarter's firings as one day's. {@code subject_started_at} (migration {@code 0012}) is when the
     * thing happened; the span join here is for {@code call_site_id} alone.
     *
     * @param facetKey the evidence member the facet is read from; a detection without one is left out
     * @param highOnly restrict to the HIGH band (NULL reads as high)
     */
    public List<FiredFacet> firedFacets(
            String detectorKind,
            String projectId,
            String classifierId,
            Collection<SpanKey> spans,
            String facetKey,
            long windowSeconds,
            boolean highOnly) {
        String table = tableFor(detectorKind);
        if (table == null || spans.isEmpty()) return List.of();
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

    /**
     * Count every detection in the windows {@code touched} names, across all sweeps rather than only the
     * one that touched them, ordered by window start so a caller filing them walks forward in event time.
     *
     * @param distinctSessions count sessions rather than detections, the {@code distinct_users} basis
     */
    public List<FacetWindow> countFacetWindows(
            String detectorKind,
            String projectId,
            String classifierId,
            String facetKey,
            long windowSeconds,
            boolean highOnly,
            boolean distinctSessions,
            Collection<FiredFacet> touched) {
        String table = tableFor(detectorKind);
        if (table == null || touched.isEmpty()) return List.of();
        Set<WindowKey> distinct = new LinkedHashSet<>();
        for (FiredFacet f : touched) {
            distinct.add(
                    new WindowKey(f.callSiteId() == null ? "" : f.callSiteId(), f.facet(), f.windowStartEpochSecond()));
        }
        List<Object[]> keys = distinct.stream()
                .map(k -> new Object[] {k.callSiteId(), k.facet(), k.windowStartEpochSecond()})
                .toList();
        // Grouped by ordinal: the facet and window expressions each bind their parameter afresh, so
        // Postgres would not recognise a repeated expression in GROUP BY as the one in the select list.
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

    /** One fired detection resolved to the event-time window it falls in — the whole-classifier
     *  counterpart of {@link FiredFacet}, with no facet or call site to key on: the classifier-wide
     *  shape implicates no single call site. */
    public record FiredWindow(String traceId, String spanId, long windowStartEpochSecond) {}

    /** One event-time window's count across all sweeps — the whole-classifier counterpart of
     *  {@link FacetWindow}. */
    public record CountedWindow(long windowStartEpochSecond, long observed, Instant lastSeenAt, boolean anyHigh) {}

    /**
     * Resolve the spans this classifier just fired on to the event-time window each falls in — {@link
     * #firedFacets} without a facet to key on.
     *
     * @param highOnly restrict to the HIGH band (NULL reads as high)
     */
    public List<FiredWindow> firedWindows(
            String detectorKind,
            String projectId,
            String classifierId,
            Collection<SpanKey> spans,
            long windowSeconds,
            boolean highOnly) {
        String table = tableFor(detectorKind);
        if (table == null || spans.isEmpty()) return List.of();
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

    /**
     * Count every detection in the windows {@code touched} names, across all sweeps rather than only the
     * one that touched them, ordered by window start so a caller filing them walks forward in event time —
     * {@link #countFacetWindows} without a facet or call site to group by.
     *
     * @param distinctSessions count sessions rather than detections, the {@code distinct_users} basis
     */
    public List<CountedWindow> countWindows(
            String detectorKind,
            String projectId,
            String classifierId,
            long windowSeconds,
            boolean highOnly,
            boolean distinctSessions,
            Collection<FiredWindow> touched) {
        String table = tableFor(detectorKind);
        if (table == null || touched.isEmpty()) return List.of();
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
     * A secret-leak facet's population since its finding's onset: how many detections, across how many
     * traces, first to last by the span's own clock, and whether any of them is HIGH band. Null when the
     * facet has no detection table or its population has aged out from under the span join entirely.
     *
     * <p>Scoped by call site AND facet together, the same pair {@code ClassifierArming} files one finding
     * per: a project-wide count here would mix a leak at one call site into another's numbers.
     */
    public @Nullable SecretLeakSummary secretLeakSummary(
            String detectorKind,
            String projectId,
            String classifierId,
            String pattern,
            @Nullable String callSiteId,
            Instant sinceOnset) {
        String table = tableFor(detectorKind);
        if (table == null) return null;
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

    /**
     * Every masked key this facet has leaked as, newest-leaking-first: how many leaks and how many
     * traces each key accounts for, when it last leaked, and whether any instance of it is still sitting
     * in stored output unredacted. A key the redaction stamp or the raw-output reading could not name
     * groups under {@code unknown} rather than being dropped, per the forward-only degrade.
     */
    public List<SecretLeakKeySummary> secretLeakKeys(
            String detectorKind,
            String projectId,
            String classifierId,
            String pattern,
            @Nullable String callSiteId,
            Instant sinceOnset) {
        String table = tableFor(detectorKind);
        if (table == null) return List.of();
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
     * What {@code secret_leak_detection} recorded for each of a finding's own witnesses — the bounded
     * set {@code ClassifierArming} already pinned, joined here for the mask, whether it is still stored
     * raw, and the span's own time. A witness whose span has aged out of the join drops out rather than
     * appearing with nulls: {@link #secretLeakSummary} and {@link #secretLeakKeys} are the full-population
     * counts, this is only the instances a reader can still open.
     */
    public List<SecretLeakWitness> secretLeakWitnesses(
            String detectorKind, String projectId, String classifierId, Collection<SpanKey> spans) {
        String table = tableFor(detectorKind);
        if (table == null || spans.isEmpty()) return List.of();
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

    /** A secret-leak facet's population since onset: see {@link #secretLeakSummary}. */
    public record SecretLeakSummary(
            long leakCount,
            long traceCount,
            @Nullable Instant firstAt,
            @Nullable Instant lastAt) {}

    /** One masked key's aggregate within a secret-leak facet: see {@link #secretLeakKeys}. */
    public record SecretLeakKeySummary(
            String masked,
            long leaks,
            long traces,
            @Nullable Instant lastAt,
            boolean storedRaw) {}

    /** One witness leak, joined to what {@code secret_leak_detection} recorded: see {@link #secretLeakWitnesses}. */
    public record SecretLeakWitness(
            String traceId,
            @Nullable String spanId,
            @Nullable Instant at,
            @Nullable String masked,
            @Nullable String stored) {}
}
