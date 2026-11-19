// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.agentrun.AgentRunDtos.RunStepsView;
import ai.tessary.agentrun.AgentRunDtos.StepView;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.fix.FixDtos.FixView;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaRunView;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RcaParkedSpringBootTest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The case page's reads of a fix against real tables: the fix block on the case and the run bar's steps. The bugs: a
 * case with a running fix that shows no fix, a fix block whose run bar is not the run's own agent run, steps read from
 * another project's run, and a case never fixed that draws an empty fix card.
 */
@RcaParkedSpringBootTest
record FixControllerTest(
        FixController controller,
        FixRunRepository fixRuns,
        FixPrRepository prs,
        AgentRunRepository runs,
        TenantService tenants,
        FindingRepository findings,
        CaseRepository cases,
        CaseService caseService,
        JdbcClient jdbc) {

    private static final Instant T0 = Instant.parse("2026-11-19T12:00:00Z");

    private static TenantContext ctx(FixSeeds.Seeded s) {
        return new TenantContext(s.userId(), s.tenant().user().email(), null, null, null, null);
    }

    @Test
    void theCaseShowsItsPullRequestAndRunningFixAndTheRunBarReadsItsSteps() {
        FixSeeds.Seeded s = FixSeeds.caseWithReport(tenants, findings, cases, caseService, "fix-reads");
        // The queue is global: a run another test left queued would be claimed first.
        jdbc.sql("UPDATE fix_run SET status = 'failed' WHERE status IN ('queued', 'running')")
                .update();
        assertNull(caseService.detail(s.projectId(), s.caseId()).fix(), "a case never fixed has no fix block");
        String prId = prs.insertOpen(
                s.projectId(), s.caseId(), "tessary/fix", 41, "https://pr/41", "h1", List.of("code:a.py"), T0);
        String runId = fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of("code:a.py"), T0)
                .orElseThrow();
        assertEquals(runId, fixRuns.claimNext(T0).orElseThrow().id());
        String agentRun = runs.open(s.projectId(), AgentRunRepository.Lane.FIX, runId, runId, null);
        runs.stepStarted(agentRun, "c-1", "Read src/a.py", "Reading src/a.py", 4);
        runs.stepEnded(agentRun, "c-1", 9, true, "Read src/a.py");
        runs.stepStarted(agentRun, "c-2", "Edited src/a.py", "Editing src/a.py", 12);

        CaseDetailView detail = caseService.detail(s.projectId(), s.caseId());
        RunStepsView steps = Objects.requireNonNull(controller
                .steps(ctx(s), s.tenant().org().slug(), s.tenant().project().slug(), runId, 1)
                .data());

        FixView fix = Objects.requireNonNull(detail.fix());
        assertEquals(prId, prs.listByCase(s.projectId(), s.caseId()).get(0).id());
        assertEquals("https://pr/41", Objects.requireNonNull(fix.pr()).url());
        RcaRunView bar =
                Objects.requireNonNull(Objects.requireNonNull(fix.latestRun()).run());
        assertEquals(
                List.of(RcaRunView.RUNNING, agentRun, "Editing src/a.py"),
                List.of(bar.state(), bar.runId(), bar.doing()));
        assertEquals(
                new RunStepsView(
                        agentRun, 2, List.of(new StepView(2, "Edited src/a.py", "Editing src/a.py", 12, null, null))),
                steps);
    }

    @Test
    void stepsOfAnotherProjectsRunAreNotFound() {
        FixSeeds.Seeded a = FixSeeds.caseWithReport(tenants, findings, cases, caseService, "fix-steps-a");
        FixSeeds.Seeded b = FixSeeds.caseWithReport(tenants, findings, cases, caseService, "fix-steps-b");
        String runId = fixRuns.enqueue(a.projectId(), a.caseId(), a.reportId(), List.of(), T0)
                .orElseThrow();
        runs.open(a.projectId(), AgentRunRepository.Lane.FIX, runId, runId, null);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.steps(
                        ctx(b), b.tenant().org().slug(), b.tenant().project().slug(), runId, 0));

        assertEquals(CommonError.NOT_FOUND, e.error());
    }
}
