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
 * Read-only: the slice writes no rows and enqueues no jobs, so it structurally cannot reach Layer 2. Costs are summed
 * from write-time-priced columns, and settling is {@code trace.is_settled}. Duration is {@code trace.latency_ms}, the
 * root span's own interval; an envelope over children inflated p95 by ~10%.
 */
@Repository
public class VitalsRepository {

    /** A closed enum mapped to a column, because this interpolates into SQL. */
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
     * Scoped to {@code kind = 'llm'}: a container span reports its subtree's cumulative usage. {@code unpriced} is
     * never folded into {@code usd}. Only settled traces count, since averaging partial costs makes spend look like it
     * fell. Served from {@code ix_span_llm_usage} alone; grouping keys compare under {@code "C"}, which groups exactly
     * as the deterministic database collation does but sorts cheaply.
     */
    public List<UsageRow> usageIn(String projectId, Instant from, Instant to, Dimension by) {
        String dim = "COALESCE(s." + by.column() + ", '" + UNATTRIBUTED + "') COLLATE \"C\"";
        return jdbc.sql("SELECT " + dim + " AS dim,"
                        + " sum(s.total_tokens) AS tokens,"
                        + " sum(s.total_cost) AS usd,"
                        + " count(*) FILTER (WHERE s.total_tokens IS NOT NULL) AS calls,"
                        + " count(*) FILTER (WHERE s.cost_source = '" + UNPRICED + "'"
                        + "                    AND s.total_tokens IS NOT NULL) AS unpriced_calls,"
                        + " count(DISTINCT s.trace_id COLLATE \"C\") FILTER (WHERE s.total_tokens IS NOT NULL)"
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
                        usd(rs),
                        rs.getLong("calls"),
                        rs.getLong("unpriced_calls"),
                        rs.getLong("spending_traces")))
                .list();
    }

    /**
     * For {@code model} every turn is {@code __unattributed__}, since root spans carry no model. No settle gate:
     * {@code latency_ms} is non-null only once the root span, flushed last, has ended.
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
        return jdbc.sql("SELECT " + dim + " AS dim, t.latency_ms AS ms"
                        + " FROM trace t" + rootJoin
                        + " WHERE t.project_id = :pid"
                        + "   AND NOT t.is_deleted"
                        + "   AND t.latency_ms IS NOT NULL"
                        + "   AND t.started_at >= :from AND t.started_at < :to")
                .param("pid", projectId)
                .param("from", at(from))
                .param("to", at(to))
                .query((rs, n) -> new DurationRow(rs.getString("dim"), rs.getDouble("ms")))
                .list();
    }

    /**
     * Excluding unended turns would let stuck turns read as improving latency. The total comes from the GROUPING SET
     * rather than summing buckets.
     */
    public List<UnterminatedRow> unterminatedTurnsIn(String projectId, Instant from, Instant to, Dimension by) {
        // Cast, not a bare literal: Postgres refuses a non-integer constant in GROUP BY but reads the cast as an
        // expression.
        String dim = by == Dimension.CALL_SITE
                ? "COALESCE(t.call_site_id, '" + UNATTRIBUTED + "')"
                : "CAST('" + UNATTRIBUTED + "' AS text)";
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

    private static final String UNPRICED = "unpriced";

    /** An unnamed call site falls back to its id at render time rather than being hidden. */
    public java.util.Map<String, String> callSiteLabels(String projectId) {
        return jdbc
                .sql("SELECT id, use_case FROM call_site WHERE project_id = :pid AND use_case IS NOT NULL")
                .param("pid", projectId)
                .query((rs, n) -> java.util.Map.entry(rs.getString("id"), rs.getString("use_case")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }

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
     * {@code spendingTraces} counts the spending population, not root spans: a call-site-tagged llm span under an
     * untagged root would otherwise read "spend, zero turns" and never flag.
     */
    public record UsageRow(
            String dimension, long tokens, BigDecimal usd, long calls, long unpricedCalls, long spendingTraces) {}

    public record DurationRow(String dimension, double millis) {}

    public record UnterminatedRow(String dimension, long turns, boolean total) {}
}
