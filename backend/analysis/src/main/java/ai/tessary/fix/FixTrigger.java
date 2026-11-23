// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.fix.FixRunRow.Reason;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.rca.RcaDtos;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides, after every finished RCA (pressed, automatic or a rerun), whether its case gets a fix run. The gates, in
 * order: {@link Capability#FIX_PR}, a destination that can take the fix (a repository Tessary can write to), a frontier
 * model on the fix lane, and at least one {@link FixableCause}. With the capability off nothing is written, as the
 * feature is not in use; any other shut gate records a skipped run with the gate as its reason, so the case can say
 * why there is no pull request. One run per report.
 */
@Component
public class FixTrigger {

    private static final Logger log = LoggerFactory.getLogger(FixTrigger.class);

    private final CapabilityService capabilities;
    private final ProjectRepository projects;
    private final FindingRepository findings;
    private final RcaReportRepository reports;
    private final FixDestination destination;
    private final ProjectModelSettings models;
    private final FixRunRepository fixRuns;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public FixTrigger(
            CapabilityService capabilities,
            ProjectRepository projects,
            FindingRepository findings,
            RcaReportRepository reports,
            FixDestination destination,
            ProjectModelSettings models,
            FixRunRepository fixRuns,
            ObjectMapper mapper) {
        this(capabilities, projects, findings, reports, destination, models, fixRuns, mapper, Clock.systemUTC());
    }

    FixTrigger(
            CapabilityService capabilities,
            ProjectRepository projects,
            FindingRepository findings,
            RcaReportRepository reports,
            FixDestination destination,
            ProjectModelSettings models,
            FixRunRepository fixRuns,
            ObjectMapper mapper,
            Clock clock) {
        this.capabilities = capabilities;
        this.projects = projects;
        this.findings = findings;
        this.reports = reports;
        this.destination = destination;
        this.models = models;
        this.fixRuns = fixRuns;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Never throws: the RCA is done and committed, so a failure here costs only this report's fix run.
     *
     * @param findingId the finding the RCA was run on, which names its case
     */
    public void afterRca(String projectId, String reportId, String findingId) {
        try {
            decide(projectId, reportId, findingId);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "fix.trigger-failed")
                    .message("could not decide the fix run for report " + reportId)
                    .field("project", projectId)
                    .field("report", reportId)
                    .field("error", e.toString())
                    .log();
        }
    }

    private void decide(String projectId, String reportId, String findingId) {
        Project project = projects.findById(projectId).orElseThrow();
        if (!capabilities.isEnabled(project.orgId(), Capability.FIX_PR)) return;
        Optional<String> caseId = findings.findById(projectId, findingId).map(FindingRow::caseId);
        if (caseId.isEmpty()) return;
        RcaReportRow report = reports.findById(projectId, reportId).orElseThrow();
        List<FixableCause> causes =
                FixableCause.of(RcaDtos.causesOf(mapper, report.reportKind(), report.causes(), report.hypotheses()));
        List<String> keys = FixableCause.keys(causes);
        String reason = shut(projectId, causes);
        if (reason == null) {
            fixRuns.enqueue(projectId, caseId.get(), reportId, keys, clock.instant());
        } else {
            fixRuns.skip(projectId, caseId.get(), reportId, reason, keys, clock.instant());
        }
    }

    private @Nullable String shut(String projectId, List<FixableCause> causes) {
        String blocked = destination.blocked(projectId);
        if (blocked != null) return blocked;
        if (models.resolve(projectId, ModelLane.FIX).isEmpty()) return Reason.NO_FRONTIER_MODEL;
        return causes.isEmpty() ? Reason.NO_FIXABLE_CAUSE : null;
    }
}
