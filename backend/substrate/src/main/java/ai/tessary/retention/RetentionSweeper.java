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
 * Enforces {@code retention_policy}. Each (project, data class) gets at most {@code max-batches-per-sweep} batches,
 * logging {@code truncated=true} when capped. Deletes by event time ({@code started_at}) so replayed history ages on
 * its own clock; payloads go before structure, and unreferenced media is collected every pass regardless of policy.
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
        if (!props.enabled()) return;
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
        // Reported every pass: a pin that never releases looks like a healthy policy from deletion counts alone.
        if (sweptTraces) {
            StructuredLog.info(log, Markers.OPS, "retention.pinned")
                    .field("project", projectId)
                    .field("traces", repo.countPinnedTraces(projectId))
                    .log();
        }
        return deleted;
    }

    /**
     * Garbage collection, not retention: unreferenced media is unreachable, so gating it on a traces policy would leak
     * it for keep-forever projects.
     */
    private long collectMedia(String projectId) {
        int batchSize = Math.max(1, props.batchSize());
        int maxBatches = Math.max(1, props.maxBatchesPerSweep());
        String cutoff = Instant.now()
                .minus(Math.max(0, props.mediaGraceHours()), ChronoUnit.HOURS)
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
        int batchSize = Math.max(1, props.batchSize());
        int maxBatches = Math.max(1, props.maxBatchesPerSweep());
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
            // Payloads first, then structure; both tiers use the traces TTL for now. Frustration's copies of turn text
            // age with the payloads. Evidence last: a row is collectable only when its claim is closed and its target
            // is already gone.
            case TRACES ->
                repo.nullAssessmentRequests(projectId, cutoff, limit)
                        + repo.deleteSpanPayloads(projectId, cutoff, limit)
                        + repo.deleteTraces(projectId, cutoff, limit)
                        + repo.deleteOrphanedEvidence(projectId, limit);
            case DETECTIONS -> repo.deleteDetections(projectId, cutoff, limit);
        };
    }
}
