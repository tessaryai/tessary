// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.cases.CaseService;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs RCA on live cases that hold windows their newest RCA did not read, gated on {@link Capability#RCA} and
 * {@link Capability#RCA_AUTOMATIC}; a failing flag store resolves to off. Only windows created after the org turned the
 * capability on count, so turning it on does not analyse a backlog. A case gets at most one run per
 * {@link #MIN_GAP}, never while one of its runs is queued or running. The run acts as the org's eldest owner, as
 * automatic triage does, through {@link CaseService#runRcaAutomatically}, the button's path.
 */
@Component
public class RcaAutoTrigger {

    private static final Logger log = LoggerFactory.getLogger(RcaAutoTrigger.class);

    static final Duration MIN_GAP = Duration.ofHours(6);

    /** Not a ration: it stops one tick from walking an unbounded list; the rest waits for the next tick. */
    private static final int CASE_SCAN_LIMIT = 100;

    private final ProjectRepository projects;
    private final CapabilityService capabilities;
    private final OrgFeatureFlagRepository overrides;
    private final OrgMembershipRepository memberships;
    private final RcaReportRepository reports;
    private final CaseService cases;
    private final TraceMdcBridge traceBridge;
    private final Clock clock;

    @Autowired
    public RcaAutoTrigger(
            ProjectRepository projects,
            CapabilityService capabilities,
            OrgFeatureFlagRepository overrides,
            OrgMembershipRepository memberships,
            RcaReportRepository reports,
            CaseService cases,
            TraceMdcBridge traceBridge) {
        this(projects, capabilities, overrides, memberships, reports, cases, traceBridge, Clock.systemUTC());
    }

    RcaAutoTrigger(
            ProjectRepository projects,
            CapabilityService capabilities,
            OrgFeatureFlagRepository overrides,
            OrgMembershipRepository memberships,
            RcaReportRepository reports,
            CaseService cases,
            TraceMdcBridge traceBridge,
            Clock clock) {
        this.projects = projects;
        this.capabilities = capabilities;
        this.overrides = overrides;
        this.memberships = memberships;
        this.reports = reports;
        this.cases = cases;
        this.traceBridge = traceBridge;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tessary.rca.auto-interval-ms:900000}",
            initialDelayString = "${tessary.rca.auto-interval-ms:900000}")
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
            StructuredLog.warn(log, Markers.OPS, "rca.auto.project-scan-failed")
                    .field("error", e.getMessage())
                    .log();
            return;
        }
        Instant now = clock.instant();
        for (Project project : active) {
            if (!enabledFor(project)) continue;
            try {
                triggerFor(project, now);
            } catch (RuntimeException e) {
                // One project's failure must not stop the sweep; the next tick tries again.
                StructuredLog.warn(log, Markers.OPS, "rca.auto.project-failed")
                        .field("project", project.id())
                        .field("error", e.getMessage())
                        .log();
            }
        }
    }

    private boolean enabledFor(Project project) {
        try {
            return capabilities.isEnabled(project.orgId(), Capability.RCA)
                    && capabilities.isEnabled(project.orgId(), Capability.RCA_AUTOMATIC);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "rca.auto.capability-unresolved")
                    .field("project", project.id())
                    .field("org", project.orgId())
                    .field("error", e.getMessage())
                    .log();
            return false;
        }
    }

    private void triggerFor(Project project, Instant now) {
        // On with no override means on by this build's default since the org began, so every window counts.
        Instant since = overrides
                .enabledSince(project.orgId(), Capability.RCA_AUTOMATIC.wire())
                .orElse(Instant.EPOCH);
        List<String> due = reports.dueForAutomaticRca(project.id(), since, now.minus(MIN_GAP), CASE_SCAN_LIMIT);
        if (due.isEmpty()) return;
        Optional<String> owner = OrgMembership.eldestOwner(memberships.findByOrg(project.orgId()));
        if (owner.isEmpty()) {
            StructuredLog.warn(log, Markers.OPS, "rca.auto.no-owner")
                    .message("skipped automatic RCA on " + due.size() + " case(s): the org has no owner to act as")
                    .field("project", project.id())
                    .field("count", due.size())
                    .log();
            return;
        }
        int triggered = 0;
        for (String caseId : due) {
            try {
                if (cases.runRcaAutomatically(project.id(), caseId, owner.get()).isPresent()) triggered++;
            } catch (RuntimeException e) {
                StructuredLog.warn(log, Markers.OPS, "rca.auto.case-failed")
                        .field("project", project.id())
                        .field("caseId", caseId)
                        .field("error", e.getMessage())
                        .log();
            }
        }
        StructuredLog.info(log, Markers.OPS, "rca.auto.triggered")
                .message(
                        "requested automatic RCA on " + triggered + " of " + due.size() + " case(s) in " + project.id())
                .field("project", project.id())
                .field("count", triggered)
                .field("due", due.size())
                .log();
    }
}
