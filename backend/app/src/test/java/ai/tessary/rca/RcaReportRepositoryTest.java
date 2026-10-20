// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.cases.CaseDetection;
import ai.tessary.cases.CaseKey;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaReportRepository.RunState;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RcaParkedSpringBootTest;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where an RCA stands, read from its job and its newest agent run against the real tables. The case list marks each
 * case from this read, so the bugs here are an old report's state shown after a newer one, a running RCA that cannot
 * say which step it is on, and one project's state shown on another's case.
 */
@RcaParkedSpringBootTest
record RcaReportRepositoryTest(
        RcaReportRepository reports,
        RcaJobRepository jobs,
        RcaTriggerService trigger,
        AgentRunRepository runs,
        CaseService cases,
        CaseRepository caseRows,
        FindingRepository findings,
        TenantService tenants,
        JdbcClient jdbc) {

    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    private record Seeded(String projectId, String caseId, String findingId) {}

    private Seeded caseIn(String name) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        String now = Instant.now().toString();
        String findingId = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        pid,
                        BuiltInDetector.Kind.SECRET_LEAK,
                        "clf-a",
                        "cause-a",
                        1,
                        "cs-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
        String caseId = caseRows.open(
                        pid,
                        new CaseDetection(
                                new CaseKey(
                                        CaseRow.Detector.CLASSIFIER, CaseRow.SubjectKind.CLASSIFIER, "p-1", "novelty"),
                                "extraction",
                                "cs-a",
                                findingId,
                                "A step the agent always performs was skipped",
                                "seen on 1 trace",
                                0.4,
                                Instant.parse("2026-07-01T10:00:00Z"),
                                null,
                                null,
                                null),
                        Instant.now())
                .orElseThrow()
                .id();
        return new Seeded(pid, caseId, findingId);
    }

    /**
     * An old failure shown after a good re-run: the case reads its newest report only, whatever an older report's job
     * and run say.
     */
    @Test
    void theCaseMarkReadsTheNewestReportOnly() {
        Seeded s = caseIn("rca-state-newest");
        RcaReportView first = cases.runRca(s.projectId(), s.caseId(), "user-1");
        String failedRun = runs.open(s.projectId(), AgentRunRepository.Lane.RCA, first.id(), first.jobId(), null);
        runs.close(failedRun, AgentRunRepository.Outcome.FAILED, "RCA.PROVIDER_REJECTED");
        jobs.markFailed(first.jobId(), "rejected");
        RcaReportView rerun = trigger.trigger(s.projectId(), s.findingId(), "user-1", Ids.ulid());

        RunState queued =
                reports.runStatesByCase(s.projectId(), List.of(s.caseId())).get(s.caseId());

        assertEquals(rerun.id(), queued.reportId());
        assertEquals("pending", queued.jobStatus());
        assertNull(queued.runId(), "the earlier report's run belongs to the earlier report");
        assertNull(queued.stopCode());

        jobs.markDone(rerun.jobId());

        assertEquals(
                "done",
                reports.runStatesByCase(s.projectId(), List.of(s.caseId()))
                        .get(s.caseId())
                        .jobStatus());
    }

    /**
     * A running RCA that cannot say where it is: a claimed job reads as leased, with its newest run, the step it is on
     * and how many it took.
     */
    @Test
    void aRunningReportCarriesItsLeaseItsNewestRunAndItsCurrentStep() {
        Seeded s = caseIn("rca-state-running");
        RcaReportView report = cases.runRca(s.projectId(), s.caseId(), "user-1");
        jdbc.sql("UPDATE job SET status = 'claimed', lease_owner = 'rca-worker-1' WHERE id = :id")
                .param("id", report.jobId())
                .update();
        String runId = runs.open(s.projectId(), AgentRunRepository.Lane.RCA, report.id(), report.jobId(), null);
        runs.stepStarted(runId, "c-1", "Read the tool guide", "Reading the tool guide", 4);
        runs.stepEnded(runId, "c-1", 9, true, "Read the tool guide");
        runs.stepStarted(runId, "c-2", "Read commit a41c0de", "Reading commit a41c0de", 12);

        RunState state = reports.runState(s.projectId(), report.id()).orElseThrow();

        assertEquals(report.id(), state.reportId());
        assertEquals("claimed", state.jobStatus());
        assertTrue(state.leased());
        assertEquals(runId, state.runId());
        assertEquals(2, state.stepCount());
        assertEquals("Reading commit a41c0de", state.doing());
        assertNull(state.endedAt());
        assertEquals(
                state,
                reports.runStatesByCase(s.projectId(), List.of(s.caseId())).get(s.caseId()),
                "the case list and the report read the same state");
    }

    /** A case never analyzed has no state, and another project's case is never read (cross-project leak). */
    @Test
    void aCaseNeverAnalyzedHasNoStateAndAnotherProjectsCaseIsNotRead() {
        Seeded never = caseIn("rca-state-never");
        Seeded other = caseIn("rca-state-other");
        RcaReportView report = cases.runRca(other.projectId(), other.caseId(), "user-1");

        Map<String, RunState> states =
                reports.runStatesByCase(never.projectId(), List.of(never.caseId(), other.caseId()));

        assertEquals(Map.of(), states);
        assertEquals(Map.of(), reports.runStatesByCase(never.projectId(), List.of()), "an empty page reads nothing");
        assertFalse(reports.runState(never.projectId(), report.id()).isPresent());
    }
}
