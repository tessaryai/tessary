// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Hourly call and failure counts per tool, plus failure signatures. No cursor: {@code ToolErrorTrend} replays the
 * buckets on every read, so double counting cannot arise. Signatures are a separate, costlier query asked only for
 * tools that alarmed. Both interpolate {@link ToolFailure#SQL_PREDICATE}, whose contract needs the {@code tc} and
 * {@code o} aliases.
 */
@Repository
public class ToolErrorRepository {

    /**
     * {@code trace.is_settled} rather than a time window: a turn's calls arrive across flushes, and counting part of a
     * turn biases the rate.
     *
     * <p>The span is outer-joined and kept only when live via {@code NOT COALESCE(o.is_deleted, TRUE)}: as an inner join
     * Postgres estimates about one row and probes {@code trace} once per call in a serial nested loop. Nothing else may
     * be strict in {@code o}, or the outer join turns back into an inner one, so the trace and the payload join on the
     * call's own keys.
     */
    private static final String SPAN_JOIN = """
            LEFT JOIN span o ON o.project_id = tc.project_id
                            AND o.trace_id = tc.trace_id AND o.id = tc.span_id
            JOIN trace tr ON tr.project_id = tc.project_id AND tr.id = tc.trace_id
            LEFT JOIN span_payload pl
              ON pl.project_id = tc.project_id AND pl.trace_id = tc.trace_id AND pl.span_id = tc.span_id
            """;

    /**
     * Never {@code created_at}: a backfill would pile months into one ingest hour. A row without a start time is
     * skipped (numerator and denominator drop together) and counted by {@link #skippedNoStartedAt}.
     */
    private static final String EVENT_AT = "tc.started_at";

    private final JdbcClient jdbc;

    public ToolErrorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code bucket} is normalized to ISO-8601 UTC, since a timestamptz read as text is in the session timezone.
     * {@code toolKey} matches what {@code tool_duration} keys on.
     */
    public record HourlyToolTally(String bucket, String toolKey, long calls, long failures) {}

    /**
     * The anchor {@link ToolErrorService#refresh} replays back from, served by an index scan on
     * {@code ix_tool_call_project_started}.
     */
    public Optional<Instant> newestEventAt(String projectId) {
        return jdbc.sql("""
                        SELECT MAX(tc.started_at) AS newest
                        FROM tool_call tc
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                        """)
                .param("pid", projectId)
                .query((rs, n) -> rs.getObject("newest", OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /** Oldest-first: a CUSUM fed out of order is not a CUSUM. Settled traces only, via {@link #SPAN_JOIN}. */
    public List<HourlyToolTally> hourlyTallies(String projectId, Instant from) {
        return hourlyTallies(jdbc.sql(hourlyTalliesSql("")), projectId, from);
    }

    /**
     * {@link #hourlyTallies(String, Instant)} for the calls whose {@code tool_name} is one of {@code toolNames}: one
     * tool's rows, read without counting every other tool's. Empty short-circuits rather than emitting {@code IN ()}.
     */
    public List<HourlyToolTally> hourlyTallies(String projectId, Instant from, Collection<String> toolNames) {
        if (toolNames.isEmpty()) return List.of();
        return hourlyTallies(
                jdbc.sql(hourlyTalliesSql("  AND COALESCE(NULLIF(tc.name, ''), 'unnamed') IN (:tools)\n"))
                        .param("tools", toolNames),
                projectId,
                from);
    }

    private static String hourlyTalliesSql(String nameFilter) {
        return "SELECT date_trunc('hour', " + EVENT_AT
                + ") AS bucket, "
                + "COALESCE(NULLIF(tc.name, ''), 'unnamed') AS tool_name, COUNT(*) AS calls, "
                + "COUNT(*) FILTER (WHERE " + ToolFailure.SQL_PREDICATE + ") AS failures "
                + """
                FROM tool_call tc
                """ + SPAN_JOIN + """
                WHERE tc.project_id = :pid
                  AND tc.is_deleted IS NOT TRUE
                  AND NOT COALESCE(o.is_deleted, TRUE)
                  AND tr.is_settled
                  AND\s""" + EVENT_AT + """
                   >= :from
                """ + nameFilter + """
                GROUP BY 1, 2
                ORDER BY 1 ASC
                """;
    }

    private List<HourlyToolTally> hourlyTallies(JdbcClient.StatementSpec spec, String projectId, Instant from) {
        return spec.param("pid", projectId)
                .param("from", at(from))
                .query((rs, n) -> new HourlyToolTally(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        ToolErrorBuckets.toolKey(rs.getString("tool_name")),
                        rs.getLong("calls"),
                        rs.getLong("failures")))
                .list();
    }

    /**
     * The inverse of the bucket normalization, read back rather than reversed by hand, which silently matches nothing.
     * One-to-many by design, so callers must match the whole set.
     */
    public Map<String, List<String>> namesByToolKey(String projectId, Instant from) {
        List<String> names = jdbc.sql("SELECT DISTINCT COALESCE(NULLIF(tc.name, ''), 'unnamed') AS tool_name "
                        + """
                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND NOT COALESCE(o.is_deleted, TRUE)
                          AND tr.is_settled
                          AND\s""" + EVENT_AT + """
                           >= :from
                        """)
                .param("pid", projectId)
                .param("from", at(from))
                .query((rs, n) -> rs.getString("tool_name"))
                .list();
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (String name : names) {
            byKey.computeIfAbsent(ToolErrorBuckets.toolKey(name), k -> new ArrayList<>())
                    .add(name);
        }
        return Map.copyOf(byKey);
    }

    /** An instrument, not correctness: a producer that stops sending timestamps shows up as a number. */
    public long skippedNoStartedAt(String projectId, Instant from) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND NOT COALESCE(o.is_deleted, TRUE)
                          AND tc.started_at IS NULL
                          AND tc.created_at >= :from
                        """)
                .param("pid", projectId)
                .param("from", at(from))
                .query(Long.class)
                .single();
    }

    /**
     * One row per failing call, because {@link ToolFailure#signature} normalizes in Java; bounded by {@code limit}.
     * {@code toolNames} are raw names, never a bucket key. Empty short-circuits, since {@code IN ()} is a syntax error.
     */
    public List<RawFailure> failuresFor(String projectId, List<String> toolNames, Instant from, Instant to, int limit) {
        if (toolNames.isEmpty()) return List.of();
        return jdbc.sql("SELECT tc.error_type, tc.is_error, pl.attributes ->> 'error.type' AS error_attr, "
                        + "pl.attributes ->> 'exception.type' AS exception_attr, tc.result, o.trace_id "
                        + """
                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND NOT COALESCE(o.is_deleted, TRUE)
                          AND COALESCE(NULLIF(tc.name, ''), 'unnamed') IN (:tools)
                          AND\s""" + EVENT_AT + """
                           >= :from
                          AND\s""" + EVENT_AT + """
                           < :to
                          AND\s""" + ToolFailure.SQL_PREDICATE + "\n"
                        + """
                        ORDER BY\s""" + EVENT_AT + """
                         DESC
                        LIMIT :lim
                        """)
                .param("pid", projectId)
                .param("tools", toolNames)
                .param("from", at(from))
                .param("to", at(to))
                .param("lim", limit)
                .query((rs, n) -> new RawFailure(
                        rs.getString("error_type"),
                        rs.getBoolean("is_error"),
                        rs.getString("error_attr"),
                        rs.getString("exception_attr"),
                        rs.getString("result"),
                        rs.getString("trace_id")))
                .list();
    }

    /**
     * Every call, not every failure: the denominator is what the claim is about. Deliberately unbounded, and the
     * predicate matches {@link #hourlyTallies} exactly.
     */
    public List<SpanRef> callRefsFor(String projectId, List<String> toolNames, Instant from, Instant to) {
        return spanRefs(projectId, toolNames, from, to, false);
    }

    /**
     * The same {@link ToolFailure#SQL_PREDICATE} {@link #hourlyTallies} counts with, so evidence and rate agree.
     * Unbounded.
     */
    public List<SpanRef> failingCallRefsFor(String projectId, List<String> toolNames, Instant from, Instant to) {
        return spanRefs(projectId, toolNames, from, to, true);
    }

    private List<SpanRef> spanRefs(
            String projectId, List<String> toolNames, Instant from, Instant to, boolean failingOnly) {
        if (toolNames.isEmpty()) return List.of();
        return jdbc.sql("SELECT o.trace_id, o.id AS span_id"
                        + """

                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND NOT COALESCE(o.is_deleted, TRUE)
                          AND tr.is_settled
                          AND COALESCE(NULLIF(tc.name, ''), 'unnamed') IN (:tools)
                          AND\s""" + EVENT_AT + """
                           >= :from
                          AND\s""" + EVENT_AT + """
                           < :to
                        """
                        + (failingOnly ? "  AND " + ToolFailure.SQL_PREDICATE + "\n" : "")
                        + """
                        ORDER BY\s""" + EVENT_AT + """
                         ASC, o.id ASC
                        """)
                .param("pid", projectId)
                .param("tools", toolNames)
                .param("from", at(from))
                .param("to", at(to))
                .query((rs, n) -> new SpanRef(rs.getString("trace_id"), rs.getString("span_id")))
                .list();
    }

    /** Both halves, or it resolves to nothing. */
    public record SpanRef(String traceId, String spanId) {}

    /** The trace id never reaches the detector; it lets a finding point at traces where failures happened. */
    public record RawFailure(
            @Nullable String errorType,
            boolean isError,
            @Nullable String errorTypeAttr,
            @Nullable String exceptionAttr,
            @Nullable String resultJson,
            @Nullable String traceId) {}

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
