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
 * The delete once ran inside the request for ~42 minutes and rolled back whole on timeout. Each batch commits on its
 * own, so a purge is resumable and never holds locks past 5,000 rows. A purge that hits {@link #MAX_BATCHES_PER_TICK}
 * logs {@code truncated=true} and resumes next tick.
 */
@Component
public class ProjectPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(ProjectPurgeWorker.class);

    /** Purging is IO-bound on one database; parallelism buys nothing. */
    private static final int CLAIM_BATCH = 1;

    /** Matched to {@code tessary.retention.batch-size}, proven against the same tables. */
    private static final int BATCH_SIZE = 5_000;

    /**
     * 1M rows, more than the largest project seen, while still bounding how long one project holds the scheduler
     * thread.
     */
    private static final int MAX_BATCHES_PER_TICK = 200;

    /** Outlasts a full tick: a shorter lease gets reclaimed mid-purge, which is idempotent but duplicated work. */
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
            // Marking and enqueuing are two statements, so a backend dying between them leaves a marked project with no
            // job.
            int revived = jobs.enqueueMissing(Instant.now().toString());
            if (revived > 0) {
                StructuredLog.info(log, Markers.OPS, "project.purge.revived")
                        .field("projects", revived)
                        .log();
            }
            // Clearing deleting_at un-hides a failed purge so someone notices. API keys stay revoked: re-enabling
            // writes is a human decision after reading job.last_error.
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
            // A heartbeat that throws stops; the next tick retries.
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

    /** False when the per-tick cap was reached with work left; the committed batches stand. */
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
                // Loop while any row was removed, not only a full batch: {@code trace}'s leaf-only batch can return a
                // few rows while a long chain still has more.
            } while (removed > 0);
        }

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
