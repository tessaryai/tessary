// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.open.jobqueue.LeasedJobSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * These rows carry a NULL {@code project_id}: that column cascades from {@code project}, so a delete job naming its
 * project would be cascaded away by its own final statement. The id lives in the payload and {@code dedupe_key}.
 * {@code ux_job_project_delete} covers only unfinished statuses, so a dead-lettered purge can be re-enqueued.
 */
@Repository
public class ProjectDeleteJobRepository {

    private static final String COLS = "id, payload->>'project_id' AS project_id, attempts";

    private final JdbcClient jdbc;

    public ProjectDeleteJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A second enqueue while one is still live is a no-op. */
    public int enqueue(String projectId, String now) {
        return jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, created_at, updated_at)
                VALUES (:id, NULL, 'project_delete', 'pending', 0, :pid,
                        jsonb_build_object('project_id', :pid::text), :now, :now)
                ON CONFLICT (dedupe_key) WHERE kind = 'project_delete' AND status IN ('pending', 'claimed')
                DO NOTHING
                """)
                .param("id", Ids.ulid())
                .param("pid", projectId)
                .param("now", now)
                .update();
    }

    /**
     * Closes the window where a backend dies between marking and enqueuing, and revives dead-lettered purges. One row
     * at a time with a fresh id: a derived id collided with its own old {@code job_pkey} after repeated dead-letters,
     * and in a bulk insert that blocked recovery for every stuck project.
     */
    public int enqueueMissing(String now) {
        List<String> orphaned = jdbc.sql("""
                SELECT p.id
                  FROM project p
                 WHERE p.deleting_at IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM job j
                        WHERE j.kind = 'project_delete' AND j.dedupe_key = p.id AND j.status IN ('pending', 'claimed')
                   )
                """).query(String.class).list();
        int revived = 0;
        for (String projectId : orphaned) {
            revived += enqueue(projectId, now);
        }
        return revived;
    }

    public List<ProjectDeleteJobRow> claimBatch(String leaseOwner, int batch, long leaseSeconds, int maxAttempts) {
        Instant now = Instant.now();
        return jdbc.sql(LeasedJobSql.claimBatchOfKind("job", COLS, "created_at"))
                .param("kind", JobRow.Kind.PROJECT_DELETE)
                .param("owner", leaseOwner)
                .param("expires", now.plus(Duration.ofSeconds(leaseSeconds)).toString())
                .param("now", now.toString())
                .param("batch", batch)
                .param("maxAttempts", maxAttempts)
                .query(ProjectDeleteJobRepository::map)
                .list();
    }

    /**
     * Returns project ids so the caller can surface the failure. Bespoke because
     * {@link LeasedJobSql#failExhaustedOfKind} has no RETURNING.
     */
    public List<String> failExhausted(int maxAttempts) {
        return jdbc.sql(String.format(Locale.ROOT, """
                        UPDATE job
                           SET status = 'failed',
                               last_error = %s,
                               updated_at = :now
                         WHERE kind = 'project_delete' AND status = 'claimed'
                           AND lease_expires_at < :now AND attempts >= :maxAttempts
                        RETURNING payload->>'project_id' AS project_id
                        """, LeasedJobSql.exhaustedLastError("hung or crashed mid-purge")))
                .param("now", Instant.now().toString())
                .param("maxAttempts", maxAttempts)
                .query(String.class)
                .list();
    }

    public void markDone(String id) {
        jdbc.sql("UPDATE job SET status = 'done', last_error = NULL, updated_at = :now WHERE id = :id")
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /** A purge is resumable (every batch commits), so a retry costs only the remaining batches. */
    public void markRetryable(String id, String error, int attempts, int maxAttempts) {
        String status = attempts >= maxAttempts ? "failed" : "pending";
        jdbc.sql("""
                UPDATE job SET status = :status, last_error = :err, lease_owner = NULL,
                               lease_expires_at = NULL, updated_at = :now
                 WHERE id = :id
                """)
                .param("status", status)
                .param("err", error.length() > 2000 ? error.substring(0, 2000) : error)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /**
     * Not a failure: the per-tick cap stopped it with durable progress. Attempts reset, or a large project would
     * dead-letter itself for being big.
     */
    public void releaseForContinuation(String id) {
        jdbc.sql("""
                UPDATE job SET status = 'pending', attempts = 0, lease_owner = NULL,
                               lease_expires_at = NULL, updated_at = :now
                 WHERE id = :id
                """).param("now", Instant.now().toString()).param("id", id).update();
    }

    private static ProjectDeleteJobRow map(ResultSet rs, int n) throws SQLException {
        return new ProjectDeleteJobRow(rs.getString("id"), rs.getString("project_id"), rs.getInt("attempts"));
    }
}
