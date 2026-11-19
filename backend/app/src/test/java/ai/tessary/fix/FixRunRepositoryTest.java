// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.fix.FixRunRepository.Finish;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.fix.FixRunRow.Outcome;
import ai.tessary.fix.FixRunRow.Reason;
import ai.tessary.fix.FixRunRow.Status;
import ai.tessary.rca.RcaTriggerService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RcaParkedSpringBootTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code fix_run} and its queue against the real table. The bugs: a column written and not read back (the checks
 * above all, stored as JSON), two runs for one report, a run claimed while another run of its case still writes the
 * same branch, a claim out of order, a stale run the sweep cannot find, and a done run with no outcome.
 */
@RcaParkedSpringBootTest
record FixRunRepositoryTest(
        FixRunRepository fixRuns,
        FixPrRepository prs,
        TenantService tenants,
        FindingRepository findings,
        CaseRepository cases,
        CaseService caseService,
        RcaTriggerService trigger,
        JdbcClient jdbc) {

    private static final Instant T0 = Instant.parse("2026-11-19T12:00:00Z");

    private FixSeeds.Seeded seed(String name) {
        return FixSeeds.caseWithReport(tenants, findings, cases, caseService, name);
    }

    /** The queue is global: runs another test left queued or running would be claimed first. */
    private void emptyTheQueue() {
        jdbc.sql("UPDATE fix_run SET status = 'failed' WHERE status IN ('queued', 'running')")
                .update();
    }

    @Test
    void aFinishedRunReadsBackEveryColumn() {
        FixSeeds.Seeded s = seed("fix-run-roundtrip");
        String runId = fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of("code:a.py"), T0)
                .orElseThrow();
        String prId = prs.insertOpen(
                s.projectId(), s.caseId(), "tessary/fix", 41, "https://pr/41", "h1", List.of("code:a.py"), T0);
        List<FixCheck> checks = List.of(
                new FixCheck("pytest", FixCheck.PASSED, null),
                new FixCheck("make e2e", FixCheck.NOT_RUN, "no browser"));
        fixRuns.recordCauses(runId, List.of("code:a.py", "prompt:b.md"), T0.plusSeconds(1));

        fixRuns.finish(runId, new Finish(Outcome.PR_OPENED, null, "It retries.", prId, checks, 2), T0.plusSeconds(60));

        assertEquals(
                Optional.of(new FixRunRow(
                        runId,
                        s.projectId(),
                        prId,
                        s.caseId(),
                        s.reportId(),
                        Status.DONE,
                        Outcome.PR_OPENED,
                        null,
                        "It retries.",
                        List.of("code:a.py", "prompt:b.md"),
                        checks,
                        2,
                        T0.toString(),
                        T0.plusSeconds(60).toString())),
                fixRuns.findById(s.projectId(), runId));
    }

    @Test
    void aSkippedRunIsDoneWithItsGateAndAReportGetsOneRunOnly() {
        FixSeeds.Seeded s = seed("fix-run-skip");

        String runId = fixRuns.skip(s.projectId(), s.caseId(), s.reportId(), Reason.NO_REPO, List.of(), T0)
                .orElseThrow();

        FixRunRow run = fixRuns.findById(s.projectId(), runId).orElseThrow();
        assertEquals(
                List.of(Status.DONE, Outcome.SKIPPED, Reason.NO_REPO),
                List.of(run.status(), run.outcome(), run.reason()));
        assertEquals(Optional.empty(), fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of(), T0));
        assertEquals(Optional.empty(), fixRuns.findById("other-project", runId));
    }

    @Test
    void theClaimTakesTheOldestRunAndSkipsACaseThatHasOneRunning() {
        emptyTheQueue();
        FixSeeds.Seeded a = seed("fix-run-claim-a");
        FixSeeds.Seeded b = seed("fix-run-claim-b");
        String aFirst = fixRuns.enqueue(a.projectId(), a.caseId(), a.reportId(), List.of(), T0)
                .orElseThrow();
        String aSecond = fixRuns.enqueue(
                        a.projectId(), a.caseId(), FixSeeds.rerun(trigger, a), List.of(), T0.plusSeconds(1))
                .orElseThrow();
        String bOnly = fixRuns.enqueue(b.projectId(), b.caseId(), b.reportId(), List.of(), T0.plusSeconds(2))
                .orElseThrow();
        Instant now = T0.plusSeconds(10);

        FixRunRow first = fixRuns.claimNext(now).orElseThrow();
        FixRunRow second = fixRuns.claimNext(now).orElseThrow();

        assertEquals(List.of(aFirst, bOnly), List.of(first.id(), second.id()), "a's second run waits for its first");
        assertEquals(List.of(Status.RUNNING, now.toString()), List.of(first.status(), first.updatedAt()));
        assertEquals(Optional.empty(), fixRuns.claimNext(now));

        fixRuns.fail(aFirst, Reason.INTERRUPTED, now);
        assertEquals(aSecond, fixRuns.claimNext(now).orElseThrow().id());
    }

    @Test
    void twoRunsOfOneCaseCannotBothBeRunning() {
        emptyTheQueue();
        FixSeeds.Seeded s = seed("fix-run-one-running");
        String first = fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of(), T0)
                .orElseThrow();
        String second = fixRuns.enqueue(s.projectId(), s.caseId(), FixSeeds.rerun(trigger, s), List.of(), T0)
                .orElseThrow();
        assertEquals(first, fixRuns.claimNext(T0).orElseThrow().id());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE fix_run SET status = 'running' WHERE id = :id")
                        .param("id", second)
                        .update());
    }

    @Test
    void aRequeuedRunWaitsInTheQueueAndAStaleRunIsFoundBySweep() {
        emptyTheQueue();
        FixSeeds.Seeded s = seed("fix-run-requeue");
        String runId = fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of(), T0)
                .orElseThrow();
        assertEquals(runId, fixRuns.claimNext(T0).orElseThrow().id());

        assertEquals(
                List.of(runId),
                fixRuns.runningSince(T0.plusSeconds(1)).stream()
                        .map(FixRunRow::id)
                        .toList());
        assertEquals(List.of(), fixRuns.runningSince(T0));

        fixRuns.requeue(runId, T0.plusSeconds(5));

        FixRunRow requeued = fixRuns.findById(s.projectId(), runId).orElseThrow();
        assertEquals(Status.QUEUED, requeued.status());
        assertEquals(runId, fixRuns.claimNext(T0.plusSeconds(6)).orElseThrow().id());
    }

    @Test
    void theCaseReadsItsNewestRunAndAFailedRunKeepsItsReason() {
        emptyTheQueue();
        FixSeeds.Seeded s = seed("fix-run-latest");
        String older = fixRuns.skip(s.projectId(), s.caseId(), s.reportId(), Reason.NO_REPO, List.of(), T0)
                .orElseThrow();
        String newer = fixRuns.enqueue(
                        s.projectId(), s.caseId(), FixSeeds.rerun(trigger, s), List.of(), T0.plusSeconds(1))
                .orElseThrow();
        assertEquals(newer, fixRuns.claimNext(T0.plusSeconds(1)).orElseThrow().id());
        fixRuns.fail(newer, "GIT.BRANCH_MOVED", T0.plusSeconds(2));

        FixRunRow latest = fixRuns.latestByCase(s.projectId(), s.caseId()).orElseThrow();
        assertEquals(
                Reason.NO_REPO,
                fixRuns.findById(s.projectId(), older).orElseThrow().reason());

        assertEquals(
                List.of(newer, Status.FAILED, "GIT.BRANCH_MOVED"),
                List.of(latest.id(), latest.status(), latest.reason()));
    }

    /** A finished run whose trail line then failed must keep how it ended, not trip the outcome check. */
    @Test
    void aRunThatAlreadyFinishedIsNotFailedAfterwards() {
        FixSeeds.Seeded s = seed("fix-run-fail-after-finish");
        String runId = fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of(), T0)
                .orElseThrow();
        fixRuns.finish(runId, new Finish(Outcome.NO_CHANGE, null, null, null, List.of(), 0), T0);

        fixRuns.fail(runId, "AGENT_RUN.RUN_FAILED", T0.plusSeconds(1));

        FixRunRow run = fixRuns.findById(s.projectId(), runId).orElseThrow();
        assertEquals(List.of(Status.DONE, Outcome.NO_CHANGE), List.of(run.status(), run.outcome()));
    }

    @Test
    void aDoneRunWithoutAnOutcomeIsRefused() {
        FixSeeds.Seeded s = seed("fix-run-outcome-check");
        String runId = fixRuns.enqueue(s.projectId(), s.caseId(), s.reportId(), List.of(), T0)
                .orElseThrow();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE fix_run SET status = 'done' WHERE id = :id")
                        .param("id", runId)
                        .update());
    }
}
