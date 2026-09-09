// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The per-(project, bucket) rollup work queue, folded onto the unified {@code job} table
 * with {@code kind='usage_rollup'}. Metering has no per-row config table, so the set of buckets to roll up
 * is derived; this queue makes that derived set a durable, claimable work surface. A heartbeat (1)
 * {@link #scheduleDueBuckets} — inserts one PENDING job per live project for the closed bucket
 * (deduped by {@code ON CONFLICT DO NOTHING} on the {@code ux_job_usage_rollup} natural key) — and (2)
 * {@link #claimBatch} — takes a bounded batch with {@code FOR UPDATE SKIP LOCKED} so N backends never both
 * run the same aggregation scan.
 *
 * <p>NON-LEASED kind: the rows carry NULL lease columns and this queue keeps its OWN {@code claimed_at}-style
 * claim SQL (it is excluded from the shared {@code LeasedJobSql} machinery by design). {@code org_id}/{@code
 * bucket_start}/{@code granularity}/{@code claimed_at} live in the {@code payload} jsonb; {@code
 * dedupe_key = project:bucket:granularity} keys the all-status {@code ux_job_usage_rollup} unique so a done
 * bucket is never re-scheduled and re-scanned (the efficiency guard). The {@code metric_rollup} aggregate
 * UNIQUE remains the final double-count guard. This repo keeps its signatures and the {@link
 * MetricRollupJobRow} shape, so {@code MeteringWorker} is unchanged.
 */
@Repository
public class MetricRollupJobRepository {

    private final JdbcClient jdbc;

    public MetricRollupJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert one PENDING job per (project, {@code bucketStart}) for every live project that has no
     * job for that bucket yet. Archived projects are skipped, and so are projects accepted for deletion:
     * metering one costs a scan whose result is deleted moments later by the purge worker.
     *
     * <p>Idempotent: {@code ON CONFLICT DO NOTHING} on {@code ux_job_usage_rollup} (dedupe_key =
     * {@code project_id:bucket_start:granularity}, all statuses), so re-scheduling a bucket that is
     * already pending OR done is a no-op and N backends racing to schedule converge on one row per
     * (project, bucket). Returns rows inserted.
     */
    public int scheduleDueBuckets(String bucketStart, String granularity) {
        String now = Instant.now().toString();
        return jdbc.sql("""
            INSERT INTO job (id, project_id, kind, status, dedupe_key, payload, created_at, updated_at)
            SELECT
                'urj_' || md5(p.id || ':' || :bstart || ':' || :bunit),
                p.id, 'usage_rollup', 'pending',
                p.id || ':' || :bstart || ':' || :bunit,
                jsonb_build_object('org_id', p.org_id, 'bucket_start', :bstart, 'granularity', :bunit),
                :now, :now
            FROM project p
            WHERE p.archived_at IS NULL AND p.deleting_at IS NULL
            ON CONFLICT (dedupe_key) WHERE kind = 'usage_rollup' DO NOTHING
            """)
                .param("bstart", bucketStart)
                .param("bunit", granularity)
                .param("now", now)
                .update();
    }

    /**
     * Claim up to {@code batch} PENDING jobs (or jobs whose claim lease has expired) via {@code FOR UPDATE
     * SKIP LOCKED}, stamping {@code payload.claimed_at = now} in the same statement so a peer instance skips
     * them. A job claimed by a worker that then dies is reclaimable once {@code now - claimed_at >
     * leaseSeconds}.
     */
    public List<MetricRollupJobRow> claimBatch(int batch, long leaseSeconds) {
        String now = Instant.now().toString();
        String leaseCutoff = Instant.now().minusSeconds(leaseSeconds).toString();
        return jdbc.sql(String.format(Locale.ROOT, """
            UPDATE job SET payload = jsonb_set(payload, '{claimed_at}', to_jsonb(CAST(:now AS text))),
                           updated_at = :now
            WHERE id IN (
                SELECT id FROM job
                WHERE kind = 'usage_rollup' AND status = 'pending'
                  AND (payload->>'claimed_at' IS NULL OR payload->>'claimed_at' < :leaseCutoff)
                ORDER BY payload->>'bucket_start' ASC
                FOR UPDATE SKIP LOCKED
                LIMIT %d
            )
            RETURNING id, payload->>'org_id' AS org_id, project_id,
                      payload->>'bucket_start' AS bucket_start, payload->>'granularity' AS granularity
            """, Math.max(1, batch)))
                .param("now", now)
                .param("leaseCutoff", leaseCutoff)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Mark a claimed job done once its rollup has been upserted. */
    public void markDone(String id) {
        jdbc.sql("UPDATE job SET status = 'done', updated_at = :now WHERE kind = 'usage_rollup' AND id = :id")
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    private static MetricRollupJobRow map(ResultSet rs) throws SQLException {
        return new MetricRollupJobRow(
                rs.getString("id"),
                rs.getString("org_id"),
                rs.getString("project_id"),
                rs.getString("bucket_start"),
                rs.getString("granularity"));
    }
}
