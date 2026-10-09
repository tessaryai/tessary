// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import ai.tessary.cases.CaseRow;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ClassifierChartDtos.CaseSpan;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import ai.tessary.classifier.toolerror.ToolFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The hourly series behind the Classifiers page charts, its case strips and its selectors. Every hour is cut with
 * {@code date_trunc('hour', x, 'UTC')}: the hourly reads the detectors replay truncate without a zone, which in a
 * session zone off UTC by a half hour is not a UTC hour, and are not reused here. The last-day reads stay UTC dates.
 */
@Repository
public class ClassifierChartRepository {

    /** The key a trace with no call site is indexed under ({@code ix_trace_scope_settled_started}). */
    static final String UNATTRIBUTED = "__unattributed__";

    /**
     * {@code ToolErrorRepository}'s join from a tool call to its span and the span's trace, copied so the chart counts
     * the calls the detector counts. {@link ToolFailure#SQL_PREDICATE} reads the {@code tc}, {@code o} and {@code pl}
     * aliases.
     */
    private static final String TOOL_SPAN_JOIN = """
            JOIN span o ON o.project_id = tc.project_id
                       AND o.trace_id = tc.trace_id AND o.id = tc.span_id
            JOIN trace tr ON tr.project_id = o.project_id AND tr.id = o.trace_id
            LEFT JOIN span_payload pl
              ON pl.project_id = o.project_id AND pl.trace_id = o.trace_id AND pl.span_id = o.id
            """;

    private static final String TOOL_CALLS_WHERE = """
             WHERE tc.project_id = :pid
               AND tc.is_deleted IS NOT TRUE
               AND o.is_deleted IS NOT TRUE
               AND tr.is_settled
            """;

    /** The first parentless span of a trace: the turn, as {@code MetricSourceRepository#turnFacts} reads it. */
    private static final String ROOT_LATERAL = """
            JOIN LATERAL (
                SELECT s.started_at, s.ended_at
                  FROM span s
                 WHERE s.project_id = tr.project_id AND s.trace_id = tr.id
                   AND s.parent_span_id IS NULL AND s.is_deleted IS NOT TRUE
                 ORDER BY s.started_at ASC, s.id ASC
                 LIMIT 1) root ON TRUE
            """;

    /** Settled live traces of one call site since {@code :from}, spelled as the index is. */
    private static final String TURNS_WHERE = """
             WHERE tr.project_id = :pid AND tr.is_settled AND tr.is_deleted IS NOT TRUE
               AND COALESCE(tr.call_site_id, '__unattributed__') = :callSite
               AND tr.started_at >= :from
            """;

    private static final String HOUR_OF_TURN = "date_trunc('hour', tr.started_at, 'UTC')";

    /** The start of the {@code :grain}-second bucket a detection falls in, as the arming bar cuts its windows. */
    private static final String GRAIN = "(floor(extract(epoch FROM d.subject_started_at) / :grain)::bigint * :grain)";

    private static final String TURN_MS = "EXTRACT(EPOCH FROM (root.ended_at - root.started_at))::float8 * 1000";

    private static final String TURN_ENDED = " AND root.ended_at IS NOT NULL AND root.ended_at >= root.started_at";

    private static final String PRICED =
            " AND tr.total_cost IS NOT NULL AND tr.total_cost >= 0" + " AND COALESCE(tr.unpriced_spans, 0) = 0";

    /** A tool span's name as {@code MetricSourceRepository#toolSpanFacts} resolves it. */
    private static final String TOOL_SPANS = """
              FROM span s
              JOIN trace tr ON tr.project_id = s.project_id AND tr.id = s.trace_id
              LEFT JOIN span_payload pl
                ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id
              LEFT JOIN LATERAL (
                  SELECT tc.name
                    FROM tool_call tc
                   WHERE tc.project_id = s.project_id AND tc.trace_id = s.trace_id
                     AND tc.span_id = s.id AND tc.is_deleted IS NOT TRUE AND tc.name IS NOT NULL
                   ORDER BY tc.started_at ASC NULLS LAST, tc.created_at ASC, tc.id ASC
                   LIMIT 1) tcn ON TRUE
             WHERE s.project_id = :pid AND s.kind = 'tool' AND s.is_deleted IS NOT TRUE
               AND tr.is_settled AND tr.is_deleted IS NOT TRUE
               AND s.started_at >= :from
            """;

    private static final String TOOL_NAME = "COALESCE(tcn.name, pl.attributes->>'gen_ai.tool.name', s.name)";

    private static final String TOOL_MS = "CASE WHEN s.latency_ms IS NOT NULL AND s.latency_ms >= 0"
            + " THEN s.latency_ms::float8"
            + " WHEN s.ended_at IS NOT NULL AND s.ended_at >= s.started_at"
            + " THEN EXTRACT(EPOCH FROM (s.ended_at - s.started_at))::float8 * 1000 END";

    /** One rate hour: trials checked and flagged. */
    public record RateRow(Instant hour, long checked, long flagged) {}

    /** One range hour's samples in one bin of the card's grid. */
    public record RangeRow(Instant hour, int bin, long n) {}

    /**
     * One count bucket: a window of a fixed number of seconds counted from the epoch, as the arming bar cuts its own.
     *
     * @param events detections the bar's band and scope admit, in the busiest facet
     * @param sessions distinct sessions among them, in the busiest facet
     * @param total every detection on the call site
     */
    public record CountRow(Instant start, long events, long sessions, long total) {}

    /** Whose findings a card's case strip shows. */
    public sealed interface CaseScope {}

    /** A per-span or rate classifier on one call site: findings it filed with that call site. */
    public record ClassifierOnCallSite(String classifierKey, String callSiteId) implements CaseScope {}

    /** A drift card: findings on its {@code metric_baseline} bucket. */
    public record DriftBucket(String measure, String bucketKind, String bucketKey) implements CaseScope {}

    /** The Tool Errors card: findings on the tool. */
    public record ToolErrorTool(String toolKey) implements CaseScope {}

    private final JdbcClient jdbc;
    private final ClassifierDetectionWriteRepository detections;

    public ClassifierChartRepository(JdbcClient jdbc, ClassifierDetectionWriteRepository detections) {
        this.jdbc = jdbc;
        this.detections = detections;
    }

    // ---- rate cards ---------------------------------------------------------------------------------

    /**
     * Frustration sessions on {@code callSite} per UTC hour of their first scored turn. Sessions are grouped from
     * {@code groupFrom}, before the range, so a session that began before {@code from} keeps its own first hour and
     * every range reads the same trials; only sessions whose first turn is at or after {@code from} are returned.
     */
    public List<RateRow> frustrationHours(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSite,
            Instant groupFrom,
            Instant from) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.FRUSTRATION));
        return jdbc.sql("""
                        WITH conv AS (
                          SELECT a.conversation_id,
                                 MIN(a.turn_started_at) AS first_scored_at,
                                 BOOL_OR(a.frustrated) AS any_flag
                            FROM frustration_assessment a
                           WHERE a.project_id = :pid AND a.classifier_id = :cid
                             AND a.scorer_version = :scorerVersion AND a.turn_started_at >= :groupFrom
                             AND COALESCE(a.call_site_id, '') = :callSite
                           GROUP BY a.conversation_id)
                        SELECT date_trunc('hour', c.first_scored_at, 'UTC') AS hour,
                               COUNT(*) AS checked,
                               COUNT(*) FILTER (WHERE c.any_flag AND EXISTS (
                                   SELECT 1 FROM {detections} d
                                    WHERE d.project_id = :pid AND d.classifier_id = :cid
                                      AND d.subject_session_id = c.conversation_id
                                      AND COALESCE(d.evidence ->> 'call_site_id', '') = :callSite
                                      AND d.cleared_at IS NULL)) AS flagged
                          FROM conv c
                         WHERE c.first_scored_at >= :from
                         GROUP BY 1
                        """.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("callSite", callSite)
                .param("groupFrom", at(groupFrom))
                .param("from", at(from))
                .query((rs, n) -> rateRow(rs))
                .list();
    }

    /** The last UTC day before {@code before} with a scored turn on {@code callSite}. */
    public Optional<LocalDate> frustrationLastDay(
            String projectId, String classifierId, String scorerVersion, String callSite, Instant before) {
        return lastDay("""
                        SELECT MAX(turn_started_at) AS last_at FROM frustration_assessment
                         WHERE project_id = :pid AND classifier_id = :cid AND scorer_version = :scorerVersion
                           AND COALESCE(call_site_id, '') = :callSite AND turn_started_at < :before
                        """, projectId, classifierId, scorerVersion, callSite, before);
    }

    /**
     * Groundedness traces on {@code callSite} per UTC hour of their first scored answer, grouped from {@code
     * groupFrom} as {@link #frustrationHours} groups sessions. A trace is flagged while one of its flagged answers
     * has an uncleared detection.
     */
    public List<RateRow> groundednessHours(
            String projectId,
            String classifierId,
            String scorerVersion,
            String callSite,
            Instant groupFrom,
            Instant from) {
        String table = Objects.requireNonNull(detections.tableFor(BuiltInDetector.Kind.GROUNDEDNESS));
        return jdbc.sql("""
                        WITH tr AS (
                          SELECT a.subject_trace_id,
                                 MIN(a.observation_started_at) AS first_scored_at,
                                 BOOL_OR(a.flagged AND EXISTS (
                                     SELECT 1 FROM {detections} d
                                      WHERE d.project_id = a.project_id AND d.classifier_id = a.classifier_id
                                        AND d.subject_trace_id = a.subject_trace_id
                                        AND d.subject_span_id = a.subject_span_id
                                        AND d.cleared_at IS NULL)) AS failed
                            FROM groundedness_assessment a
                           WHERE a.project_id = :pid AND a.classifier_id = :cid
                             AND a.scorer_version = :scorerVersion AND a.call_site_id = :callSite
                             AND a.observation_started_at >= :groupFrom
                           GROUP BY a.subject_trace_id)
                        SELECT date_trunc('hour', t.first_scored_at, 'UTC') AS hour,
                               COUNT(*) AS checked,
                               COUNT(*) FILTER (WHERE t.failed) AS flagged
                          FROM tr t
                         WHERE t.first_scored_at >= :from
                         GROUP BY 1
                        """.replace("{detections}", table))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("callSite", callSite)
                .param("groupFrom", at(groupFrom))
                .param("from", at(from))
                .query((rs, n) -> rateRow(rs))
                .list();
    }

    /** The last UTC day before {@code before} with a scored answer on {@code callSite}. */
    public Optional<LocalDate> groundednessLastDay(
            String projectId, String classifierId, String scorerVersion, String callSite, Instant before) {
        return lastDay("""
                        SELECT MAX(observation_started_at) AS last_at FROM groundedness_assessment
                         WHERE project_id = :pid AND classifier_id = :cid AND scorer_version = :scorerVersion
                           AND call_site_id = :callSite AND observation_started_at < :before
                        """, projectId, classifierId, scorerVersion, callSite, before);
    }

    private Optional<LocalDate> lastDay(
            String sql, String projectId, String classifierId, String scorerVersion, String callSite, Instant before) {
        return jdbc.sql(sql)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("scorerVersion", scorerVersion)
                .param("callSite", callSite)
                .param("before", at(before))
                .query((rs, n) -> Optional.ofNullable(utcDay(rs.getObject("last_at", OffsetDateTime.class))))
                .single();
    }

    /**
     * Malformed Output's checked and failed outputs on {@code callSite} per UTC hour. A span counts once the sweep
     * has stored past it: {@code created_at} before the cursor, the ingest clock the sweep walks, so a backfilled
     * span the sweep has not reached is not a pass.
     */
    public List<RateRow> malformedHours(
            String projectId, String classifierId, String callSite, Instant from, String checkedBefore) {
        return jdbc.sql("""
                        SELECT date_trunc('hour', s.started_at, 'UTC') AS hour,
                               COUNT(*) AS checked,
                               COUNT(d.id) AS flagged
                          FROM span s
                          JOIN call_site cs
                            ON cs.project_id = s.project_id AND cs.id = s.call_site_id
                           AND cs.output_schema IS NOT NULL
                          LEFT JOIN malformed_output_detection d
                            ON d.project_id = s.project_id AND d.classifier_id = :cid
                           AND d.subject_trace_id = s.trace_id AND d.subject_span_id = s.id
                         WHERE s.project_id = :pid AND s.call_site_id = :callSite
                           AND s.started_at >= :from
                           AND s.created_at < CAST(:checkedBefore AS timestamptz)
                           AND s.output_preview IS NOT NULL AND s.output_preview <> ''
                         GROUP BY 1
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSite)
                .param("from", at(from))
                .param("checkedBefore", checkedBefore)
                .query((rs, n) -> rateRow(rs))
                .list();
    }

    /** The last UTC day before {@code before} with an output Malformed Output checked on {@code callSite}. */
    public Optional<LocalDate> malformedLastDay(
            String projectId, String callSite, Instant before, String checkedBefore) {
        return jdbc.sql("""
                        SELECT MAX(s.started_at) AS last_at
                          FROM span s
                          JOIN call_site cs
                            ON cs.project_id = s.project_id AND cs.id = s.call_site_id
                           AND cs.output_schema IS NOT NULL
                         WHERE s.project_id = :pid AND s.call_site_id = :callSite
                           AND s.started_at < :before
                           AND s.created_at < CAST(:checkedBefore AS timestamptz)
                           AND s.output_preview IS NOT NULL AND s.output_preview <> ''
                        """)
                .param("pid", projectId)
                .param("callSite", callSite)
                .param("before", at(before))
                .param("checkedBefore", checkedBefore)
                .query((rs, n) -> Optional.ofNullable(utcDay(rs.getObject("last_at", OffsetDateTime.class))))
                .single();
    }

    /**
     * Tool calls and failed ones per UTC hour, for the raw {@code tool_call.name} values of one tool key. The names
     * come from {@code ToolErrorRepository#namesByToolKey}: a key is a normalization and never matches a name.
     */
    public List<RateRow> toolErrorHours(String projectId, Collection<String> names, Instant from) {
        if (names.isEmpty()) return List.of();
        return jdbc.sql("SELECT date_trunc('hour', tc.started_at, 'UTC') AS hour, COUNT(*) AS checked,"
                        + " COUNT(*) FILTER (WHERE " + ToolFailure.SQL_PREDICATE + ") AS flagged"
                        + " FROM tool_call tc " + TOOL_SPAN_JOIN + TOOL_CALLS_WHERE
                        + " AND tc.started_at >= :from"
                        + " AND COALESCE(NULLIF(tc.name, ''), 'unnamed') IN (:names)"
                        + " GROUP BY 1")
                .param("pid", projectId)
                .param("from", at(from))
                .param("names", names)
                .query((rs, n) -> rateRow(rs))
                .list();
    }

    /** The last UTC day before {@code before} with a call of one of {@code names}. */
    public Optional<LocalDate> toolCallLastDay(String projectId, Collection<String> names, Instant before) {
        if (names.isEmpty()) return Optional.empty();
        return jdbc.sql("SELECT MAX(tc.started_at) AS last_at FROM tool_call tc " + TOOL_SPAN_JOIN + TOOL_CALLS_WHERE
                        + " AND tc.started_at < :before AND COALESCE(NULLIF(tc.name, ''), 'unnamed') IN (:names)")
                .param("pid", projectId)
                .param("before", at(before))
                .param("names", names)
                .query((rs, n) -> Optional.ofNullable(utcDay(rs.getObject("last_at", OffsetDateTime.class))))
                .single();
    }

    /**
     * One tool's raw name in the range, with the call site of each trace that called it and how often: the tool
     * selector's read, over the calls Tool Errors counts.
     */
    public record ToolCallerRow(String name, @Nullable String callSiteId, long calls) {}

    public List<ToolCallerRow> toolCallers(String projectId, Instant from) {
        return jdbc.sql("SELECT COALESCE(NULLIF(tc.name, ''), 'unnamed') AS name, tr.call_site_id, COUNT(*) AS calls"
                        + " FROM tool_call tc " + TOOL_SPAN_JOIN + TOOL_CALLS_WHERE
                        + " AND tc.started_at >= :from GROUP BY 1, 2")
                .param("pid", projectId)
                .param("from", at(from))
                .query((rs, n) ->
                        new ToolCallerRow(rs.getString("name"), rs.getString("call_site_id"), rs.getLong("calls")))
                .list();
    }

    // ---- range cards --------------------------------------------------------------------------------

    /**
     * Turn duration on {@code callSite} per UTC hour and bin of {@code grid}: the root span's own interval in ms,
     * never {@code trace.latency_ms}, which a child that outlives the root stretches.
     */
    public List<RangeRow> turnDurationHours(String projectId, String callSite, Instant from, Grid grid) {
        return histogram(
                "SELECT " + HOUR_OF_TURN + " AS hour, " + TURN_MS + " AS v FROM trace tr " + ROOT_LATERAL + TURNS_WHERE
                        + TURN_ENDED,
                projectId,
                callSite,
                from,
                grid);
    }

    /** The 95th percentile of every turn on {@code callSite} since {@code from}, pooled: the headline. */
    public @Nullable Double turnDurationP95(String projectId, String callSite, Instant from) {
        return pooled(
                "SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY " + TURN_MS + ") AS p95" + " FROM trace tr "
                        + ROOT_LATERAL + TURNS_WHERE + TURN_ENDED,
                projectId,
                callSite,
                from);
    }

    /**
     * Cost per turn on {@code callSite} per UTC hour and bin of {@code grid}, in USD, over turns whose every span was
     * priced.
     */
    public List<RangeRow> costHours(String projectId, String callSite, Instant from, Grid grid) {
        return histogram(
                "SELECT " + HOUR_OF_TURN + " AS hour, tr.total_cost::float8 AS v FROM trace tr " + TURNS_WHERE + PRICED,
                projectId,
                callSite,
                from,
                grid);
    }

    /** The pooled 95th percentile cost per turn on {@code callSite} since {@code from}. */
    public @Nullable Double costP95(String projectId, String callSite, Instant from) {
        return pooled(
                "SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY tr.total_cost::float8) AS p95" + " FROM trace tr "
                        + TURNS_WHERE + PRICED,
                projectId,
                callSite,
                from);
    }

    /** The last UTC day before {@code before} with a settled turn on {@code callSite}; a priced one when {@code priced}. */
    public Optional<LocalDate> turnLastDay(String projectId, String callSite, Instant before, boolean priced) {
        return jdbc.sql("""
                        SELECT MAX(tr.started_at) AS last_at FROM trace tr
                         WHERE tr.project_id = :pid AND tr.is_settled AND tr.is_deleted IS NOT TRUE
                           AND COALESCE(tr.call_site_id, '__unattributed__') = :callSite
                           AND tr.started_at < :before
                        """ + (priced ? PRICED : ""))
                .param("pid", projectId)
                .param("callSite", callSite)
                .param("before", at(before))
                .query((rs, n) -> Optional.ofNullable(utcDay(rs.getObject("last_at", OffsetDateTime.class))))
                .single();
    }

    private List<RangeRow> histogram(String hourAndValue, String projectId, String callSite, Instant from, Grid grid) {
        return grid(
                        jdbc.sql("SELECT x.hour, " + bin("x.v") + " AS bin, COUNT(*) AS n FROM (" + hourAndValue
                                        + ") x GROUP BY 1, 2")
                                .param("pid", projectId)
                                .param("callSite", callSite)
                                .param("from", at(from)),
                        grid)
                .query((rs, n) -> rangeRow(rs))
                .list();
    }

    private @Nullable Double pooled(String sql, String projectId, String callSite, Instant from) {
        return jdbc.sql(sql)
                .param("pid", projectId)
                .param("callSite", callSite)
                .param("from", at(from))
                .query((rs, n) -> Optional.ofNullable(nullableDouble(rs, "p95")))
                .single()
                .orElse(null);
    }

    /** Every name a tool span in the range carries, before it is folded to a tool key. */
    public List<String> toolSpanNames(String projectId, Instant from) {
        return jdbc
                .sql("SELECT DISTINCT " + TOOL_NAME + " AS name " + TOOL_SPANS)
                .param("pid", projectId)
                .param("from", at(from))
                .query((rs, n) -> rs.getString("name"))
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Tool span duration per UTC hour and bin of {@code grid}, in ms, for the names of one tool key: the span's own
     * latency, else its interval. {@code callSiteIds} limits it to traces of those call sites when the classifier is
     * limited.
     */
    public List<RangeRow> toolDurationHours(
            String projectId,
            Collection<String> names,
            @Nullable Collection<String> callSiteIds,
            Instant from,
            Grid grid) {
        if (names.isEmpty()) return List.of();
        return grid(
                        toolDuration(
                                "SELECT date_trunc('hour', s.started_at, 'UTC') AS hour, " + bin("s.ms")
                                        + " AS bin, COUNT(*) AS n",
                                " GROUP BY 1, 2",
                                projectId,
                                names,
                                callSiteIds,
                                from),
                        grid)
                .query((rs, n) -> rangeRow(rs))
                .list();
    }

    /** The pooled 95th percentile tool span duration since {@code from}. */
    public @Nullable Double toolDurationP95(
            String projectId, Collection<String> names, @Nullable Collection<String> callSiteIds, Instant from) {
        if (names.isEmpty()) return null;
        return toolDuration(
                        "SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY ms) AS p95",
                        "",
                        projectId,
                        names,
                        callSiteIds,
                        from)
                .query((rs, n) -> Optional.ofNullable(nullableDouble(rs, "p95")))
                .single()
                .orElse(null);
    }

    /** The last UTC day in {@code [from, before)} with a timed tool span of one of {@code names}. */
    public Optional<LocalDate> toolDurationLastDay(
            String projectId,
            Collection<String> names,
            @Nullable Collection<String> callSiteIds,
            Instant from,
            Instant before) {
        if (names.isEmpty()) return Optional.empty();
        return toolDuration(
                        "SELECT MAX(s.started_at) AS last_at",
                        " AND s.started_at < :before",
                        projectId,
                        names,
                        callSiteIds,
                        from)
                .param("before", at(before))
                .query((rs, n) -> Optional.ofNullable(utcDay(rs.getObject("last_at", OffsetDateTime.class))))
                .single();
    }

    private JdbcClient.StatementSpec toolDuration(
            String select,
            String tail,
            String projectId,
            Collection<String> names,
            @Nullable Collection<String> callSiteIds,
            Instant from) {
        String scoped = callSiteIds == null ? "" : " AND tr.call_site_id IN (:callSites)";
        JdbcClient.StatementSpec spec = jdbc.sql(select + " FROM (SELECT s.started_at, " + TOOL_MS + " AS ms "
                        + TOOL_SPANS + " AND " + TOOL_NAME + " IN (:names)" + scoped + ") s"
                        + " WHERE s.ms IS NOT NULL" + tail)
                .param("pid", projectId)
                .param("from", at(from))
                .param("names", names);
        return callSiteIds == null ? spec : spec.param("callSites", callSiteIds.isEmpty() ? List.of("") : callSiteIds);
    }

    // ---- count cards --------------------------------------------------------------------------------

    /**
     * Detections of a per-span classifier on {@code callSite} per {@code grainSeconds} bucket of the span, cut from
     * the epoch as the arming bar cuts its windows. {@code events} and {@code sessions} are counted in the HIGH band
     * when {@code highOnly}. With {@code facet} set (Secret Leak), they are the busiest value of that evidence member
     * in the bucket, because the bar counts each facet on its own. Distinct sessions do not add across buckets, so a
     * caller reads each grain it needs.
     */
    public List<CountRow> countBuckets(
            String detectorKind,
            String projectId,
            String classifierId,
            String callSite,
            Instant from,
            long grainSeconds,
            boolean highOnly,
            @Nullable String facet) {
        String table = Objects.requireNonNull(detections.tableFor(detectorKind));
        String band = highOnly ? "(d.confidence = 'high' OR d.confidence IS NULL)" : "TRUE";
        String facetColumn = facet == null ? "''" : "d.evidence ->> :facet";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        WITH f AS (
                          SELECT {grain} AS bucket_start, {facet} AS facet,
                                 COUNT(*) FILTER (WHERE {band}) AS events,
                                 COUNT(DISTINCT d.subject_session_id) FILTER (WHERE {band}) AS sessions,
                                 COUNT(*) AS total
                            FROM {table} d
                            JOIN span s ON s.project_id = d.project_id
                                       AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id
                           WHERE d.project_id = :pid AND d.classifier_id = :cid
                             AND d.subject_started_at >= :from AND s.call_site_id = :callSite
                           GROUP BY 1, 2)
                        SELECT bucket_start, MAX(events) AS events, MAX(sessions) AS sessions, SUM(total) AS total
                          FROM f GROUP BY bucket_start
                        """.replace("{table}", table)
                        .replace("{band}", band)
                        .replace("{facet}", facetColumn)
                        .replace("{grain}", GRAIN))
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSite)
                .param("from", at(from))
                .param("grain", grainSeconds);
        if (facet != null) spec = spec.param("facet", facet);
        return spec.query((rs, n) -> countRow(rs, rs.getLong("total"))).list();
    }

    /**
     * A whole-project classifier's detections per {@code grainSeconds} bucket, every call site and none: what its
     * bar counts. Read beside {@link #countBuckets}, whose {@code total} stays the call site's own.
     */
    public List<CountRow> projectCountBuckets(
            String detectorKind,
            String projectId,
            String classifierId,
            Instant from,
            long grainSeconds,
            boolean highOnly) {
        String table = Objects.requireNonNull(detections.tableFor(detectorKind));
        String band = highOnly ? " AND (d.confidence = 'high' OR d.confidence IS NULL)" : "";
        return jdbc.sql("SELECT " + GRAIN + " AS bucket_start, COUNT(*) AS events,"
                        + " COUNT(DISTINCT d.subject_session_id) AS sessions"
                        + " FROM " + table + " d"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :cid AND d.subject_started_at >= :from"
                        + band + " GROUP BY 1")
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("from", at(from))
                .param("grain", grainSeconds)
                .query((rs, n) -> countRow(rs, 0))
                .list();
    }

    /** The last UTC day before {@code before} with a detection on {@code callSite}. */
    public Optional<LocalDate> countLastDay(
            String detectorKind, String projectId, String classifierId, String callSite, Instant before) {
        String table = Objects.requireNonNull(detections.tableFor(detectorKind));
        return jdbc.sql("SELECT MAX(d.subject_started_at) AS last_at FROM " + table + " d"
                        + " JOIN span s ON s.project_id = d.project_id"
                        + " AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id"
                        + " WHERE d.project_id = :pid AND d.classifier_id = :cid"
                        + " AND d.subject_started_at < :before AND s.call_site_id = :callSite")
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSite)
                .param("before", at(before))
                .query((rs, n) -> Optional.ofNullable(utcDay(rs.getObject("last_at", OffsetDateTime.class))))
                .single();
    }

    // ---- cases --------------------------------------------------------------------------------------

    /**
     * Every finding of {@code scope} that opened or joined a case and overlaps {@code [from, toExclusive)}: open
     * cases first, then newest onset. A finding without a case never shows. Times are compared as instants, not as
     * the ISO text the columns hold, which drops a zero fraction and so does not sort.
     */
    public List<CaseSpan> caseSpans(String projectId, CaseScope scope, Instant from, Instant toExclusive) {
        String join = "";
        String filter;
        JdbcClient.StatementSpec params;
        switch (scope) {
            case ClassifierOnCallSite _ -> filter = "f.classifier_key = :key AND f.call_site_id = :callSite";
            case DriftBucket _ -> {
                join = " JOIN metric_baseline mb ON mb.project_id = f.project_id AND mb.id = f.subject_id";
                filter = "f.subject_kind = '" + FindingRow.SubjectKind.METRIC_BASELINE + "'"
                        + " AND mb.measure = :measure AND mb.bucket_kind = :bucketKind AND mb.bucket_key = :bucketKey";
            }
            case ToolErrorTool _ ->
                filter = "f.classifier_key = '" + BuiltInDetector.Kind.TOOL_ERROR + "'" + " AND f.subject_kind = '"
                        + FindingRow.SubjectKind.TOOL + "' AND f.subject_id = :toolKey";
        }
        params = jdbc.sql("SELECT f.id AS finding_id, f.onset_at, c.id AS case_id, c.seq, c.title, c.state,"
                        + " c.resolved_at, c.resolution, c.disposition"
                        + " FROM finding f JOIN eval_case c ON c.project_id = f.project_id AND c.id = f.case_id"
                        + join
                        + " WHERE f.project_id = :pid AND f.case_id IS NOT NULL AND " + filter
                        + " AND CAST(f.onset_at AS timestamptz) < :to"
                        + " AND (c.resolved_at IS NULL OR CAST(c.resolved_at AS timestamptz) >= :from)"
                        + " ORDER BY (c.state <> '" + CaseRow.State.RESOLVED + "') DESC,"
                        + " CAST(f.onset_at AS timestamptz) DESC, f.id DESC")
                .param("pid", projectId)
                .param("from", at(from))
                .param("to", at(toExclusive));
        params = switch (scope) {
            case ClassifierOnCallSite c ->
                params.param("key", c.classifierKey()).param("callSite", c.callSiteId());
            case DriftBucket d ->
                params.param("measure", d.measure())
                        .param("bucketKind", d.bucketKind())
                        .param("bucketKey", d.bucketKey());
            case ToolErrorTool t -> params.param("toolKey", t.toolKey());
        };
        return params.query((rs, n) -> {
                    String state = rs.getString("state");
                    boolean resolved = CaseRow.State.RESOLVED.equals(state);
                    return new CaseSpan(
                            rs.getString("finding_id"),
                            rs.getString("case_id"),
                            "C-" + rs.getLong("seq"),
                            rs.getString("title"),
                            state,
                            rs.getString("onset_at"),
                            resolved ? rs.getString("resolved_at") : null,
                            rs.getString("resolution"),
                            rs.getString("disposition"));
                })
                .list();
    }

    /**
     * Open cases per call site, over the scopes the call-site cards read: findings of {@code classifierKeys} filed
     * with a call site, and turn-duration and cost drift on a call-site bucket. A muted case is not open, and a
     * tool-grain drift case counts for its tool, never for the call site it names.
     */
    public Map<String, Integer> openCasesByCallSite(String projectId, Collection<String> classifierKeys) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("""
                        SELECT k, COUNT(DISTINCT case_id) AS n FROM (
                          SELECT f.call_site_id AS k, c.id AS case_id
                            FROM finding f JOIN eval_case c ON c.project_id = f.project_id AND c.id = f.case_id
                           WHERE f.project_id = :pid AND c.state = 'open' AND f.classifier_key IN (:keys)
                             AND f.subject_kind <> 'metric_baseline' AND f.call_site_id IS NOT NULL
                          UNION ALL
                          SELECT mb.bucket_key, c.id
                            FROM finding f JOIN eval_case c ON c.project_id = f.project_id AND c.id = f.case_id
                            JOIN metric_baseline mb ON mb.project_id = f.project_id AND mb.id = f.subject_id
                           WHERE f.project_id = :pid AND c.state = 'open' AND f.subject_kind = 'metric_baseline'
                             AND mb.bucket_kind = 'call_site' AND mb.measure IN (:measures)) x
                         WHERE k <> :unattributed
                         GROUP BY k
                        """)
                .param("pid", projectId)
                .param("keys", classifierKeys.isEmpty() ? List.of("") : classifierKeys)
                .param("measures", List.of(MetricBaselineRow.Measure.TURN_DURATION, MetricBaselineRow.Measure.COST))
                .param("unattributed", UNATTRIBUTED)
                .query((rs, n) -> Map.entry(rs.getString("k"), rs.getInt("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * Open cases per tool key, over Tool Errors and tool-grain Duration Drift. A tool-grain duration bucket also
     * holds {@code mcp:} and {@code retrieval:} actions, and only {@code tool:} keys are tools.
     */
    public Map<String, Integer> openCasesByTool(String projectId) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("""
                        SELECT k, COUNT(DISTINCT case_id) AS n FROM (
                          SELECT f.subject_id AS k, c.id AS case_id
                            FROM finding f JOIN eval_case c ON c.project_id = f.project_id AND c.id = f.case_id
                           WHERE f.project_id = :pid AND c.state = 'open'
                             AND f.classifier_key = 'tool_error' AND f.subject_kind = 'tool'
                          UNION ALL
                          SELECT mb.bucket_key, c.id
                            FROM finding f JOIN eval_case c ON c.project_id = f.project_id AND c.id = f.case_id
                            JOIN metric_baseline mb ON mb.project_id = f.project_id AND mb.id = f.subject_id
                           WHERE f.project_id = :pid AND c.state = 'open' AND f.subject_kind = 'metric_baseline'
                             AND mb.bucket_kind = 'tool' AND mb.measure = 'tool_duration') x
                         WHERE k LIKE 'tool:%'
                         GROUP BY k
                        """)
                .param("pid", projectId)
                .query((rs, n) -> Map.entry(rs.getString("k"), rs.getInt("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Settled live traces per call site since {@code from}. */
    public Map<String, Long> turnsByCallSite(String projectId, Instant from) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                        SELECT COALESCE(call_site_id, '__unattributed__') AS call_site_id, COUNT(*) AS turns
                          FROM trace
                         WHERE project_id = :pid AND is_settled AND is_deleted IS NOT TRUE AND started_at >= :from
                         GROUP BY 1
                        """)
                .param("pid", projectId)
                .param("from", at(from))
                .query((rs, n) -> Map.entry(rs.getString("call_site_id"), rs.getLong("turns")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static RateRow rateRow(ResultSet rs) throws SQLException {
        return new RateRow(hour(rs), rs.getLong("checked"), rs.getLong("flagged"));
    }

    private static RangeRow rangeRow(ResultSet rs) throws SQLException {
        return new RangeRow(hour(rs), rs.getInt("bin"), rs.getLong("n"));
    }

    private static CountRow countRow(ResultSet rs, long total) throws SQLException {
        return new CountRow(
                Instant.ofEpochSecond(rs.getLong("bucket_start")), rs.getLong("events"), rs.getLong("sessions"), total);
    }

    private static Instant hour(ResultSet rs) throws SQLException {
        return rs.getObject("hour", OffsetDateTime.class).toInstant();
    }

    /**
     * The 0-based bin of {@code value} on the grid the {@code :logLo}, {@code :logHi} and {@code :bins} parameters
     * lay out. A value outside the grid is clamped into the first or last bin, and one at or below zero, which has no
     * log, is the first.
     */
    private static String bin(String value) {
        return "CASE WHEN " + value + " <= 0 THEN 0 ELSE GREATEST(0, LEAST(CAST(:bins AS int) - 1, width_bucket(ln("
                + value + "), CAST(:logLo AS float8), CAST(:logHi AS float8), CAST(:bins AS int)) - 1)) END";
    }

    private static JdbcClient.StatementSpec grid(JdbcClient.StatementSpec spec, Grid grid) {
        return spec.param("logLo", grid.logLo()).param("logHi", grid.logHi()).param("bins", grid.bins());
    }

    private static @Nullable Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable LocalDate utcDay(@Nullable OffsetDateTime at) {
        return at == null ? null : at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
