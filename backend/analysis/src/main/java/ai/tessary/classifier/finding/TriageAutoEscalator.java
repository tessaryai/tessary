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
import java.time.Duration;
import java.time.Instant;
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
 * decides now — {@code positive} opens a case a person reads, {@code negative} and {@code unclear} close
 * the finding — so a finding nobody triaged is a finding nobody will ever see. Every eligible finding is
 * therefore scheduled, with no per-tick or per-project ration on top.
 *
 * <h2>Off unless an org opts in</h2>
 *
 * <p>Gated on {@link Capability#TRIAGE_AUTOMATIC}, which is off by default in every edition — so this
 * component runs on every deployment and does nothing on every project, until an org turns it on for itself
 * (decision D5). A flag store that is empty, unreachable or erroring supplies no opinion and leaves that
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
 * <h2>Re-open on recurrence</h2>
 *
 * <p>Closing on {@code unclear} is only safe because a wrong close is recoverable, and this is the
 * recovery: a closed finding whose cause fires {@code triageReopenRecurrences} more times within
 * {@code triageReopenWindowHours} has its ruling cleared and goes back through triage. A finding that
 * has already had two looks and closed again does not get a third — it opens a case, because at that
 * point the disagreement is between the agent and the traffic, and a person settles that.
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
 * <p>Eligibility is asked through the {@link TriageSource} seam, in registration order — the behaviour
 * table first, conformance after it. The recurrence bar is shared across the sources, read in each
 * store's own observation unit. Registration order no longer decides who gets scheduled, only who is
 * scheduled first: with the caps gone, every eligible finding in every source is taken.
 */
@Component
public class TriageAutoEscalator {

    private static final Logger log = LoggerFactory.getLogger(TriageAutoEscalator.class);

    /**
     * How many looks a finding gets before recurrence stops buying another one. Two: the first ruling,
     * and one re-look after the traffic contradicted it. A third would be the same agent reading the
     * same evidence for the same answer.
     */
    private static final int MAX_LOOKS = 2;

    /**
     * Page size for one source's eligible set. Not a ration — every eligible finding is scheduled, and a
     * tick that fills this page leaves the rest for the next one fifteen minutes later. It exists so a
     * mis-calibrated detector cannot materialize an unbounded list in memory before anything notices.
     */
    private static final int SOURCE_SCAN_LIMIT = 500;

    /**
     * How many closed findings one tick examines for recurrence. Previously the per-tick escalation cap,
     * borrowed — which coupled two unrelated bounds, so removing the cap would silently have made this
     * scan unbounded.
     */
    private static final int REOPEN_SCAN_LIMIT = 100;

    private final ProjectRepository projects;
    private final CapabilityService capabilities;
    private final List<TriageSource> sources;
    private final BehaviorTriageJobRepository jobs;
    private final FindingRepository findings;
    private final FindingService drift;
    private final ClassifierProperties props;
    private final TraceMdcBridge traceBridge;

    public TriageAutoEscalator(
            ProjectRepository projects,
            CapabilityService capabilities,
            List<TriageSource> sources,
            BehaviorTriageJobRepository jobs,
            FindingRepository findings,
            FindingService drift,
            ClassifierProperties props,
            TraceMdcBridge traceBridge) {
        this.projects = projects;
        this.capabilities = capabilities;
        this.sources = sources;
        this.jobs = jobs;
        this.findings = findings;
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
                // Re-opens first, and the order is load-bearing: a finding the traffic has contradicted
                // has a stronger claim on a scarce tick than one nothing has ruled on yet, and re-opening
                // it before the scan means it is eligible in the same tick rather than the next one.
                reopenRecurring(project);
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

    /**
     * Send closed findings whose cause kept firing back through triage — or, if they have already had
     * their two looks, straight to a case.
     *
     * <p>The case is opened by the ordinary gate rather than by new machinery: writing {@code positive} on
     * the finding is what a case source reads, so a recurrence-opened case reaches Triage by the same
     * road a triage-opened one does. The summary says the ruling came from the recurrence rather than
     * from the agent, because a reader is entitled to know that nothing re-examined the evidence.
     */
    private void reopenRecurring(Project project) {
        String since = Instant.now()
                .minus(Duration.ofHours(props.getTriageReopenWindowHours()))
                .toString();
        List<FindingRow> recurring = findings.listRecurringClosed(
                project.id(), props.getTriageReopenRecurrences(), since, REOPEN_SCAN_LIMIT);
        int reopened = 0;
        int escalatedToCase = 0;
        for (FindingRow finding : recurring) {
            String now = Instant.now().toString();
            if (jobs.countLooks(project.id(), finding.id()) >= MAX_LOOKS) {
                findings.recordTriage(
                        project.id(),
                        finding.id(),
                        FindingRow.TriageVerdict.POSITIVE,
                        "Closed by triage twice and its cause has fired " + finding.recurrencesSinceVerdict()
                                + " more time(s) since. The traffic disagrees with both rulings, so this is a"
                                + " case for a person rather than a third look.",
                        null,
                        now);
                escalatedToCase++;
                continue;
            }
            if (findings.reopenForTriage(project.id(), finding.id(), now) == 1) reopened++;
        }
        if (reopened == 0 && escalatedToCase == 0) return;
        StructuredLog.info(log, Markers.OPS, "triage.auto.reopened")
                .message("recurrence re-opened " + reopened + " finding(s) and opened " + escalatedToCase
                        + " case(s) directly for " + project.id())
                .field("project", project.id())
                .field("reopened", reopened)
                .field("opened_case", escalatedToCase)
                .field("recurrences", props.getTriageReopenRecurrences())
                .field("window_hours", props.getTriageReopenWindowHours())
                .log();
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
