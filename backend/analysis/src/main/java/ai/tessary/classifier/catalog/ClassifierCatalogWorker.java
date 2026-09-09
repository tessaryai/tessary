// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every active project's built-in classifier catalog matching what its org is entitled to:
 * {@link ClassifierService#resyncBuiltIns} on a heartbeat, over {@code ProjectRepository#findActive()}.
 * The second of catalog provisioning's two triggers; {@link ClassifierSeedListener} is the first and
 * fires once, at project creation.
 *
 * <p>This scans the project table rather than the sweep tick's list of projects with observations,
 * because catalog membership must not wait on trace ingestion: a project with zero spans would
 * otherwise keep a stale catalog until its first span landed. It runs on its own schedule
 * ({@code tessary.classifier.catalog-resync-ms}) rather than widening the sweep tick, since nothing
 * downstream waits on a reconcile and the two want different cadences as the project count grows.
 *
 * <p>Sweep enqueue still keys on projects with observations: a job row per classifier on a project
 * that has never sent a span would be claimed and dispatched to sweep an empty window every tick,
 * which is the load this split avoids.
 */
@Component
public class ClassifierCatalogWorker {

    private static final Logger log = LoggerFactory.getLogger(ClassifierCatalogWorker.class);

    private final ClassifierService classifiers;
    private final ProjectRepository projects;
    private final TraceMdcBridge traceBridge;

    public ClassifierCatalogWorker(
            ClassifierService classifiers, ProjectRepository projects, TraceMdcBridge traceBridge) {
        this.classifiers = classifiers;
        this.projects = projects;
        this.traceBridge = traceBridge;
    }

    @Scheduled(fixedDelayString = "${tessary.classifier.catalog-resync-ms:60000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        Instant start = Instant.now();
        List<Project> active;
        try {
            active = projects.findActive();
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "classifier.catalog.project-scan-failed")
                    .field("error", e.getMessage())
                    .log();
            return;
        }

        int seeded = 0;
        int failed = 0;
        for (Project project : active) {
            try {
                seeded += classifiers.resyncBuiltIns(project);
            } catch (RuntimeException e) {
                // One project's catalog failing must not strand every other project's; the next tick
                // simply tries again. Without this line a project that fails every tick is invisible,
                // and its symptom looks like a flag problem rather than a backend one.
                failed++;
                StructuredLog.warn(log, Markers.OPS, "classifier.catalog.resync-failed")
                        .field("project", project.id())
                        .field("org", project.orgId())
                        .field("error", e.getMessage())
                        .log();
            }
        }

        // The steady state is "scanned N, changed nothing", every minute, forever: DEBUG. A pass
        // that actually provisioned something, or failed on a project, is the outcome an operator
        // wants at INFO.
        StructuredLog.Builder line = seeded > 0 || failed > 0
                ? StructuredLog.info(log, Markers.OPS, "classifier.catalog.reconciled")
                : StructuredLog.debug(log, "classifier.catalog.reconciled");
        line.message("reconciled the built-in catalog across %d project(s), %d seeded", active.size(), seeded)
                .field("projects", active.size())
                .field("seeded", seeded)
                .field("failed", failed)
                .durationMs(start)
                .log();
    }
}
