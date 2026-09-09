// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link CaseReconciler} over every live project on a heartbeat.
 *
 * <p>This is where the detectors' cost is paid. CUSUM replays four weeks of hourly buckets for
 * every detector in the project; doing that on a page load would put the platform's most
 * expensive computation on its first screen for every visitor. Running it here once per cadence
 * and persisting the outcome makes Triage an indexed table read.
 *
 * <p>The cadence is the detection latency: five minutes is well inside the hourly grain the
 * detectors bucket on, so a spell cannot be missed by polling faster than the data changes.
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

    @Scheduled(fixedDelayString = "${tessary.cases.heartbeat-ms:300000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            int projectCount = 0;
            for (Project project : projects.findActive()) {
                try {
                    reconciler.reconcile(project.id());
                    projectCount++;
                } catch (RuntimeException e) {
                    // One project's detectors failing must not stop every other project's cases
                    // from being reconciled: a stalled sweep is silent, and silence here reads as
                    // "everything is fine".
                    log.warn(Markers.OPS, "case reconcile failed project={}", project.id(), e);
                }
            }
            log.debug("case reconcile tick projects={}", projectCount);
        }
    }
}
