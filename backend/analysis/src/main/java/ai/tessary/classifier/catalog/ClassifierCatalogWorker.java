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
 * Re-syncs every active project's built-in catalog to its entitlements on a heartbeat ({@link ClassifierSeedListener}
 * handles creation). Scans projects rather than ones with observations, so catalog membership does not wait on
 * ingestion; sweep enqueue still keys on observations.
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
                // One project failing must not strand others; counted so a project that fails every tick is visible.
                failed++;
                StructuredLog.warn(log, Markers.OPS, "classifier.catalog.resync-failed")
                        .field("project", project.id())
                        .field("org", project.orgId())
                        .field("error", e.getMessage())
                        .log();
            }
        }

        // The steady state "changed nothing" is DEBUG; provisioning or failure is INFO.
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
