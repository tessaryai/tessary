// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.model.JobStatus;
import ai.tessary.open.jobqueue.LeasedJobSql;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two end-user kinds on the {@code job} table (plan 3.2). The dedupe key is {@code <project>:<day>} for the daily
 * job and {@code <project>:<hour>} for the hit sweep; its unique index covers every status, so a day or an hour is
 * scheduled once and a dead job is not revived. Every time written here comes from the caller's clock in whole seconds,
 * because the lease columns compare as text.
 */
@Repository
public class EndUserJobRepository {

    public enum Kind {
        DAILY("end_users", "euj_"),
        HITS("end_user_hits", "euh_");

        private final String wire;
        private final String idPrefix;

        Kind(String wire, String idPrefix) {
            this.wire = wire;
            this.idPrefix = idPrefix;
        }
    }

    private static final String COLUMNS =
            "id, project_id, CAST(payload ->> 'day' AS integer) AS day, attempts, progress ->> 'stage' AS stage";

    private final JdbcClient jdbc;

    public EndUserJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One daily job for each active project; returns the jobs added. */
    public int scheduleDaily(int day, Instant now) {
        return jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, created_at, updated_at)
                SELECT :prefix || md5(p.id || ':' || :key), p.id, :kind, 'pending', 0, p.id || ':' || :key,
                       jsonb_build_object('day', :day), :now, :now
                  FROM project p
                 WHERE p.archived_at IS NULL AND p.deleting_at IS NULL
                ON CONFLICT (dedupe_key) WHERE kind = 'end_users' DO NOTHING
                """)
                .param("prefix", Kind.DAILY.idPrefix)
                .param("kind", Kind.DAILY.wire)
                .param("key", String.valueOf(day))
                .param("day", day)
                .param("now", now.toString())
                .update();
    }

    /**
     * One hit sweep for each active project with evidence newer than its watermark, or with any evidence before its
     * first sweep; returns the jobs added. The evidence time is text, so a date prefix narrows the index range first.
     */
    public int scheduleHits(Instant hour, int day, Instant now) {
        return jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, created_at, updated_at)
                SELECT :prefix || md5(p.id || ':' || :key), p.id, :kind, 'pending', 0, p.id || ':' || :key,
                       jsonb_build_object('day', :day, 'hour', CAST(:key AS text)), :now, :now
                  FROM project p
                  LEFT JOIN end_user_project w ON w.project_id = p.id
                 WHERE p.archived_at IS NULL AND p.deleting_at IS NULL
                   AND EXISTS (
                       SELECT 1 FROM finding_evidence e
                        WHERE e.project_id = p.id
                          AND (w.hits_watermark IS NULL
                               OR (e.created_at >= to_char(w.hits_watermark AT TIME ZONE 'UTC', 'YYYY-MM-DD')
                                   AND CAST(e.created_at AS timestamptz) > w.hits_watermark)))
                ON CONFLICT (dedupe_key) WHERE kind = 'end_user_hits' DO NOTHING
                """)
                .param("prefix", Kind.HITS.idPrefix)
                .param("kind", Kind.HITS.wire)
                .param("key", hour.toString())
                .param("day", day)
                .param("now", now.toString())
                .update();
    }

    public Optional<EndUserJob> claim(Kind kind, String owner, Instant now, long leaseSeconds, int maxAttempts) {
        return jdbc.sql(LeasedJobSql.claimBatchOfKind("job", COLUMNS, "created_at, id"))
                .param("kind", kind.wire)
                .param("owner", owner)
                .param("expires", now.plusSeconds(leaseSeconds).toString())
                .param("now", now.toString())
                .param("batch", 1)
                .param("maxAttempts", maxAttempts)
                .query((rs, n) -> new EndUserJob(
                        rs.getString("id"),
                        rs.getString("project_id"),
                        rs.getInt("day"),
                        rs.getInt("attempts"),
                        rs.getString("stage")))
                .optional();
    }

    /** Dead-letters the jobs whose worker died at the attempt cap; returns how many. */
    public int failExhausted(Kind kind, Instant now, int maxAttempts) {
        return jdbc.sql(LeasedJobSql.failExhaustedOfKind("job", "hung or crashed mid-run", JobStatus.DEAD))
                .param("kind", kind.wire)
                .param("now", now.toString())
                .param("maxAttempts", maxAttempts)
                .update();
    }

    public void saveProgress(String id, String stage, Instant now) {
        jdbc.sql("UPDATE job SET progress = jsonb_build_object('stage', CAST(:stage AS text)), updated_at = :now"
                        + " WHERE id = :id")
                .param("stage", stage)
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    public void markDone(String id, Instant now) {
        jdbc.sql("UPDATE job SET status = 'done', lease_owner = NULL, last_error = NULL, updated_at = :now"
                        + " WHERE id = :id")
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    /**
     * Releases a failed job: it stays {@code claimed} with no owner and is due again at {@code retryAt}, which the
     * claim's reclaim leg counts against the attempt cap; at the cap it is dead. Returns the resulting status.
     */
    public String markFailed(String id, String error, Instant retryAt, Instant now, int maxAttempts) {
        return jdbc.sql("""
                UPDATE job
                   SET status = CASE WHEN attempts >= :maxAttempts THEN :dead ELSE 'claimed' END,
                       lease_owner = NULL, lease_expires_at = :retryAt, last_error = :error, updated_at = :now
                 WHERE id = :id
                RETURNING status
                """)
                .param("maxAttempts", maxAttempts)
                .param("dead", JobStatus.DEAD)
                .param("retryAt", retryAt.toString())
                .param("error", error)
                .param("now", now.toString())
                .param("id", id)
                .query(String.class)
                .single();
    }
}
