// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every eligible finding gets exactly one triage run, since an untriaged finding is never seen. Gated on
 * {@link Capability#TRIAGE_AUTOMATIC} (off by default); a failing flag store resolves to off, never to spending. The
 * one gate is the recurrence bar ({@code triageMinTraceCount}); there are no per-tick caps, because the queue and
 * {@code behaviorTriageTaskExecutor} already bound concurrency and {@code TriageLauncherBreaker} stops runaway retries.
 * It calls {@link FindingService#analyze}, the same method as the button.
 */
@Component
public class TriageAutoEscalator {

    private static final Logger log = LoggerFactory.getLogger(TriageAutoEscalator.class);

    /**
     * Not a ration: it stops a mis-calibrated detector materializing an unbounded list; the rest waits for the next
     * tick.
     */
    private static final int SOURCE_SCAN_LIMIT = 500;

    private final ProjectRepository projects;
    private final CapabilityService capabilities;
    private final List<TriageSource> sources;
    private final FindingService drift;
    private final ClassifierProperties props;
    private final TraceMdcBridge traceBridge;

    public TriageAutoEscalator(
            ProjectRepository projects,
            CapabilityService capabilities,
            List<TriageSource> sources,
            FindingService drift,
            ClassifierProperties props,
            TraceMdcBridge traceBridge) {
        this.projects = projects;
        this.capabilities = capabilities;
        this.sources = sources;
        this.drift = drift;
        this.props = props;
        this.traceBridge = traceBridge;
    }

    @Scheduled(fixedDelayString = "${tessary.classifier.triage-interval-ms:900000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        List<Project> active;
        try {
            active = projects.findActive();
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "triage.auto.project-scan-failed")
                    .field("error", e.getMessage())
                    .log();
            return;
        }
        for (Project project : active) {
            // Per project, since the resolution is cached below CapabilityService.
            if (!enabledFor(project)) continue;
            try {
                escalateFor(project);
            } catch (RuntimeException e) {
                // One project's failure must not stop the sweep; the next tick tries again.
                StructuredLog.warn(log, Markers.OPS, "triage.auto.project-failed")
                        .field("project", project.id())
                        .field("error", e.getMessage())
                        .log();
            }
        }
    }

    private boolean enabledFor(Project project) {
        try {
            return capabilities.isEnabled(project.orgId(), Capability.TRIAGE_AUTOMATIC);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "triage.auto.capability-unresolved")
                    .field("project", project.id())
                    .field("org", project.orgId())
                    .field("error", e.getMessage())
                    .log();
            return false;
        }
    }

    private void escalateFor(Project project) {
        int escalated = 0;
        for (TriageSource source : sources) {
            for (TriageSource.Escalatable finding :
                    source.listAutoEscalatable(project.id(), props.getTriageMinTraceCount(), SOURCE_SCAN_LIMIT)) {
                // The grader lane is a human's choice, so the scheduler requests none.
                drift.analyze(project.id(), finding.findingId(), null);
                escalated++;
            }
        }
        if (escalated == 0) return;
        StructuredLog.info(log, Markers.OPS, "triage.auto.escalated")
                .message("scheduled " + escalated + " triage run(s) for " + project.id())
                .field("project", project.id())
                .field("count", escalated)
                .log();
    }
}
