// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentRunDtos.RunStepsView;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.RunSummary;
import ai.tessary.fix.FixDtos.FixCheckView;
import ai.tessary.fix.FixDtos.FixPrView;
import ai.tessary.fix.FixDtos.FixRunView;
import ai.tessary.fix.FixDtos.FixView;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaRunView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What the case page reads about a fix. The bugs: a case with no fix drawing an empty fix card, a run linked to the
 * wrong pull request, a run bar that shows a finished run's last step as live, a failed run that reads as done, and
 * one project reading another's steps.
 */
@ExtendWith(MockitoExtension.class)
class FixReadsTest {

    @Mock
    private FixRunRepository fixRuns;

    @Mock
    private FixPrRepository prs;

    @Mock
    private AgentRunRepository runs;

    private FixReads reads() {
        return new FixReads(fixRuns, prs, runs);
    }

    private static FixRunRow run(String status, @Nullable String fixPrId) {
        return new FixRunRow(
                "run-1",
                "proj-1",
                fixPrId,
                "case-1",
                "rpt-1",
                status,
                FixRunRow.Status.DONE.equals(status) ? FixRunRow.Outcome.COMMIT_ADDED : null,
                null,
                "It retries.",
                List.of("code:src/agent.py"),
                List.of(new FixCheck("pytest", FixCheck.PASSED, null)),
                1,
                "2026-11-19T12:00:00Z",
                "2026-11-19T12:05:00Z");
    }

    private static FixPrRow pr(String id, int number) {
        return new FixPrRow(
                id,
                "proj-1",
                "case-1",
                "tessary/fix",
                number,
                "https://pr/" + number,
                "open",
                "h",
                List.of("code:src/agent.py"),
                "t1",
                "t2");
    }

    private static RunSummary summary() {
        return new RunSummary(
                "ar-1",
                AgentRunRepository.Lane.FIX,
                "run-1",
                1,
                "2026-11-19T12:01:00Z",
                null,
                null,
                null,
                3,
                "Editing src/agent.py",
                "Read src/agent.py",
                900L,
                "Adding a retry",
                800L,
                "sess-1");
    }

    @Test
    void aCaseNoFixStepLookedAtHasNoFixBlock() {
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of());
        when(fixRuns.latestByCase("proj-1", "case-1")).thenReturn(Optional.empty());

        assertNull(reads().forCase("proj-1", "case-1"));
    }

    @Test
    void theFixBlockShowsTheNewestPullRequestAndTheNewestRunLinkedToItsOwnPullRequest() {
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of(pr("pr-2", 42), pr("pr-1", 41)));
        when(fixRuns.latestByCase("proj-1", "case-1")).thenReturn(Optional.of(run(FixRunRow.Status.RUNNING, "pr-1")));
        when(runs.latestBySubjects("proj-1", AgentRunRepository.Lane.FIX, List.of("run-1")))
                .thenReturn(Map.of("run-1", summary()));

        FixView view = reads().forCase("proj-1", "case-1");

        assertEquals(
                new FixView(
                        new FixPrView(
                                42, "https://pr/42", "open", "tessary/fix", List.of("code:src/agent.py"), "t1", "t2"),
                        new FixRunView(
                                "run-1",
                                "running",
                                null,
                                null,
                                "It retries.",
                                "https://pr/41",
                                List.of("code:src/agent.py"),
                                List.of(new FixCheckView("pytest", "passed", null)),
                                1,
                                "2026-11-19T12:00:00Z",
                                "2026-11-19T12:05:00Z",
                                new RcaRunView(
                                        RcaRunView.RUNNING,
                                        "2026-11-19T12:00:00Z",
                                        "ar-1",
                                        "2026-11-19T12:01:00Z",
                                        null,
                                        3,
                                        "Editing src/agent.py",
                                        "Read src/agent.py",
                                        900L,
                                        "Adding a retry",
                                        800L,
                                        null))),
                view);
    }

    @Test
    void aSkippedRunWithNoPullRequestAndNoAgentRunHasNoLinkAndNoRunBar() {
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of());
        when(fixRuns.latestByCase("proj-1", "case-1")).thenReturn(Optional.of(run(FixRunRow.Status.DONE, null)));
        when(runs.latestBySubjects("proj-1", AgentRunRepository.Lane.FIX, List.of("run-1")))
                .thenReturn(Map.of());

        FixView view = Objects.requireNonNull(reads().forCase("proj-1", "case-1"));
        FixRunView latest = Objects.requireNonNull(view.latestRun());

        assertNull(view.pr());
        assertNull(latest.prUrl());
        assertNull(latest.run());
    }

    @ParameterizedTest
    @CsvSource({"queued, queued", "done, done", "failed, stopped"})
    void aRunThatIsNotRunningShowsNoLiveStep(String status, String state) {
        assertEquals(
                new RcaRunView(
                        state,
                        "2026-11-19T12:00:00Z",
                        "ar-1",
                        "2026-11-19T12:01:00Z",
                        null,
                        3,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                FixReads.runBar(run(status, null), summary()));
    }

    @Test
    void stepsOfARunReadItsNewestAgentRun() {
        when(fixRuns.findById("proj-1", "run-1")).thenReturn(Optional.of(run(FixRunRow.Status.RUNNING, null)));
        when(runs.latestBySubjects("proj-1", AgentRunRepository.Lane.FIX, List.of("run-1")))
                .thenReturn(Map.of("run-1", summary()));
        when(runs.steps("proj-1", "ar-1", 2)).thenReturn(new AgentRunRepository.RunSteps("ar-1", 3, List.of()));

        assertEquals(new RunStepsView("ar-1", 3, List.of()), reads().steps("proj-1", "run-1", 2));
    }

    @Test
    void stepsOfARunOutsideTheProjectAreNotFound() {
        when(fixRuns.findById("proj-2", "run-1")).thenReturn(Optional.empty());

        TessaryException e = assertThrows(TessaryException.class, () -> reads().steps("proj-2", "run-1", 0));

        assertEquals(CommonError.NOT_FOUND, e.error());
    }

    @Test
    void stepsOfARunTheWorkerNeverStartedAreNotFound() {
        when(fixRuns.findById("proj-1", "run-1")).thenReturn(Optional.of(run(FixRunRow.Status.QUEUED, null)));
        when(runs.latestBySubjects("proj-1", AgentRunRepository.Lane.FIX, List.of("run-1")))
                .thenReturn(Map.of());

        TessaryException e = assertThrows(TessaryException.class, () -> reads().steps("proj-1", "run-1", 0));

        assertEquals(CommonError.NOT_FOUND, e.error());
    }

    @Test
    void aNegativeStepCursorIsRefused() {
        TessaryException e = assertThrows(TessaryException.class, () -> reads().steps("proj-1", "run-1", -1));

        assertEquals(CommonError.VALIDATION_FAILED, e.error());
    }
}
