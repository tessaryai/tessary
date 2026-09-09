// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The single owner of the vitals SQL: two deterministic aggregations over already-ingested substrate,
 * grouped by a dimension.
 *
 * <p><b>Two, not three.</b> Tool errors used to be the third and left with the surface that showed them:
 * they are a classifier now ({@code classifiers/tool_error/PROGRAM.md}), watched per tool against that
 * tool's own past. Nothing in this package reads {@code tool_call} any more.
 *
 * <p><b>Everything here is a read.</b> The slice writes no rows and enqueues no jobs, which is what
 * makes it structurally incapable of reaching Layer 2 — the guarantee is architectural rather than a
 * flag someone can flip back on.
 *
 * <h2>What the v2 substrate changed here</h2>
 *
 * <p>This used to ship every {@code llm} observation in the window to the application, parse each one's
 * {@code usage} jsonb in Java, and price it against {@link TokenPriceBook} at read time. Three things were
 * wrong with that and all three are gone:
 *
 * <ul>
 *   <li><b>Cost moved to write time.</b> {@code span.input_cost}/{@code output_cost}/{@code total_cost}
 *       are priced on arrival and never repriced (spec rule 3), so this now sums stored numerics. A figure
 *       shown here no longer changes because a rate file was refreshed under it.
 *   <li><b>Usage is typed.</b> The buckets are columns, so the database aggregates them and the wire
 *       carries one row per dimension value rather than one per generation.
 *   <li><b>Settling is a fact, not a guess.</b> The 300-second "probably complete by now" window is
 *       replaced by {@code trace.is_settled}, which means precisely "nothing has arrived since the last
 *       rollup" (spec §7.4). It is stricter than the heuristic it replaces, and honest where the
 *       heuristic was merely conservative.
 * </ul>
 *
 * <p>Duration still comes off the turn rather than the spans: {@code trace.latency_ms} is the root
 * span's own start→end, which is what Jaeger, Tempo and Datadog report as trace duration. Measured
 * against production, a min/max envelope over children inflated p95 by ~10% because async children
 * outlive their parent.
 */
@Repository
public class VitalsRepository {

    /**
     * The grouping dimension. Deliberately a closed enum mapped to a column rather than a
     * caller-supplied string — this interpolates into SQL, so the set of legal values has to be ours.
     */
    public enum Dimension {
        CALL_SITE("call_site_id"),
        MODEL("provided_model_name");

        private final String column;

        Dimension(String column) {
            this.column = column;
        }

        String column() {
            return column;
        }
    }

    private final JdbcClient jdbc;

    public VitalsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Spend and usage in a window, grouped by dimension — one row per dimension value, already summed.
     *
     * <p>Scoped to {@code kind = 'llm'} deliberately. A container span ({@code agent}, {@code chain})
     * reports the CUMULATIVE usage of its subtree, so summing across kinds roughly doubles every figure.
     * The v2 ingest already refuses to write typed usage on container kinds for exactly this reason, but
     * the scope stays explicit here so the statistic does not silently change meaning if that rule ever
     * loosens.
     *
     * <p><b>{@code unpriced} counts what could not be priced, and it is never folded into {@code usd}.</b>
     * A real spend rendered as free is worse than an admitted gap, which is why {@code cost_source} exists
     * as a written fact rather than something a reader infers from a null.
     *
     * <p>Only settled traces contribute. A trace still receiving spans has a partial cost, and averaging
     * partials into a window makes spend look like it fell.
     */
    public List<UsageRow> usageIn(String projectId, Instant from, Instant to, Dimension by) {
        String dim = "COALESCE(s." + by.column() + ", '" + UNATTRIBUTED + "')";
        return jdbc.sql("SELECT " + dim + " AS dim,"
                        + " sum(s.total_tokens) AS tokens,"
                        + " sum(s.input_tokens) AS input_tokens,"
                        + " sum(s.output_tokens) AS output_tokens,"
                        + " sum(s.total_cost) AS usd,"
                        + " count(*) FILTER (WHERE s.total_tokens IS NOT NULL) AS calls,"
                        + " count(*) FILTER (WHERE s.cost_source = '" + UNPRICED + "'"
                        + "                    AND s.total_tokens IS NOT NULL) AS unpriced_calls,"
                        + " count(DISTINCT s.trace_id) FILTER (WHERE s.total_tokens IS NOT NULL)"
                        + "     AS spending_traces"
                        + " FROM span s JOIN trace t"
                        + "   ON t.project_id = s.project_id AND t.id = s.trace_id"
                        + " WHERE s.project_id = :pid"
                        + "   AND s.kind = 'llm'"
                        + "   AND NOT s.is_deleted"
                        + "   AND s.started_at >= :from AND s.started_at < :to"
                        + "   AND t.is_settled"
                        + " GROUP BY " + dim)
                .param("pid", projectId)
                .param("from", at(from))
                .param("to", at(to))
                .query((rs, n) -> new UsageRow(
                        rs.getString("dim"),
                        longOrZero(rs, "tokens"),
                        longOrZero(rs, "input_tokens"),
                        longOrZero(rs, "output_tokens"),
                        usd(rs),
                        rs.getLong("calls"),
                        rs.getLong("unpriced_calls"),
                        rs.getLong("spending_traces")))
                .list();
    }

    /**
     * Turn durations in a window, grouped by dimension — one row per completed turn.
     *
     * <p>The duration is {@code trace.latency_ms}, a stored column derived from the trace's own
     * start and end. The dimension comes from the turn's entry point: for {@code call_site} that is the
     * trace's own {@code call_site_id}, copied down from the root span by the rollup (implementation plan
     * §2.2), which is one column read instead of a LATERAL per trace. For {@code model} it is the root
     * span's model, which root spans do not carry — so that view buckets everything as
     * {@code __unattributed__}, exactly as it did before, and the cost statistic is the one that
     * discriminates by model.
     *
     * <p>No settle gate: {@code latency_ms} is non-null only once the trace has an end, and a trace's end
     * is its root span's, which an exporter flushes last. Its presence IS the completion signal.
     */
    public List<DurationRow> turnDurationsIn(String projectId, Instant from, Instant to, Dimension by) {
        String dim = by == Dimension.CALL_SITE
                ? "COALESCE(t.call_site_id, '" + UNATTRIBUTED + "')"
                : "COALESCE(r.provided_model_name, '" + UNATTRIBUTED + "')";
        String rootJoin = by == Dimension.CALL_SITE
                ? ""
                : " LEFT JOIN LATERAL (SELECT provided_model_name FROM span rs"
                        + "   WHERE rs.project_id = t.project_id AND rs.trace_id = t.id"
                        + "     AND rs.parent_span_id IS NULL AND NOT rs.is_deleted"
                        + "   ORDER BY rs.started_at, rs.id LIMIT 1) r ON TRUE";
        return jdbc.sql("SELECT " + dim + " AS dim, t.id AS trace_id, t.latency_ms AS ms"
                        + " FROM trace t" + rootJoin
                        + " WHERE t.project_id = :pid"
                        + "   AND NOT t.is_deleted"
                        + "   AND t.latency_ms IS NOT NULL"
                        + "   AND t.started_at >= :from AND t.started_at < :to")
                .param("pid", projectId)
                .param("from", at(from))
                .param("to", at(to))
                .query((rs, n) -> new DurationRow(rs.getString("dim"), rs.getString("trace_id"), rs.getDouble("ms")))
                .list();
    }

    /**
     * Turns that never ended, in a window — counted, never silently dropped.
     *
     * <p>An abandoned or still-running turn has no end, so it cannot contribute a duration. Excluding it
     * quietly would let a rising number of stuck turns read as improving latency, which is the exact
     * inversion that makes a metric worse than none. The count is surfaced beside the percentiles instead.
     *
     * <p>{@code GROUPING SETS} gives the per-dimension rows AND a grand total in one pass. The total is
     * taken from the set rather than summed from the buckets, because a trace contributes to exactly one
     * bucket here and the two must agree regardless.
     */
    public List<UnterminatedRow> unterminatedTurnsIn(String projectId, Instant from, Instant to, Dimension by) {
        String dim = by == Dimension.CALL_SITE
                ? "COALESCE(t.call_site_id, '" + UNATTRIBUTED + "')"
                : "'" + UNATTRIBUTED + "'";
        return jdbc.sql("SELECT " + dim + " AS dim, GROUPING(" + dim + ") AS is_total,"
                        + " COUNT(*) AS turns"
                        + " FROM trace t"
                        + " WHERE t.project_id = :pid"
                        + "   AND NOT t.is_deleted"
                        + "   AND t.ended_at IS NULL"
                        + "   AND t.started_at >= :from AND t.started_at < :to"
                        + " GROUP BY GROUPING SETS ((" + dim + "), ())")
                .param("pid", projectId)
                .param("from", at(from))
                .param("to", at(to))
                .query((rs, n) ->
                        new UnterminatedRow(rs.getString("dim"), rs.getLong("turns"), rs.getInt("is_total") == 1))
                .list();
    }

    /** {@code span.cost_source} for "we hold no rate for this model" — the column, not an inference. */
    private static final String UNPRICED = "unpriced";

    /**
     * Display labels for the project's call sites, keyed by id.
     *
     * <p>{@code use_case} is the producer-chosen label; an id with none falls back to the id itself at
     * render time rather than being hidden, since a call site with traffic is worth showing whether or
     * not anyone has named it.
     */
    public java.util.Map<String, String> callSiteLabels(String projectId) {
        return jdbc
                .sql("SELECT id, use_case FROM call_site WHERE project_id = :pid AND use_case IS NOT NULL")
                .param("pid", projectId)
                .query((rs, n) -> java.util.Map.entry(rs.getString("id"), rs.getString("use_case")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, java.util.Map.Entry::getValue, (a, b) -> a));
    }

    /** The bucket for traffic that resolved no dimension value — named, not dropped. */
    public static final String UNATTRIBUTED = "__unattributed__";

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static long longOrZero(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? 0 : v;
    }

    private static BigDecimal usd(ResultSet rs) throws SQLException {
        BigDecimal v = rs.getBigDecimal("usd");
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * One dimension value's spend in the window, already summed by the database.
     *
     * <p>{@code spendingTraces} is the cost denominator, and it comes from the SPENDING population rather
     * than the root-span one. They are not the same set: an llm span tagged with a call site whose root
     * span is not lands its cost in the call-site bucket and its turn in {@code __unattributed__}, so the
     * bucket would read "spend, zero turns" and could never flag. For {@code by=model} the mismatch is
     * total — root spans carry no model.
     */
    public record UsageRow(
            String dimension,
            long tokens,
            long inputTokens,
            long outputTokens,
            BigDecimal usd,
            long calls,
            long unpricedCalls,
            long spendingTraces) {}

    /** One completed turn's wall-clock duration, read off the trace's stored {@code latency_ms}. */
    public record DurationRow(String dimension, String traceId, double millis) {}

    /**
     * Turns that never ended. {@code total} marks the grand-total row from the GROUPING SET.
     */
    public record UnterminatedRow(String dimension, long turns, boolean total) {}
}
