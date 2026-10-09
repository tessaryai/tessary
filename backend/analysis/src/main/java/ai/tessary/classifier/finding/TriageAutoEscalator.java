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
 * The triage scheduler: every finding that opens gets exactly one triage run, and every finding a
 * ruling closed gets a second one if its cause keeps firing.
 *
 * <h2>Autonomy, not escalation</h2>
 *
 * <p>This used to be an opt-in that pressed <em>Run analysis</em> a handful of times a day, because a
 * ruling was advice a person then had to act on and 8 a day was what the shared budget allowed. Triage
 * decides now — {@code positive} opens a case a person reads, {@code negative} closes the finding — so
 * a finding nobody triaged is a finding nobody will ever see. Every eligible finding is therefore
 * scheduled, with no per-tick or per-project ration on top.
 *
 * <h2>Off unless an org opts in</h2>
 *
 * <p>Gated on {@link Capability#TRIAGE_AUTOMATIC}, which is off by default in every edition — so this
 * component runs on every deployment and does nothing on every project, until an org turns it on for itself.
 * A flag store that is empty, unreachable or erroring supplies no opinion and leaves that
 * default standing, so the failure mode of the capability layer is "manual", never "spending".
 *
 * <h2>What bounds it, and what deliberately does not</h2>
 *
 * <p><b>One gate remains: the recurrence bar.</b> A cause seen once is a coincidence, so
 * {@code triageMinTraceCount} is how many samples it must have been observed over first. It survived
 * the budgeted era because it is about whether a claim is worth auditing, not about cost.
 *
 * <p><b>The per-tick and per-project caps are gone</b>, and their removal is the point rather than a
 * relaxation. They were read as protecting E2B; they were not. {@code job.kind='triage'} is a real
 * queue with {@code SKIP LOCKED} claims and leases, and {@code behaviorTriageTaskExecutor} bounds
 * concurrent microVMs to two — so the caps never limited what reached the launcher, only which findings
 * were allowed to exist as work. A backlog is the queue doing its job; a finding refused because a
 * counter was full is one nobody ever sees, which is the failure this scheduler exists to prevent.
 * Runaway protection belongs where the money is spent, and {@code TriageLauncherBreaker} is what stops
 * a broken launcher being retried into the ground.
 *
 * <h2>No re-open on recurrence</h2>
 *
 * <p>A closed finding stays closed. Its cause firing again opens a FRESH finding — the row a ruling
 * settled has already left {@code ux_finding_live} by construction — and that fresh finding is
 * triaged exactly like any other, with no special counting of how many times this cause has been
 * ruled before. There is no re-open path here to describe.
 *
 * <h2>It presses the same button</h2>
 *
 * <p>This does not enqueue. It calls {@link FindingService#analyze}, the exact method the
 * <em>Run analysis</em> button calls, so the once-per-look guarantee, the payload assembly and the ops
 * log are one implementation with one set of behaviours. Automatic mode is therefore not a second path
 * that could drift from the manual one — it is the manual one, pressed by a scheduler.
 *
 * <h2>One scheduler, several stores</h2>
 *
 * <p>Eligibility is asked through the {@link TriageSource} seam, in registration order. The recurrence bar is
 * shared across the sources, read in each store's own observation unit. Registration order no longer decides
 * who gets scheduled, only who is scheduled first: with the caps gone, every eligible finding in every source
 * is taken.
 */
@Component
public class TriageAutoEscalator {

    private static final Logger log = LoggerFactory.getLogger(TriageAutoEscalator.class);

    /**
     * Page size for one source's eligible set. Not a ration — every eligible finding is scheduled, and a
     * tick that fills this page leaves the rest for the next one fifteen minutes later. It exists so a
     * mis-calibrated detector cannot materialize an unbounded list in memory before anything notices.
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
            // The flag is asked per project rather than once per org, because a deployment's projects are
            // few and the resolution is cached below the CapabilityService; asking per org would mean
            // building a distinct-org set whose only purpose is to save a call that is already cheap.
            if (!enabledFor(project)) continue;
            try {
                escalateFor(project);
            } catch (RuntimeException e) {
                // One project's failure must not stop the rest of the sweep. Nothing downstream is waiting
                // on this ruling, so the next tick simply tries again.
                StructuredLog.warn(log, Markers.OPS, "triage.auto.project-failed")
                        .field("project", project.id())
                        .field("error", e.getMessage())
                        .log();
            }
        }
    }

    /** Whether this project's org has opted into automatic mode. A flag-layer failure resolves to off. */
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
                // No lane requested: triage takes the sandbox lane the project supports. The grader lane
                // is a human's choice about whether the graders measure this kind of thing, and a
                // scheduler cannot make it.
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
