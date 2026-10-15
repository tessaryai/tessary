// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.detection.DetectionTableRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link #upsert} is {@code ON CONFLICT DO UPDATE} on the natural key, so re-running a closed bucket is a no-op. LLM
 * spend is read from the {@code llm_call} ledger by {@code LlmUsageQueryRepository}; do not re-source it here. The
 * ingest-reachable span-quota gate goes through a per-project cache so OTLP bursts do not make {@link #orgTotals}
 * per-request.
 */
@Repository
public class MetricRollupRepository {

    private final JdbcClient jdbc;
    private final DetectionTableRegistry detectionTables;

    public MetricRollupRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        this.detectionTables = detectionTables;
    }

    public void upsert(MetricRollupRow row) {
        jdbc.sql("""
            INSERT INTO metric_rollup (id, org_id, project_id, metric, value, bucket_start, granularity, created_at)
            VALUES (:id, :org, :pid, :unit, :value, :bstart, :bunit, :createdAt)
            ON CONFLICT (org_id, project_id, metric, bucket_start, granularity)
            DO UPDATE SET value = EXCLUDED.value
            """)
                .param("id", row.id())
                .param("org", row.orgId())
                .param("pid", row.projectId())
                .param("unit", row.metric())
                .param("value", row.value())
                .param("bstart", row.bucketStart())
                .param("bunit", row.granularity())
                .param("createdAt", row.createdAt())
                .update();
    }

    /**
     * Ingest time, deliberately: billing describes when we accepted the data, so a three-month replay is work done in
     * the hour it arrived.
     */
    public long countIngestedSpans(String projectId, String from, String to) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM span s
            WHERE s.project_id = :pid AND s.created_at >= :from::timestamptz AND s.created_at < :to::timestamptz
            """)
                .param("pid", projectId)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public long countIngestedTraces(String projectId, String from, String to) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM trace t
            WHERE t.project_id = :pid AND t.created_at >= :from::timestamptz AND t.created_at < :to::timestamptz
            """)
                .param("pid", projectId)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Live counts start empty at the detection-table cutover; historical rollups are unaffected. */
    public long countL1Evals(String projectId, String from, String to) {
        return jdbc.sql("SELECT COUNT(*) FROM (" + detectionTables.unionSql() + ") d\n"
                        + "WHERE d.project_id = :pid AND d.created_at >= :from::timestamptz"
                        + " AND d.created_at < :to::timestamptz")
                .param("pid", projectId)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public List<UsageBucket> projectTimeseries(
            String projectId, String metric, String granularity, String from, String to) {
        return jdbc.sql("""
                SELECT bucket_start, COALESCE(SUM(value), 0) AS value FROM metric_rollup
                WHERE project_id = :pid AND metric = :unit AND granularity = :bunit
                  AND bucket_start >= :from AND bucket_start < :to
                GROUP BY bucket_start
                ORDER BY bucket_start ASC
                """)
                .param("pid", projectId)
                .param("unit", metric)
                .param("bunit", granularity)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new UsageBucket(rs.getString("bucket_start"), rs.getLong("value")))
                .list();
    }

    /**
     * {@code granularity} is required: a {@code day} row re-aggregates the same rows as its 24 {@code hour} rows, so
     * spanning both doubles the total. Pass {@link UsageUnit#BUCKET_HOUR}, which also covers today.
     */
    public List<UsageTotal> orgTotals(String orgId, String granularity, @Nullable String from, @Nullable String to) {
        StringBuilder sql = new StringBuilder("SELECT metric, COALESCE(SUM(value), 0) AS value FROM metric_rollup"
                + " WHERE org_id = :org AND granularity = :bunit");
        if (from != null) sql.append(" AND bucket_start >= :from");
        if (to != null) sql.append(" AND bucket_start < :to");
        sql.append(" GROUP BY metric ORDER BY metric ASC");
        var spec = jdbc.sql(sql.toString()).param("org", orgId).param("bunit", granularity);
        if (from != null) spec = spec.param("from", from);
        if (to != null) spec = spec.param("to", to);
        return spec.query((rs, n) -> new UsageTotal(rs.getString("metric"), rs.getLong("value")))
                .list();
    }

    /**
     * Read from rollups to avoid scanning the largest table every six hours. Day grain because it re-aggregates its
     * whole day, surviving downtime across hour boundaries.
     */
    public long installLifetimeTotal(UsageUnit unit) {
        return jdbc.sql("SELECT COALESCE(SUM(value), 0)::bigint FROM metric_rollup"
                        + " WHERE metric = :metric AND granularity = :bunit")
                .param("metric", unit.wire())
                .param("bunit", UsageUnit.BUCKET_DAY)
                .query(Long.class)
                .single();
    }

    public record UsageBucket(String bucketStart, long value) {}

    public record UsageTotal(String metric, long value) {}
}
