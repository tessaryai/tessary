// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import ai.tessary.evals.config.TraceMdcBridge;
import ai.tessary.evals.open.obs.LogContext;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link CaseReconciler} over every live project on a heartbeat.
 *
 * <p>This is where the detectors' cost is paid. CUSUM replays four weeks of hourly buckets for every
 * grader in the project; doing that on a page load put the platform's most expensive computation on
 * its first screen, for every visitor, whether or not anything had changed. Running it here once per
 * cadence and persisting the outcome makes Triage an indexed table read.
 *
 * <p>The cadence is the detection latency. Five minutes is well inside the hourly grain the detectors
 * bucket on — a spell cannot be missed by polling faster than the data changes.
 */
@Component
public class CaseWorker {

    private static final Logger log = LoggerFactory.getLogger(CaseWorker.class);

    private final CaseReconciler reconciler;
    private final ProjectRepository projects;
    private final TraceMdcBridge traceBridge;

    public CaseWorker(CaseReconciler reconciler, ProjectRepository projects, TraceMdcBridge traceBridge) {
        this.reconciler = reconciler;
        this.projects = projects;
        this.traceBridge = traceBridge;
    }

    @Scheduled(fixedDelayString = "${evals.cases.heartbeat-ms:300000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            int projectCount = 0;
            for (Project project : projects.findActive()) {
                try {
                    reconciler.reconcile(project.id());
                    projectCount++;
                } catch (RuntimeException e) {
                    // One project's detectors failing must not stop every other project's cases from
                    // being reconciled — a stalled sweep is silent, and silence is this product's
                    // "everything is fine".
                    log.warn(Markers.OPS, "case reconcile failed project={}", project.id(), e);
                }
            }
            log.debug("case reconcile tick projects={}", projectCount);
        }
    }
}
