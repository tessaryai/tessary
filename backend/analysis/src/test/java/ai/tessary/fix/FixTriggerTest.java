// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRowBuilder;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The gates between a finished RCA and a fix run, in the plan's order. The bugs: a fix run for an org that turned the
 * feature off, an agent started for a repository Tessary cannot write to or with no frontier model, a run for a report
 * with nothing fixable, a gate that shuts without saying which, and a failure here that breaks the RCA that called it.
 */
@ExtendWith(MockitoExtension.class)
class FixTriggerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private static final String FIXABLE = "[{\"title\":\"Search is never retried\",\"confidence\":\"high\","
            + "\"type\":\"code\",\"attribution\":{\"path\":\"src/agent.py\"}}]";

    private static final String NOT_FIXABLE =
            "[{\"title\":\"Traffic moved\",\"confidence\":\"high\",\"type\":\"traffic\"}]";

    @Mock
    private CapabilityService capabilities;

    @Mock
    private ProjectRepository projects;

    @Mock
    private FindingRepository findings;

    @Mock
    private RcaReportRepository reports;

    @Mock
    private FixDestination destination;

    @Mock
    private ProjectModelSettings models;

    @Mock
    private FixRunRepository fixRuns;

    private FixTrigger trigger() {
        return new FixTrigger(
                capabilities,
                projects,
                findings,
                reports,
                destination,
                models,
                fixRuns,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seed(boolean enabled) {
        when(projects.findById("proj-1"))
                .thenReturn(Optional.of(
                        new Project("proj-1", "org-1", "agent", "Agent", null, "t", null, null, true, null)));
        when(capabilities.isEnabled("org-1", Capability.FIX_PR)).thenReturn(enabled);
    }

    private void seedCaseAndReport(String causes) {
        when(findings.findById("proj-1", "fnd-1"))
                .thenReturn(Optional.of(
                        FindingRowBuilder.of("tool_error").caseId("case-1").build()));
        when(reports.findById("proj-1", "rpt-1")).thenReturn(Optional.of(report(causes)));
    }

    private static RcaReportRow report(String causes) {
        return new RcaReportRow(
                "rpt-1",
                "job-1",
                "finding",
                "fnd-1",
                "Search",
                null,
                "tool_error",
                "metric_movement",
                "t",
                "t",
                "t",
                0.2,
                0.1,
                0.1,
                "done",
                "causes_identified",
                "s",
                null,
                null,
                causes,
                null,
                "agentic",
                true,
                null,
                "t",
                "t");
    }

    @Test
    void anOrgWithFixPullRequestsOffGetsNoRunAndNoRow() {
        seed(false);

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verifyNoInteractions(fixRuns, destination, reports);
    }

    @Test
    void aFindingThatLeftItsCaseGetsNoRun() {
        seed(true);
        when(findings.findById("proj-1", "fnd-1")).thenReturn(Optional.empty());

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verifyNoInteractions(fixRuns, destination);
    }

    @Test
    void aShutDestinationRecordsASkippedRunNamingWhy() {
        seed(true);
        seedCaseAndReport(FIXABLE);
        when(destination.blocked("proj-1")).thenReturn(FixRunRow.Reason.READ_ONLY);

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verify(fixRuns)
                .skip("proj-1", "case-1", "rpt-1", FixRunRow.Reason.READ_ONLY, List.of("code:src/agent.py"), NOW);
        verify(fixRuns, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void noFrontierModelOnTheFixLaneRecordsASkippedRun() {
        seed(true);
        seedCaseAndReport(FIXABLE);
        when(models.resolve("proj-1", ModelLane.FIX)).thenReturn(Optional.empty());

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verify(fixRuns)
                .skip(
                        "proj-1",
                        "case-1",
                        "rpt-1",
                        FixRunRow.Reason.NO_FRONTIER_MODEL,
                        List.of("code:src/agent.py"),
                        NOW);
    }

    @Test
    void aReportWithNothingFixableRecordsASkippedRun() {
        seed(true);
        seedCaseAndReport(NOT_FIXABLE);
        when(models.resolve("proj-1", ModelLane.FIX))
                .thenReturn(Optional.of(new ProjectModelSettings.LaneSelection("anthropic:claude", true)));

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verify(fixRuns).skip("proj-1", "case-1", "rpt-1", FixRunRow.Reason.NO_FIXABLE_CAUSE, List.of(), NOW);
    }

    @Test
    void everyGateOpenQueuesARunWithTheFixableCauses() {
        seed(true);
        seedCaseAndReport(FIXABLE);
        when(models.resolve("proj-1", ModelLane.FIX))
                .thenReturn(Optional.of(new ProjectModelSettings.LaneSelection("anthropic:claude", true)));

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verify(fixRuns).enqueue("proj-1", "case-1", "rpt-1", List.of("code:src/agent.py"), NOW);
    }

    @Test
    void aFailureDecidingTheRunNeverReachesTheRcaThatCalledIt() {
        when(projects.findById("proj-1")).thenThrow(new IllegalStateException("db down"));

        trigger().afterRca("proj-1", "rpt-1", "fnd-1");

        verifyNoInteractions(fixRuns);
    }
}
