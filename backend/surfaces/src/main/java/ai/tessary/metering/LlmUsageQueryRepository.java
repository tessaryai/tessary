// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read live from the per-call ledger, current to the last call; {@code metric_rollup} stays the billing basis but has
 * no lane, model or bucket split.
 */
@Repository
public class LlmUsageQueryRepository {

    private final JdbcClient jdbc;

    public LlmUsageQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A non-zero {@code unpricedCalls} means the real cost is higher than shown. Platform and BYO spend must not be
     * added together.
     */
    public record UsageSlice(
            @Nullable String key,
            @Nullable String label,
            long calls,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long totalTokens,
            BigDecimal costUsd,
            BigDecimal platformCostUsd,
            BigDecimal byoCostUsd,
            long unpricedCalls) {}

    /** The funding split and unpriced count are read once off the window total, not per bar. */
    public record UsageCell(
            String bucketStart,
            String key,
            @Nullable String label,
            long calls,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long totalTokens,
            BigDecimal costUsd) {}

    /** One constant so every view agrees on what "total tokens" and "cost" mean. */
    private static final String AGGREGATES = """
            COUNT(*) AS calls,
            COALESCE(SUM(c.input_tokens), 0) AS input_tokens,
            COALESCE(SUM(c.output_tokens), 0) AS output_tokens,
            COALESCE(SUM(c.cache_read_tokens), 0) AS cache_read_tokens,
            COALESCE(SUM(c.cache_write_tokens), 0) AS cache_write_tokens,
            COALESCE(SUM(c.total_tokens), 0) AS total_tokens,
            COALESCE(SUM(c.cost_usd), 0) AS cost_usd,
            COALESCE(SUM(c.cost_usd) FILTER (WHERE c.funding = 'platform'), 0) AS platform_cost_usd,
            COALESCE(SUM(c.cost_usd) FILTER (WHERE c.funding = 'byo'), 0) AS byo_cost_usd,
            COUNT(*) FILTER (WHERE c.cost_usd IS NULL) AS unpriced_calls""";

    /**
     * Lane and model compare against {@code COALESCE(col, '')} because the empty string is a real key that
     * {@link #byModel} groups under.
     */
    private static final String ORG_WINDOW = """
            FROM llm_call c
            JOIN project p ON p.id = c.project_id
            WHERE p.org_id = :orgId
              AND (:from::timestamptz IS NULL OR c.created_at >= :from::timestamptz)
              AND (:to::timestamptz IS NULL OR c.created_at < :to::timestamptz)
              AND (:lane::text IS NULL OR COALESCE(c.lane, '') = :lane::text)
              AND (:project::text IS NULL OR c.project_id = :project::text)
              AND (:model::text IS NULL OR COALESCE(c.model, '') = :model::text)""";

    public UsageSlice orgTotal(String orgId, @Nullable String from, @Nullable String to, LlmUsageFilter filter) {
        List<UsageSlice> one = query(
                "SELECT NULL::text AS key, NULL::text AS label, " + AGGREGATES + "\n" + ORG_WINDOW,
                orgId,
                from,
                to,
                filter);
        return one.get(0);
    }

    /**
     * Unfiltered by design: this lists the filter's choices, so narrowing it would delete every other option. Same for
     * {@link #byProject} and {@link #byModel}.
     */
    public List<UsageSlice> byLane(String orgId, @Nullable String from, @Nullable String to) {
        return query(
                "SELECT c.lane AS key, NULL::text AS label, " + AGGREGATES + "\n" + ORG_WINDOW
                        + "\nGROUP BY c.lane\nORDER BY total_tokens DESC, calls DESC",
                orgId,
                from,
                to,
                LlmUsageFilter.NONE);
    }

    public List<UsageSlice> byProject(String orgId, @Nullable String from, @Nullable String to) {
        return query(
                "SELECT c.project_id AS key, p.name AS label, " + AGGREGATES + "\n" + ORG_WINDOW
                        + "\nGROUP BY c.project_id, p.name\nORDER BY total_tokens DESC, calls DESC",
                orgId,
                from,
                to,
                LlmUsageFilter.NONE);
    }

    public List<UsageSlice> byModel(String orgId, @Nullable String from, @Nullable String to) {
        return query(
                "SELECT COALESCE(c.model, '') AS key, NULL::text AS label, " + AGGREGATES + "\n" + ORG_WINDOW
                        + "\nGROUP BY COALESCE(c.model, '')\nORDER BY total_tokens DESC, calls DESC",
                orgId,
                from,
                to,
                LlmUsageFilter.NONE);
    }

    /** @param runs more than one means the finding was re-triaged */
    public record SubjectSpend(
            String subjectId, long runs, long totalTokens, BigDecimal costUsd, long unpricedRuns, String lastAt) {}

    public List<SubjectSpend> bySubject(
            String orgId, @Nullable String from, @Nullable String to, String subjectKind, int limit) {
        return jdbc.sql("SELECT c.subject_id AS subject_id,\n"
                        + "       COUNT(*) AS runs,\n"
                        + "       COALESCE(SUM(c.total_tokens), 0) AS total_tokens,\n"
                        + "       COALESCE(SUM(c.cost_usd), 0) AS cost_usd,\n"
                        + "       COUNT(*) FILTER (WHERE c.cost_usd IS NULL) AS unpriced_runs,\n"
                        + "       to_char(MAX(c.created_at) AT TIME ZONE 'UTC',"
                        + " 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS last_at\n"
                        + ORG_WINDOW
                        + "\n  AND c.subject_kind = :subjectKind AND c.subject_id IS NOT NULL"
                        + "\nGROUP BY c.subject_id\nORDER BY cost_usd DESC, runs DESC\nLIMIT :limit")
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("lane", (String) null)
                .param("project", (String) null)
                .param("model", (String) null)
                .param("subjectKind", subjectKind)
                .param("limit", limit)
                .query((rs, n) -> new SubjectSpend(
                        rs.getString("subject_id"),
                        rs.getLong("runs"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getLong("unpriced_runs"),
                        rs.getString("last_at")))
                .list();
    }

    private static final String CELL_AGGREGATES = """
            COUNT(*) AS calls,
            COALESCE(SUM(c.input_tokens), 0) AS input_tokens,
            COALESCE(SUM(c.output_tokens), 0) AS output_tokens,
            COALESCE(SUM(c.cache_read_tokens), 0) AS cache_read_tokens,
            COALESCE(SUM(c.cache_write_tokens), 0) AS cache_write_tokens,
            COALESCE(SUM(c.total_tokens), 0) AS total_tokens,
            COALESCE(SUM(c.cost_usd), 0) AS cost_usd""";

    /**
     * Cut in UTC so every connection returns the same bars, rendered with an explicit {@code Z} so {@link #seriesAxis}
     * matches the cells character for character.
     */
    private static final String BUCKET_START =
            "to_char(date_trunc(:grain::text, c.created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";

    /** Only buckets with calls; {@link #seriesAxis} supplies the full axis. */
    public List<UsageCell> series(
            String orgId,
            String from,
            String to,
            LlmUsageGrain grain,
            LlmUsageGrouping grouping,
            LlmUsageFilter filter) {
        String sql = "SELECT " + BUCKET_START + " AS bucket_start,\n" + keyExpr(grouping) + " AS key,\n"
                + labelExpr(grouping) + " AS label,\n" + CELL_AGGREGATES + "\n" + ORG_WINDOW
                + "\nGROUP BY 1, 2, 3\nORDER BY 1 ASC, total_tokens DESC";
        return jdbc.sql(sql)
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("grain", grain.wire())
                .param("lane", filter.lane())
                .param("project", filter.projectId())
                .param("model", filter.model())
                .query((rs, n) -> new UsageCell(
                        rs.getString("bucket_start"),
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cache_read_tokens"),
                        rs.getLong("cache_write_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd")))
                .list();
    }

    /** Built off the same {@code date_trunc} as the cells, so a week never starts on a different day. */
    public List<String> seriesAxis(String from, String to, LlmUsageGrain grain) {
        return jdbc.sql("""
                        SELECT to_char(g, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS bucket_start
                        FROM generate_series(
                                date_trunc(:grain::text, (:from::timestamptz) AT TIME ZONE 'UTC'),
                                ((:to::timestamptz) AT TIME ZONE 'UTC') - interval '1 microsecond',
                                (:step)::interval) AS g
                        """)
                .param("grain", grain.wire())
                .param("from", from)
                .param("to", to)
                .param("step", grain.step())
                .query((rs, n) -> rs.getString("bucket_start"))
                .list();
    }

    /** Never null on the wire; the empty string carries "none of these". */
    private static String keyExpr(LlmUsageGrouping grouping) {
        return switch (grouping) {
            case NONE -> "''::text";
            case LANE -> "COALESCE(c.lane, '')";
            case PROJECT -> "c.project_id";
            case MODEL -> "COALESCE(c.model, '')";
        };
    }

    private static String labelExpr(LlmUsageGrouping grouping) {
        return grouping == LlmUsageGrouping.PROJECT ? "p.name" : "NULL::text";
    }

    private List<UsageSlice> query(
            String sql, String orgId, @Nullable String from, @Nullable String to, LlmUsageFilter filter) {
        return jdbc.sql(sql)
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("lane", filter.lane())
                .param("project", filter.projectId())
                .param("model", filter.model())
                .query((rs, n) -> new UsageSlice(
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cache_read_tokens"),
                        rs.getLong("cache_write_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getBigDecimal("platform_cost_usd"),
                        rs.getBigDecimal("byo_cost_usd"),
                        rs.getLong("unpriced_calls")))
                .list();
    }
}
