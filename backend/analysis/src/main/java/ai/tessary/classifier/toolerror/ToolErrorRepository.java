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
 * failure signatures behind them. Design contract: {@code classifiers/tool_error/PROGRAM.md} §5.
 *
 * <p><b>Two queries, both aggregates, and no cursor anywhere.</b> There is no sweep here and no state
 * to advance — {@code ToolErrorTrend} replays these buckets through the detector on every read, which
 * is what {@code TrendService} does for grader pass rate and for the same reason. The whole class of
 * double-count bug that a cursor and a watermark exist to prevent cannot arise, because nothing is
 * accumulated across passes.
 *
 * <p>The counts and the signatures are separate queries deliberately. The count query is the one the
 * detector reads and it must stay cheap and complete; the signature query touches {@code error_type}
 * and the result payload and is only ever asked about the handful of tools that actually alarmed. Folding
 * them together would make every read pay the second query's cost to answer the first one's question.
 *
 * <p>{@link #namesByToolKey} is the third, and it exists only to join the other two: the counts come back
 * keyed by a NORMALIZED bucket key while the signatures are selected by the RAW column, and nothing but a
 * read can map one to the other.
 *
 * <p>Both interpolate {@link ToolFailure#SQL_PREDICATE}, which is why both alias {@code tool_call} as
 * {@code tc} and the SPAN as {@code o} — that aliasing is part of the constant's contract, and the
 * alias letter is kept rather than renamed to {@code s} precisely because the constant depends on it.
 *
 * <p><b>The join is on producer keys.</b> {@code tool_call} reaches its span through
 * {@code (project_id, trace_id, span_id)} rather than the bare unscoped {@code observation_id}
 * it used, which was only correct while span ids were platform-minted surrogates.
 */
@Repository
public class ToolErrorRepository {

    /**
     * The producer-key join from a tool call to its span, and the settle gate on that span's trace.
     *
     * <p>{@code trace.is_settled} replaces the caller-supplied settle window. The window existed because
     * a turn's calls arrive across several exporter flushes, so reading the last few minutes counted some
     * of a turn's calls and not others — with no reason to believe the ones that landed first fail at the
     * same rate. The flag says the thing the window was estimating, per trace rather than per clock.
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
     * <p><b>Why no fallback to {@code created_at}.</b> That is the INGEST clock, and bucketing on it is
     * not an approximation, it is a different question. A backfill lands months of traffic in one ingest
     * burst, so hourly buckets cut on ingest time pile that whole history into the hour we happened to
     * receive it — "nothing happened in June, then ninety thousand calls arrived on Tuesday afternoon".
     * A CUSUM fed that sequence is not measuring anything.
     *
     * <p><b>Why no fallback to {@code event_ts} either.</b> Ingest is the only writer of this table and
     * it writes {@code event_ts} and {@code started_at} from the same span, so the arm
     * never chose anything different — and one column is one system of record. The column stays for the
     * other eight tables that use it, notably retention; retiring it there is its own migration.
     *
     * <p>A row without a start time is therefore SKIPPED, not guessed at. That is safe for a rate because
     * a missing start time is a property of the producer rather than of the call's outcome — an OTLP span
     * is stamped when it is created, before the operation runs, so even a call that crashed has one.
     * Numerator and denominator drop together and the fraction is unchanged. {@link #skippedNoStartedAt}
     * counts them anyway, so a producer that silently stops sending timestamps shows up as a number
     * rather than as detection quietly getting slower.
     */
    private static final String EVENT_AT = "tc.started_at";

    private final JdbcClient jdbc;

    public ToolErrorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One tool's outcomes in one hour.
     *
     * @param bucket the hour, in event time, as an ISO-8601 UTC instant.
     *     <p><b>Normalized here, never handed out as the driver rendered it.</b> {@code date_trunc}
     *     returns a {@code timestamptz}, and reading that as a string gives Postgres's own text form in
     *     the SESSION's timezone — {@code 2026-08-10 03:00:00+05:30}. That value travels: it becomes a
     *     spell's onset, which becomes {@code behavior_finding.first_seen_at}, which
     *     {@code ToolErrorCaseSource} parses as an {@link Instant}. It does not parse, so every
     *     tool-error onset arrived at {@code CaseLedger} as null and no tool-error case could ever be
     *     bracketed or reopened. It is also compared lexicographically against
     *     {@code AcceptedReference#acceptedAt}, which IS ISO — and {@code ' '} sorts below {@code 'T'},
     *     so the same instant compared as earlier. One format, fixed at the boundary.
     * @param toolKey the {@code ActionSymbol} the tool buckets under — resolved in SQL from
     *     {@code tool_call.name}, matching what {@code tool_duration} keys on so a tool-error finding
     *     and a tool-duration finding name the same thing
     * @param calls every call, failing and succeeding alike. The rate's denominator, and the reason
     *     this is not a query for failures
     */
    public record HourlyToolTally(String bucket, String toolKey, long calls, long failures) {}

    /** One failure signature's count for one tool over the whole window. */
    public record SignatureTally(String toolKey, String signature, String source, long count) {}

    /**
     * Hourly call and failure counts per tool since {@code from}.
     *
     * <p>Ordered oldest-first because the replay is sequential: a CUSUM fed out of order is not a CUSUM.
     *
     * <p>Only SETTLED traces are counted, via {@link #SPAN_JOIN}. A tool call is one span and its
     * arrival is its own completion signal, but the DENOMINATOR is not one span — a turn's calls arrive
     * across several exporter flushes, so counting a turn mid-flight counts some of its calls and not
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
     * The raw {@code tool_call.name} values behind each bucket key, over the same window and the same
     * predicate {@link #hourlyTallies} grouped — the inverse of the normalization it applied.
     *
     * <p><b>A bucket key is not a name and must never be compared against one.</b> It is
     * {@link ToolErrorBuckets#toolKey}'s reduction of one: lowercased, uuids and digit runs and a trailing
     * numeric id stripped, every other run of non-alphanumerics collapsed to an underscore. Reversing that
     * by hand — dropping the {@code tool:} prefix and matching what is left — is what {@link #failuresFor}
     * used to be handed, and it silently matched nothing for every tool whose name was not already
     * lowercase snake_case. A project calling {@code WebSearch} bucketed to {@code tool:websearch}, the
     * signature read asked for {@code name = 'websearch'}, and the finding was written with no patterns and
     * no exemplar — which Layer 2 refuses to rule on, so the tool was permanently unanalyzable while its
     * rate numbers, which never leave the normalized side, stayed correct and made the finding look healthy.
     *
     * <p>One-to-many, and that is not an edge case: {@code search_docs_3} and {@code search_docs_4} are one
     * bucket by design, so a caller must match on the whole set or it drops half the evidence.
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
     * Tool calls in the same window that {@link #hourlyTallies} could not place on the event clock, and
     * therefore did not count.
     *
     * <p>Not a correctness mechanism — the fraction those rows would have contributed is the same one the
     * counted rows already report, because a missing start time says something about the producer and
     * nothing about whether the call failed. It is an instrument. A producer that stops sending
     * timestamps makes detection quietly slower and nothing else in the system would say so; this makes
     * it one number in the log line beside the buckets that were used.
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
     * <p>Asked only about a tool that has already alarmed, which is what makes it affordable to read the
     * result payload at all. Returns one row per failing call rather than a {@code GROUP BY}: the
     * grouping key is {@link ToolFailure#signature}, which normalizes a free-text message in Java, and
     * SQL cannot compute it. Bounded by {@code limit} for exactly that reason.
     *
     * @param toolNames the RAW names in this tool's bucket, from {@link #namesByToolKey} — never a bucket
     *     key, and never one name when the bucket holds several. Empty short-circuits rather than emitting
     *     {@code IN ()}, which is a syntax error.
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
     * Every call of one tool in a window, as span references — the POPULATION a rate was measured over,
     * for {@code finding_evidence}.
     *
     * <p>Every call, not every failure: the rate's denominator is what the claim is about, and a
     * reader handed only the failures cannot check the denominator the detector divided by. The grain
     * is the span, because a tool call IS a span and that is the row {@link #hourlyTallies} counted.
     *
     * <p><b>Deliberately unbounded.</b> This is the enumerated population behind one finding-open, not
     * a page; truncating it here would hand Layer 2 a sample with an undeclared selection rule, which
     * is the one thing the evidence contract forbids. The predicate is
     * {@link #hourlyTallies}' character for character, minus the hour bucketing, so what is enumerated
     * and what was counted cannot diverge.
     */
    public List<SpanRef> callRefsFor(String projectId, List<String> toolNames, Instant from, Instant to) {
        return spanRefs(projectId, toolNames, from, to, false);
    }

    /**
     * The FAILING calls of one tool in a window, as span references — the numerator the same window's
     * {@link #callRefsFor} is the denominator of.
     *
     * <p>Selected by {@link ToolFailure#SQL_PREDICATE}, which is the predicate {@link #hourlyTallies}
     * counts its {@code failures} with. Enumerating on any other rule would let the evidence and the
     * rate disagree about which calls failed.
     *
     * <p><b>Deliberately unbounded</b>, on the same grounds as {@link #callRefsFor}: this is the
     * enumerated failing population, not a page, and a cap would be a sample with an undeclared
     * selection rule.
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

    /** One tool call's composite span key — both halves, or it resolves to nothing under substrate v2. */
    public record SpanRef(String traceId, String spanId) {}

    /**
     * One failing call's error-bearing fields — the five {@link ToolFailure#recognize} reads, plus the
     * trace the call happened in.
     *
     * <p>The trace id is not part of the decision and never reaches the detector. It is carried so a
     * finding can point at traces where the failures actually happened: Layer 2 refuses to rule on a
     * finding with no exemplar, and a rate claim with no reachable instance of the thing it is claiming
     * about is also not much use to a human reading the case.
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
