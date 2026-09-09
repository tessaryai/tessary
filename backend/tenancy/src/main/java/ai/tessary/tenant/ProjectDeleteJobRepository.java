// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.open.jobqueue.LeasedJobSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The project-purge work queue on the unified {@code job} table: one {@code kind='project_delete'} row
 * per project the delete endpoint has accepted, {@code dedupe_key} = the project id. Claim / reclaim /
 * dead-letter is the shared kind-scoped {@link LeasedJobSql}, the same machinery {@code sop_compile} and
 * the classifier queues run on.
 *
 * <h2>Why these rows carry a NULL project_id</h2>
 *
 * <p>{@code job.project_id} is {@code ON DELETE CASCADE} to {@code project}. Every other kind wants that:
 * a project's queued work should vanish with the project. This kind is the exception, because its whole
 * job is to make the project vanish — a delete job that named its own project in that column would be
 * cascaded away by its own final statement, and the worker would come back from the purge to find no row
 * left to mark done. The id lives in {@code payload.project_id} and in {@code dedupe_key}, neither of
 * which any foreign key touches.
 *
 * <h2>Why the dedupe unique is scoped to unfinished statuses</h2>
 *
 * <p>{@code ux_job_project_delete} covers {@code pending} and {@code claimed} only. Double-clicking Delete
 * is therefore a no-op, while a purge that dead-letters can still be re-enqueued by
 * {@link #enqueueMissing} — an all-status unique like {@code ux_job_sop_compile} would let one failed
 * attempt block every future one for a project that is already marked and can never be un-marked.
 */
@Repository
public class ProjectDeleteJobRepository {

    private static final String COLS = "id, payload->>'project_id' AS project_id, status, attempts";

    private final JdbcClient jdbc;

    public ProjectDeleteJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Enqueue the purge of one project. A second enqueue while one is still live is a no-op. */
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
     * Re-enqueue every project marked {@code deleting_at} that has no live job — the recovery read.
     * Returns how many were revived.
     *
     * <p>Marking and enqueuing are two statements, so a backend that dies between them leaves a project
     * nobody is purging and a user watching a row that will never disappear. This closes that window, and
     * it is also how a dead-lettered purge gets picked back up. Idempotent by the same partial unique the
     * endpoint's enqueue relies on, so N backends running this heartbeat converge on one job per project.
     *
     * <p>Each revival goes through {@link #enqueue}, one row at a time, rather than a single bulk
     * {@code INSERT ... SELECT} — deliberately, because {@code job.id} is the primary key and a bulk
     * statement needs one id expression shared by every row it inserts. A stable id derived from the
     * project id (the previous approach) reused the SAME id across every dead-letter cycle for that
     * project: a purge that dead-letters once and is revived, then dead-letters again, hits its own old
     * {@code job_pkey} on the next revival — and because the bulk statement covers every orphaned project
     * in one INSERT, that single unique-violation aborts the whole statement and silently blocks recovery
     * for every other stuck project too. {@code dedupe_key}, not {@code id}, is the real dedupe mechanism
     * (the partial unique index above), so the id only ever needs to be fresh — {@link Ids#ulid()}, the
     * same generator {@link #enqueue} already uses.
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

    /** Claim up to {@code batch} due jobs under a fresh lease ({@code FOR UPDATE SKIP LOCKED}). */
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
     * Dead-letter jobs whose lease expired at/over the attempt cap. Returns the ids of the projects those
     * jobs were purging, so the caller can surface the failure rather than leaving the project stuck
     * showing "deleting" forever — see {@link ai.tessary.retention.ProjectPurgeWorker#tick}.
     *
     * <p>Written as a bespoke query rather than {@link LeasedJobSql#failExhaustedOfKind} because that
     * shared helper has no {@code RETURNING} — every other queue it serves only needs the row count.
     */
    public List<String> failExhausted(int maxAttempts) {
        return jdbc.sql("""
                        UPDATE job
                           SET status = 'failed',
                               last_error = 'exhausted: ' || attempts || ' attempts (hung or crashed mid-purge)',
                               updated_at = :now
                         WHERE kind = 'project_delete' AND status = 'claimed'
                           AND lease_expires_at < :now AND attempts >= :maxAttempts
                        RETURNING payload->>'project_id' AS project_id
                        """)
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

    /**
     * Return one job to {@code pending} with its error recorded, or park it {@code failed} once
     * {@code attempts} has reached the cap.
     *
     * <p>A purge is resumable by construction — every batch commits, and every statement is scoped to the
     * project id — so retrying one that failed halfway costs only the batches it has not done yet.
     */
    public void markRetryable(String id, String error, int attempts, int maxAttempts) {
        String status = attempts >= maxAttempts ? "failed" : "pending";
        jdbc.sql("""
                UPDATE job SET status = :status, last_error = :err, lease_owner = NULL,
                               lease_expires_at = NULL, updated_at = :now
                 WHERE id = :id
                """)
                .param("status", status)
                .param("err", error != null && error.length() > 2000 ? error.substring(0, 2000) : error)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /**
     * Hand a still-unfinished purge back to the queue so the next tick continues it.
     *
     * <p>Distinct from {@link #markRetryable} because this is not a failure: the worker stopped on its
     * per-tick batch cap, having committed real progress, and the batches it did are durable. Attempts
     * resets to zero for exactly that reason — a project large enough to need more ticks than the attempt
     * cap would otherwise dead-letter itself for being big, which is the one thing this whole change
     * exists to stop happening.
     */
    public void releaseForContinuation(String id) {
        jdbc.sql("""
                UPDATE job SET status = 'pending', attempts = 0, lease_owner = NULL,
                               lease_expires_at = NULL, updated_at = :now
                 WHERE id = :id
                """).param("now", Instant.now().toString()).param("id", id).update();
    }

    private static ProjectDeleteJobRow map(ResultSet rs, int n) throws SQLException {
        return new ProjectDeleteJobRow(
                rs.getString("id"), rs.getString("project_id"), rs.getString("status"), rs.getInt("attempts"));
    }
}
