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
 * The per-(project, bucket) rollup queue on {@code job} with {@code kind='usage_rollup'}. Non-leased: it keeps its own
 * {@code claimed_at} claim SQL outside {@code LeasedJobSql}. {@code dedupe_key} keys the all-status
 * {@code ux_job_usage_rollup} unique, so a done bucket is never re-scanned; the {@code metric_rollup} UNIQUE is the
 * final double-count guard.
 */
@Repository
public class MetricRollupJobRepository {

    private final JdbcClient jdbc;

    public MetricRollupJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Skips archived and deleting projects. Idempotent across statuses, so racing backends converge on one row per
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
     * Stamps {@code payload.claimed_at} in the same statement; a dead worker's job is reclaimable after
     * {@code leaseSeconds}.
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
