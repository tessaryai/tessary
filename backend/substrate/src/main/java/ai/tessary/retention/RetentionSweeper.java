// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import ai.tessary.config.RetentionProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.retention.RetentionResolver.DataClass;
import ai.tessary.retention.RetentionResolver.EffectiveRetention;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enforces retention: the hourly pass that actually deletes what a {@code retention_policy} says should be
 * gone. Until this existed the table was rows nobody read — a policy a customer could be shown and that
 * the system did not keep.
 *
 * <p><b>Bounded, and it says what it did.</b> Each (project, data class) gets at most
 * {@code max-batches-per-sweep} statements of {@code batch-size} rows, so the first pass after a long
 * unenforced period drains gradually rather than locking a table for minutes. When a class hits its cap the
 * sweep logs {@code truncated=true} for it, because "deleted 100,000 rows" and "deleted 100,000 rows and
 * stopped early with more to go" are different operational facts and only one of them means the backlog is
 * shrinking.
 *
 * <p><b>Deletion is by EVENT time, not row-insert time.</b> A backfilled trace carries the timestamp of
 * when it happened, and a partner who replays three months of history expects it to age on that clock.
 * Using {@code created_at} alone would keep replayed history for the full TTL from the day it was uploaded,
 * which is the opposite of what a retention commitment says. In v2 that is not a choice the query makes
 * any more: {@code trace} carries only {@code started_at}.
 *
 * <p><b>Payloads age ahead of structure.</b> The traces class deletes {@code span_payload} rows before
 * {@code trace} rows (spec §10), so the bytes go first and a trace whose text has expired keeps working
 * on every list, rollup and detector surface. Per-tier durations are a configuration decision that has
 * not been taken yet, so both tiers currently run on the traces policy's TTL.
 *
 * <p><b>Media is collected, not aged.</b> {@link #collectMedia} runs for every project on every pass,
 * outside the policy classes entirely, because an image no payload references any more is unreachable
 * rather than merely old.
 */
@Component
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    private final ProjectRepository projects;
    private final RetentionResolver resolver;
    private final RetentionRepository repo;
    private final RetentionProperties props;

    public RetentionSweeper(
            ProjectRepository projects,
            RetentionResolver resolver,
            RetentionRepository repo,
            RetentionProperties props) {
        this.projects = projects;
        this.resolver = resolver;
        this.repo = repo;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${tessary.retention.interval-ms:3600000}", initialDelayString = "60000")
    public void sweep() {
        if (!props.isEnabled()) return;
        Instant started = Instant.now();
        long totalDeleted = 0;
        int projectsSwept = 0;
        for (Project project : projects.findActive()) {
            totalDeleted += sweepProject(project.id());
            projectsSwept++;
        }
        StructuredLog.info(log, Markers.OPS, "retention.sweep")
                .field("projects", projectsSwept)
                .field("deleted", totalDeleted)
                .durationMs(started)
                .log();
    }

    private long sweepProject(String projectId) {
        long deleted = 0;
        boolean sweptTraces = false;
        for (EffectiveRetention retention : resolver.resolve(projectId)) {
            if (!retention.bounded()) continue;
            String cutoff =
                    Instant.now().minus(retention.ttlDays(), ChronoUnit.DAYS).toString();
            deleted += sweepClass(projectId, retention, cutoff);
            sweptTraces |= retention.dataClass() == DataClass.TRACES;
        }
        deleted += collectMedia(projectId);
        // Reported every pass, deletions or not — a pin that never releases looks exactly like a healthy
        // policy from the deletion counts alone: they simply go to zero and the project's disk stops
        // falling. This is the number that tells the two apart.
        if (sweptTraces) {
            StructuredLog.info(log, Markers.OPS, "retention.pinned")
                    .field("project", projectId)
                    .field("traces", repo.countPinnedTraces(projectId))
                    .log();
        }
        return deleted;
    }

    /**
     * Collect the project's unreferenced media — every pass, for every project, whatever its policies say.
     *
     * <p>This is garbage collection, not retention. An image whose payload has been deleted (or whose
     * batch never committed) cannot be reached, named or rendered by anything: no TTL makes it useful and
     * no policy is expressed by keeping it. Gating it on a bounded traces policy the way the classes above
     * are gated would leave every keep-forever project accumulating unreachable bytes for ever, which is
     * the half of #761 that is a disk-space bug and the half that is a deletion-promise bug at once.
     *
     * <p>Bounded like everything else, and logged only when it actually removed something.
     */
    private long collectMedia(String projectId) {
        int batchSize = Math.max(1, props.getBatchSize());
        int maxBatches = Math.max(1, props.getMaxBatchesPerSweep());
        String cutoff = Instant.now()
                .minus(Math.max(0, props.getMediaGraceHours()), ChronoUnit.HOURS)
                .toString();
        long deleted = 0;
        boolean truncated = true;
        for (int batch = 0; batch < maxBatches; batch++) {
            int removed = repo.deleteOrphanedMedia(projectId, cutoff, batchSize);
            deleted += removed;
            if (removed < batchSize) {
                truncated = false;
                break;
            }
        }
        if (deleted == 0) return 0;
        StructuredLog.info(log, Markers.OPS, "retention.media_collected")
                .field("project", projectId)
                .field("cutoff", cutoff)
                .field("deleted", deleted)
                .field("truncated", truncated)
                .log();
        return deleted;
    }

    private long sweepClass(String projectId, EffectiveRetention retention, String cutoff) {
        int batchSize = Math.max(1, props.getBatchSize());
        int maxBatches = Math.max(1, props.getMaxBatchesPerSweep());
        long deleted = 0;
        boolean truncated = true;
        for (int batch = 0; batch < maxBatches; batch++) {
            int removed = delete(projectId, retention.dataClass(), cutoff, batchSize);
            deleted += removed;
            if (removed < batchSize) {
                truncated = false;
                break;
            }
        }
        if (deleted == 0) return 0;
        StructuredLog.info(log, Markers.OPS, "retention.deleted")
                .field("project", projectId)
                .field("data_class", retention.dataClass().wire())
                .field("ttl_days", retention.ttlDays())
                .field("from_policy", retention.fromPolicy())
                .field("cutoff", cutoff)
                .field("deleted", deleted)
                .field("truncated", truncated)
                .log();
        return deleted;
    }

    private int delete(String projectId, DataClass dataClass, String cutoff, int limit) {
        return switch (dataClass) {
            // Payloads first, then the structure. Both tiers run under the traces policy's TTL for now —
            // giving payloads their own shorter horizon is a config decision, not a schema one, and the
            // ordering here is what makes it a one-line change when that number is chosen. Until then the
            // payload delete is a no-op after the first pass, since it removes rows the trace delete in
            // the same sweep would have cascaded anyway.
            // Evidence last, and only ever after the substrate deletes above: a row is collectable
            // exactly when its claim is closed AND the thing it pointed at is already gone, so running
            // it in the same pass releases the previous pass's remainder rather than its own work.
            case TRACES ->
                repo.deleteSpanPayloads(projectId, cutoff, limit)
                        + repo.deleteTraces(projectId, cutoff, limit)
                        + repo.deleteOrphanedEvidence(projectId, limit);
            case DETECTIONS -> repo.deleteDetections(projectId, cutoff, limit);
        };
    }
}
