// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.usage;

import ai.tessary.evals.detection.DetectionTableRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The single owner of usage-rollup SQL: the idempotent rollup upsert, the bounded aggregation
 * queries that count a closed bucket from already-committed substrate rows, and both read surfaces
 * (project-scoped timeseries for {@code MeteringController}, org-scoped totals for {@code BillingController}).
 * Per the project's ArchUnit rule, raw {@code JdbcClient} lives only in a {@code *Repository}; both the
 * project-scoped and org-scoped reads delegate here so the aggregation SQL never forks.
 *
 * <p><b>Idempotency.</b> {@link #upsert} writes {@code ON CONFLICT … DO UPDATE} against the natural key
 * {@code (org_id, project_id, metric, bucket_start, granularity)}, so a re-run of a CLOSED bucket
 * overwrites with the same stable count — a no-op. The aggregation queries scan a
 * {@code [bucketStart, bucketEnd)} window on {@code created_at}, served by the
 * {@code (project_id, created_at)} substrate index.
 *
 * <p><b>Two whole aggregation families left with Track A, and the reason matters.</b> This file used
 * to carry {@code countL2Evals}, {@code sumLlmTokens} and {@code sumLlmCostMicros}, all three scanning
 * {@code FROM verdict} — a table that no longer exists. Their units ({@code l2_evals},
 * {@code llm_tokens}, both {@code llm_cost_micro_usd_*}) are retired rather than re-sourced. LLM spend
 * did NOT go dark with them: {@code metric_rollup} and the {@code llm_call} ledger are two separate
 * usage systems, and the rich per-lane/per-model/per-subject spend view is
 * {@code metering/LlmUsageQueryRepository}, which reads {@code llm_call} directly and is untouched.
 * What was genuinely lost is the pre-aggregated basis the monthly token/L2 quota CAPS were enforced
 * against — a deliberate, accepted loss (no monthly LLM-token cap is enforced at launch).
 * Do not re-source these onto {@code llm_call}: the ledger is written per call and read live, and
 * turning it into a metering scan is the mistake that split existed to prevent.
 *
 * <p><b>The per-environment grain went too.</b> Every query here used to {@code GROUP BY} the
 * producer's {@code environment_id} and return one amount per environment, and both reads carried an
 * {@code EnvironmentFilter}. The Environment concept was removed, so each query returns one number for
 * the project and the reads have no env predicate. Historical rows written under the old key survive:
 * the {@code 0016} changeset SUM-merges the per-env rows into a single row per
 * {@code (org, project, metric, bucket_start, granularity)} before dropping the column, so an org's
 * totals over any window are unchanged.
 *
 * <p><b>Off the hot path.</b> Every method here is called from the scheduled {@code MeteringWorker} or a
 * read endpoint. The one caller reachable from ingest is the span-quota gate, and it goes through a
 * short-lived per-project cache ({@code plan/CapabilityIngestQuotaGate}) precisely so a burst of OTLP exports
 * does not turn {@link #orgTotals} into a per-request aggregate.
 */
@Repository
public class MetricRollupRepository {

    private final JdbcClient jdbc;
    private final DetectionTableRegistry detectionTables;

    public MetricRollupRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        this.detectionTables = detectionTables;
    }

    // ---- write -------------------------------------------------------------------------------------

    /**
     * Idempotent upsert of one (scope, unit, bucket) aggregate. Re-running a closed bucket overwrites
     * the same value — a no-op.
     */
    public void upsert(MetricRollupRow row) {
        jdbc.sql("""
            INSERT INTO metric_rollup (id, org_id, project_id, metric, value,
                                      bucket_start, granularity, dimensions, attributes, created_at)
            VALUES (:id, :org, :pid, :unit, :value, :bstart, :bunit, :dimensions::jsonb,
                    :attributes::jsonb, :createdAt)
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
                .param("dimensions", row.dimensions())
                .param("attributes", row.attributes())
                .param("createdAt", row.createdAt())
                .update();
    }

    // ---- bounded aggregation over committed rows (the closed-bucket counts) -------------------------

    /**
     * {@code COUNT(*)} of spans ingested for a project in {@code [from, to)}.
     *
     * <p><b>The window is on INGEST time ({@code span.created_at}), deliberately, and it is the one place
     * in this migration that stays on it.</b> Every analytical read moved to event time because a
     * backfill's event timestamps describe when the agent ran; billing describes when we accepted and
     * stored the data, and a replay of three months of history is work done in the hour it arrived.
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

    /**
     * {@code COUNT(*)} of Layer-1 units for a project in {@code [from, to)} — one per classifier
     * detection.
     *
     * <p>Read from DetectionTableRegistry's runtime-stitched union over the per-classifier detection
     * tables. Detections stopped being verdicts at the cutover, and the automatic verdicts they used to
     * be were deleted rather than copied — so LIVE counts start from empty here. The historical buckets
     * already written into {@code metric_rollup} are pre-aggregated and are not affected: this query only
     * ever fills the bucket currently being metered.
     */
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

    /**
     * {@code COUNT(*)} of span rows AT REST for a project as of {@code asOf} (the bucket's
     * exclusive end) — the {@code storage} usage LEVEL. Unlike the event-stream counts above this is NOT
     * a {@code [from, to)} window: storage is a level, so it snapshots everything ingested up to (and not
     * including) the bucket boundary, making the snapshot stable AS-OF a CLOSED bucket — re-running the
     * bucket recomputes the same count, and the worker's last-writer-wins upsert overwrites it
     * idempotently. Like the window counts, {@code asOf} bounds the INGEST clock.
     *
     * <p>The billable basis is ingested span rows; bytes-at-rest / retention-weighting are deferred
     * billing product decisions. The worker only calls this when {@code evals.metering.storage-enabled}
     * is set.
     */
    public long snapshotStorageRows(String projectId, String asOf) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM span s
            WHERE s.project_id = :pid AND s.created_at < :asOf::timestamptz
            """)
                .param("pid", projectId)
                .param("asOf", asOf)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    // ---- read: project-scoped timeseries (MeteringController) ---------------------------------------

    /**
     * The ascending per-bucket values for one project + unit over {@code [from, to)}. Index-served by
     * {@code ix_metric_rollup_project_unit_bucket}. Range bounds compare on the raw TEXT {@code bucket_start}.
     */
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

    // ---- read: org-scoped totals (BillingController) ------------------------------------------

    /**
     * The total billable value per unit for an org over {@code [from, to)} — the cross-project read billing
     * bills from, and the read plan quotas are enforced against. {@code from}/{@code to} are inclusive-start,
     * exclusive-end ISO bounds on {@code bucket_start}; a null bound means unbounded on that side. Returns one
     * {@link UsageTotal} per unit that has any rollup in range. Index-served by {@code ix_metric_rollup_org_bucket}.
     *
     * <p><b>{@code granularity} is required, and the reason is a double count.</b> The worker writes a {@code
     * day} row covering the SAME producer rows as the 24 {@code hour} rows beside it (it re-aggregates the
     * window, it does not sum the hours), so a total that spans both key spaces returns roughly twice the
     * usage. Pass {@link UsageUnit#BUCKET_HOUR}: it is the finest grain, it is always present, and unlike the
     * day grain it covers the day currently in progress.
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

    /** One timeseries bucket of usage. */
    public record UsageBucket(String bucketStart, long value) {}

    /** One unit's total over a period (org-scoped billing read). */
    public record UsageTotal(String metric, long value) {}
}
