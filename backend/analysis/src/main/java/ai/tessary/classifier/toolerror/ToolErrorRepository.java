// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one read behind the tool-error classifier: hourly call and failure counts per tool, plus the
 * failure signatures behind them.
 *
 * <p>Two queries, both aggregates, and no cursor anywhere: {@code ToolErrorTrend} replays these
 * buckets through the detector on every read, the same way {@code TrendService} does for grader
 * pass rate, so the double-count bug a cursor and watermark exist to prevent can't arise.
 *
 * <p>The counts and the signatures are separate queries deliberately. The count query is the one
 * the detector reads and must stay cheap and complete; the signature query touches {@code
 * error_type} and the result payload and is only ever asked about the handful of tools that
 * actually alarmed. Folding them together would make every read pay the second query's cost to
 * answer the first one's question.
 *
 * <p>{@link #namesByToolKey} exists only to join the other two: the counts come back keyed by a
 * normalized bucket key while the signatures are selected by the raw column.
 *
 * <p>Both interpolate {@link ToolFailure#SQL_PREDICATE}, which is why both alias {@code tool_call}
 * as {@code tc} and the span as {@code o}: that aliasing is part of the constant's contract.
 *
 * <p>The join is on producer keys: {@code tool_call} reaches its span through {@code (project_id,
 * trace_id, span_id)} rather than a bare unscoped id.
 */
@Repository
public class ToolErrorRepository {

    /**
     * The producer-key join from a tool call to its span, and the settle gate on that span's trace.
     *
     * <p>{@code trace.is_settled} avoids a caller-supplied settle window: a turn's calls arrive
     * across several exporter flushes, so reading the last few minutes would count some of a turn's
     * calls and not others, with no reason to believe the ones that landed first fail at the same
     * rate. The flag settles that per trace rather than per clock.
     */
    private static final String SPAN_JOIN = """
            JOIN span o ON o.project_id = tc.project_id
                       AND o.trace_id = tc.trace_id AND o.id = tc.span_id
            JOIN trace tr ON tr.project_id = o.project_id AND tr.id = o.trace_id
            LEFT JOIN span_payload pl
              ON pl.project_id = o.project_id AND pl.trace_id = o.trace_id AND pl.span_id = o.id
            """;

    /**
     * The event clock, and only the event clock: {@code tc.started_at}, with no fallback.
     *
     * <p>Never {@code created_at}, the ingest clock: bucketing on it is a different question, not an
     * approximation. A backfill lands months of traffic in one ingest burst, so hourly buckets cut
     * on ingest time would pile that whole history into the hour it happened to arrive, and a CUSUM
     * fed that sequence measures nothing real.
     *
     * <p>A row without a start time is skipped, not guessed at. That's safe for a rate: a missing
     * start time is a property of the producer, not the call's outcome, since an OTLP span is
     * stamped when created, before the operation runs, so even a crashed call has one. Numerator and
     * denominator drop together and the fraction is unchanged. {@link #skippedNoStartedAt} counts
     * them anyway, so a producer that silently stops sending timestamps shows up as a number rather
     * than as detection quietly getting slower.
     */
    private static final String EVENT_AT = "tc.started_at";

    private final JdbcClient jdbc;

    public ToolErrorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One tool's outcomes in one hour.
     *
     * @param bucket the hour, in event time, as an ISO-8601 UTC instant. Normalized here rather than
     *     handed out as the driver rendered it: {@code date_trunc} returns a {@code timestamptz},
     *     and reading that as a string gives Postgres's session-timezone text form, which downstream
     *     consumers (an {@link Instant} parse, a lexicographic comparison against an ISO string)
     *     cannot read correctly.
     * @param toolKey the {@code ActionSymbol} the tool buckets under, resolved in SQL from {@code
     *     tool_call.name}, matching what {@code tool_duration} keys on so a tool-error finding and a
     *     tool-duration finding name the same thing
     * @param calls every call, failing and succeeding alike: the rate's denominator, and the reason
     *     this is not a query for failures
     */
    public record HourlyToolTally(String bucket, String toolKey, long calls, long failures) {}

    /** One failure signature's count for one tool over the whole window. */
    public record SignatureTally(String toolKey, String signature, String source, long count) {}

    /**
     * Hourly call and failure counts per tool since {@code from}.
     *
     * <p>Ordered oldest-first because the replay is sequential: a CUSUM fed out of order is not a
     * CUSUM.
     *
     * <p>Only settled traces are counted, via {@link #SPAN_JOIN}: a turn's calls arrive across
     * several exporter flushes, so counting a turn mid-flight would count some of its calls and not
     * others, with no reason to believe the ones that landed first fail at the same rate.
     */
    public List<HourlyToolTally> hourlyTallies(String projectId, Instant from) {
        return jdbc.sql("SELECT date_trunc('hour', " + EVENT_AT
                        + ") AS bucket, "
                        + "COALESCE(NULLIF(tc.name, ''), 'unnamed') AS tool_name, COUNT(*) AS calls, "
                        + "COUNT(*) FILTER (WHERE " + ToolFailure.SQL_PREDICATE + ") AS failures "
                        + """
                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND o.is_deleted IS NOT TRUE
                          AND tr.is_settled
                          AND\s""" + EVENT_AT + """
                           >= :from
                        GROUP BY 1, 2
                        ORDER BY 1 ASC
                        """)
                .param("pid", projectId)
                .param("from", at(from))
                .query((rs, n) -> new HourlyToolTally(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        ToolErrorBuckets.toolKey(rs.getString("tool_name")),
                        rs.getLong("calls"),
                        rs.getLong("failures")))
                .list();
    }

    /**
     * The raw {@code tool_call.name} values behind each bucket key, over the same window and
     * predicate {@link #hourlyTallies} grouped: the inverse of the normalization it applied.
     *
     * <p>A bucket key is not a name and must never be compared against one. It's {@link
     * ToolErrorBuckets#toolKey}'s reduction of one: lowercased, uuids and digit runs and a trailing
     * numeric id stripped, every other run of non-alphanumerics collapsed to an underscore.
     * Reversing that by hand instead of reading it back from this method silently matches nothing
     * for a tool whose name isn't already lowercase snake_case, and leaves its finding with no
     * exemplar for a human to look at.
     *
     * <p>One-to-many, and that's not an edge case: {@code search_docs_3} and {@code search_docs_4}
     * are one bucket by design, so a caller must match on the whole set or drop half the evidence.
     */
    public Map<String, List<String>> namesByToolKey(String projectId, Instant from) {
        List<String> names = jdbc.sql("SELECT DISTINCT COALESCE(NULLIF(tc.name, ''), 'unnamed') AS tool_name "
                        + """
                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND o.is_deleted IS NOT TRUE
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

    /**
     * Tool calls in the same window that {@link #hourlyTallies} couldn't place on the event clock,
     * and so didn't count.
     *
     * <p>Not a correctness mechanism: a missing start time says something about the producer, not
     * whether the call failed, so the fraction these rows would have contributed matches the counted
     * rows already. It's an instrument, so a producer that stops sending timestamps shows up as a
     * number instead of detection quietly getting slower with nothing to say why.
     */
    public long skippedNoStartedAt(String projectId, Instant from) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM tool_call tc
                        """ + SPAN_JOIN + """
                        WHERE tc.project_id = :pid
                          AND tc.is_deleted IS NOT TRUE
                          AND o.is_deleted IS NOT TRUE
                          AND tc.started_at IS NULL
                          AND tc.created_at >= :from
                        """)
                .param("pid", projectId)
                .param("from", at(from))
                .query(Long.class)
                .single();
    }

    /**
     * The raw failure descriptions for one tool over a window, for signature grouping.
     *
     * <p>Asked only about a tool that has already alarmed, which is what makes it affordable to read
     * the result payload at all. Returns one row per failing call rather than a {@code GROUP BY}:
     * the grouping key is {@link ToolFailure#signature}, which normalizes a free-text message in
     * Java, and SQL cannot compute it. Bounded by {@code limit} for exactly that reason.
     *
     * @param toolNames the raw names in this tool's bucket, from {@link #namesByToolKey}, never a
     *     bucket key, and never one name when the bucket holds several. Empty short-circuits rather
     *     than emitting {@code IN ()}, which is a syntax error.
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
                          AND o.is_deleted IS NOT TRUE
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
     * Every call of one tool in a window, as span references: the population a rate was measured
     * over, for {@code finding_evidence}.
     *
     * <p>Every call, not every failure: the rate's denominator is what the claim is about, and a
     * reader handed only the failures can't check the denominator the detector divided by.
     *
     * <p>Deliberately unbounded: this is the enumerated population behind one finding, not a page,
     * and truncating it would hand Layer 2 a sample with an undeclared selection rule. The predicate
     * matches {@link #hourlyTallies} character for character, minus the hour bucketing, so what's
     * enumerated and what was counted can't diverge.
     */
    public List<SpanRef> callRefsFor(String projectId, List<String> toolNames, Instant from, Instant to) {
        return spanRefs(projectId, toolNames, from, to, false);
    }

    /**
     * The failing calls of one tool in a window, as span references: the numerator the same window's
     * {@link #callRefsFor} is the denominator of.
     *
     * <p>Selected by {@link ToolFailure#SQL_PREDICATE}, the same predicate {@link #hourlyTallies}
     * counts its {@code failures} with, so the evidence and the rate can't disagree about which calls
     * failed. Deliberately unbounded, on the same grounds as {@link #callRefsFor}.
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
                          AND o.is_deleted IS NOT TRUE
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

    /** One tool call's composite span key: both halves, or it resolves to nothing. */
    public record SpanRef(String traceId, String spanId) {}

    /**
     * One failing call's error-bearing fields, the five {@link ToolFailure#recognize} reads, plus the
     * trace the call happened in.
     *
     * <p>The trace id is not part of the decision and never reaches the detector. It's carried so a
     * finding can point at traces where the failures actually happened: Layer 2 refuses to rule on a
     * finding with no exemplar.
     */
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
