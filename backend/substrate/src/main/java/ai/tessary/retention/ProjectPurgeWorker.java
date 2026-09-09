// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.ProjectDeleteJobRepository;
import ai.tessary.tenant.ProjectDeleteJobRow;
import ai.tessary.tenant.ProjectRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the {@code project_delete} queue: empty a doomed project's volume tables in bounded batches,
 * then drop the project row and let the cascade take the rest.
 *
 * <p>This exists because the delete used to be one statement inside the HTTP request. On the Paperclip
 * corpus that statement ran ~42 minutes — four unindexed {@code ON DELETE SET NULL} references into
 * {@code media_object} turned each of 8,210 media rows into two sequential scans of a 700 MB
 * {@code tool_call} — while holding a pooled connection and blocking the job queue's own INSERT. The
 * request timed out, the single-statement cascade rolled back whole, and the user's retry started again
 * from nothing. An earlier migration already fixed that specific cost by
 * removing the four dangling columns in favor of an indexed join table; this class fixes the shape —
 * a delete no longer runs inside the request at all.
 *
 * <p><b>Nothing here runs in one transaction.</b> Each batch commits on its own, which is what makes a
 * purge resumable: a worker that dies, a lease that expires, a backend that is redeployed mid-delete all
 * cost the current batch and nothing more. It is also what keeps ingest and the other workers unblocked,
 * since no statement holds row locks for longer than 5,000 rows.
 *
 * <p><b>Every exit is bounded and says which one it took.</b> A purge that hits
 * {@link #MAX_BATCHES_PER_TICK} logs {@code truncated=true} and hands the job back for the next tick
 * rather than running the scheduler thread indefinitely; a purge that throws is retried up to
 * {@link #MAX_ATTEMPTS} times and then parked with the reason in {@code job.last_error}. "Deleted 100,000
 * rows" and "deleted 100,000 rows and stopped early with more to go" are different operational facts.
 */
@Component
public class ProjectPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(ProjectPurgeWorker.class);

    /** One project at a time per backend. Purging is IO-bound on one database; parallelism buys nothing. */
    private static final int CLAIM_BATCH = 1;

    /**
     * Rows per statement. Matched to {@code tessary.retention.batch-size} — the same trade-off against the
     * same tables, and a number already proven against this corpus.
     */
    private static final int BATCH_SIZE = 5_000;

    /**
     * Statements per tick. At {@link #BATCH_SIZE} this is 1M rows, comfortably more than the largest
     * project seen (~1.1M rows across all volume tables) so a normal purge finishes in one tick, while
     * still bounding how long one project can hold the scheduler thread.
     */
    private static final int MAX_BATCHES_PER_TICK = 200;

    /**
     * How long a claim is held before the job is considered hung. Sized to outlast a full tick: a lease
     * shorter than the work it covers gets reclaimed mid-purge by a second backend. That reclaim is
     * harmless — every statement is {@code WHERE project_id = …} and therefore idempotent — but it is
     * duplicated work, and a queue that keeps redoing itself never converges.
     */
    private static final long LEASE_SECONDS = 1_800;

    private static final int MAX_ATTEMPTS = 5;

    private final ProjectDeleteJobRepository jobs;
    private final ProjectPurgeRepository purge;
    private final ProjectRepository projects;
    private final String leaseOwner =
            "project-purge-" + UUID.randomUUID().toString().substring(0, 8);

    public ProjectPurgeWorker(
            ProjectDeleteJobRepository jobs, ProjectPurgeRepository purge, ProjectRepository projects) {
        this.jobs = jobs;
        this.purge = purge;
        this.projects = projects;
    }

    @Scheduled(fixedDelayString = "${tessary.project-delete.heartbeat-ms:15000}", initialDelayString = "30000")
    public void tick() {
        try {
            // Recovery first: marking and enqueuing are two statements in the endpoint, so a backend that
            // died between them left a project marked with no job. The marker is the durable record.
            int revived = jobs.enqueueMissing(Instant.now().toString());
            if (revived > 0) {
                StructuredLog.info(log, Markers.OPS, "project.purge.revived")
                        .field("projects", revived)
                        .log();
            }
            // A purge that exhausted its retry budget is dead-lettered here, but a dead-lettered job row
            // is invisible to the user — they're watching the project, not the queue. Clearing deleting_at
            // un-hides the project (it reappears in listings and every route accepts it again) so someone
            // notices instead of the UI polling "deleting" forever. API keys stay revoked: a purge that
            // merely failed to finish isn't proof the delete was a mistake, so re-enabling write access is
            // a human decision made after reading job.last_error, not this worker's to make automatically.
            for (String projectId : jobs.failExhausted(MAX_ATTEMPTS)) {
                projects.clearDeleting(projectId);
                StructuredLog.warn(log, Markers.OPS, "project.purge.exhausted")
                        .field("project", projectId)
                        .log();
            }

            for (ProjectDeleteJobRow job : jobs.claimBatch(leaseOwner, CLAIM_BATCH, LEASE_SECONDS, MAX_ATTEMPTS)) {
                runOne(job);
            }
        } catch (RuntimeException e) {
            // A heartbeat that throws is a heartbeat that stops. Claim/recovery failures are the
            // database being unavailable, which the next tick retries anyway.
            log.warn("project purge tick failed", e);
        }
    }

    private void runOne(ProjectDeleteJobRow job) {
        Instant started = Instant.now();
        try {
            boolean complete = purgeProject(job.projectId(), started);
            if (complete) {
                jobs.markDone(job.id());
            } else {
                jobs.releaseForContinuation(job.id());
            }
        } catch (RuntimeException e) {
            log.warn("project purge failed projectId={}", job.projectId(), e);
            jobs.markRetryable(job.id(), e.toString(), job.attempts(), MAX_ATTEMPTS);
        }
    }

    /**
     * Empty one project and drop it. Returns false when the per-tick batch cap was reached with work
     * still to do, in which case the committed batches stand and the next tick resumes.
     */
    private boolean purgeProject(String projectId, Instant started) {
        int batches = 0;
        long deleted = 0;
        for (String table : ProjectPurgeRepository.TABLES) {
            int removed;
            do {
                if (batches >= MAX_BATCHES_PER_TICK) {
                    StructuredLog.info(log, Markers.OPS, "project.purge.progress")
                            .field("project", projectId)
                            .field("deleted", deleted)
                            .field("stopped_in", table)
                            .field("truncated", true)
                            .durationMs(started)
                            .log();
                    return false;
                }
                removed = purge.deleteBatch(table, projectId, BATCH_SIZE);
                batches++;
                deleted += removed;
                // Loop while a batch found ANY row, not only a full one. Every other table's plain
                // ctid-LIMIT batch can only return fewer than BATCH_SIZE when it is exhausted, so this was
                // equivalent to `removed == BATCH_SIZE` for them — but `trace`'s leaf-only batch
                // (ProjectPurgeRepository#deleteTraceLeafBatch) can legitimately return a small handful of
                // newly-exposed leaves on one call while a long parent/child chain still has more to give
                // on the next; treating that as "table exhausted" would abandon the rest of the chain.
            } while (removed > 0);
        }

        // Everything with volume is gone; what this cascades into is the ~40 small per-project tables.
        boolean dropped = projects.deleteById(projectId);
        StructuredLog.info(log, Markers.OPS, "project.purge.done")
                .field("project", projectId)
                .field("deleted", deleted)
                .field("batches", batches)
                .field("row_dropped", dropped)
                .durationMs(started)
                .log();
        return true;
    }
}
