// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.SessionEvent;
import ai.tessary.agentrun.SessionEvent.PatchFile;
import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.config.AlertProperties;
import ai.tessary.fix.FixAgent.FixAnswer;
import ai.tessary.fix.FixDestination.Change;
import ai.tessary.fix.FixDestination.Delivery;
import ai.tessary.fix.FixDestination.Start;
import ai.tessary.fix.FixRunRepository.Finish;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.fix.FixRunRow.Outcome;
import ai.tessary.fix.FixRunRow.Reason;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.testsupport.Props;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * One fix run from report to delivery, with the agent stubbed and the destination scripted. The bugs: a pull request
 * opened for a change the agent declined or whose check failed, a patch Tessary may not write delivered, a declined
 * gate that still runs the agent, a commit that claims causes the agent did not fix, a branch that moved twice treated
 * as delivered, and a delivery with no line on the case's trail.
 */
@ExtendWith(MockitoExtension.class)
class FixStepTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private static final String CAUSES = "[{\"title\":\"Search is never retried\",\"confidence\":\"high\","
            + "\"type\":\"code\",\"attribution\":{\"path\":\"src/agent.py\"},\"evidence_trace_ids\":[\"t1\"]},"
            + "{\"title\":\"The prompt asks for prose\",\"confidence\":\"high\",\"type\":\"prompt\","
            + "\"attribution\":{\"path\":\"prompts/system.md\"}}]";

    private static final FixRunRow RUN = new FixRunRow(
            "run-1", "proj-1", null, "case-1", "rpt-1", "running", null, null, null, List.of(), List.of(), 0, "t0",
            "t0");

    private static final AgentSessionClient.Clone REPO = new AgentSessionClient.Clone("https://clone", "base", null);

    private static final PatchFile EDIT = new PatchFile("src/agent.py", "100644", "cmV0cnkoKQ==");

    @Mock
    private FixRunRepository fixRuns;

    @Mock
    private RcaReportRepository reports;

    @Mock
    private CaseRepository cases;

    @Mock
    private CaseEventRepository events;

    @Mock
    private ProjectRepository projects;

    @Mock
    private OrganizationRepository orgs;

    @Mock
    private FixAgent agent;

    @Mock
    private AgentSessionRunner.StepSink steps;

    private final ScriptedDestination destination = new ScriptedDestination();

    /** Answers {@code start} with one result and each {@code deliver} with the next scripted delivery. */
    private static final class ScriptedDestination implements FixDestination {
        Start start = new Start.Stop(Outcome.SKIPPED, Reason.DECLINED, null);
        final Deque<Delivery> deliveries = new ArrayDeque<>();
        final List<Change> changes = new ArrayList<>();
        List<FixableCause> startedWith = List.of();

        @Override
        public @Nullable String blocked(String projectId) {
            throw new AssertionError("the step never asks whether the destination is blocked");
        }

        @Override
        public Start start(String projectId, String caseId, List<FixableCause> causes) {
            startedWith = causes;
            return start;
        }

        @Override
        public Delivery deliver(Start.Go go, Change change) {
            changes.add(change);
            return deliveries.removeFirst();
        }
    }

    private FixStep step(String appBaseUrl) {
        return new FixStep(
                fixRuns,
                reports,
                cases,
                events,
                projects,
                orgs,
                Props.of(AlertProperties.class, "app-base-url", appBaseUrl),
                destination,
                agent,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seedReportAndCase() {
        when(reports.findById("proj-1", "rpt-1")).thenReturn(Optional.of(report()));
        when(cases.findById("proj-1", "case-1")).thenReturn(Optional.of(caseRow()));
    }

    private static RcaReportRow report() {
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
                "Search fails on timeouts.",
                null,
                null,
                CAUSES,
                null,
                "agentic",
                true,
                null,
                "t",
                "t");
    }

    private static CaseRow caseRow() {
        return new CaseRow(
                "case-1",
                7L,
                CaseRow.Detector.TOOL_ERROR,
                "tool",
                "search",
                "Search",
                null,
                "error_rate",
                1L,
                "fnd-1",
                CaseRow.State.OPEN,
                null,
                "Search fails more often.",
                "basis",
                0.5,
                "t",
                null,
                null,
                null,
                "t",
                "t",
                null,
                null,
                null,
                null,
                null);
    }

    private List<FixableCause> causes() {
        return FixableCause.of(ai.tessary.rca.RcaDtos.causesOf(new ObjectMapper(), "metric_movement", CAUSES, null));
    }

    private Start.Go go(@Nullable FixPrRow open) {
        return new Start.Go("proj-1", "case-1", REPO, "main", causes(), open);
    }

    private static FixAnswer fixed(List<String> addressed, FixCheck... checks) {
        return new FixAnswer(true, "Retry search", "It retries.", addressed, List.of(checks), null);
    }

    private static SessionEvent.Patch patch(PatchFile... files) {
        return new SessionEvent.Patch(5, "base", List.of(files), List.of(), null);
    }

    @Test
    void aStopFromTheDestinationFinishesTheRunWithoutAnAgent() {
        seedReportAndCase();
        destination.start = new Start.Stop(Outcome.NO_CHANGE, null, "pr-1");

        step("").run(RUN, steps);

        assertEquals(causes(), destination.startedWith);
        verify(fixRuns).finish("run-1", new Finish(Outcome.NO_CHANGE, null, null, "pr-1", List.of(), 0), NOW);
        verify(agent, never()).run(any(), any());
    }

    @Test
    void anAgentThatDeclinesEndsTheRunNoFixInItsOwnWords() {
        seedReportAndCase();
        destination.start = go(null);
        FixCheck notRun = new FixCheck("make test", FixCheck.NOT_RUN, "needs a database");
        when(agent.run(any(), any()))
                .thenReturn(new FixAgent.Result(
                        new FixAnswer(false, "", "", List.of(), List.of(notRun), "It needs a schema change."), null));

        step("").run(RUN, steps);

        verify(fixRuns).recordCauses("run-1", List.of("code:src/agent.py", "prompt:prompts/system.md"), NOW);
        verify(fixRuns)
                .finish(
                        "run-1",
                        new Finish(
                                Outcome.NO_FIX,
                                Reason.AGENT_DECLINED,
                                "It needs a schema change.",
                                null,
                                List.of(notRun),
                                0),
                        NOW);
        assertEquals(List.of(), destination.changes);
    }

    @Test
    void aCheckTheChangeFailsMeansNoPullRequest() {
        seedReportAndCase();
        destination.start = go(null);
        FixCheck passed = new FixCheck("ruff", FixCheck.PASSED, null);
        FixCheck failed = new FixCheck("pytest", FixCheck.FAILED, "test_search fails");
        when(agent.run(any(), any()))
                .thenReturn(new FixAgent.Result(fixed(List.of("code:src/agent.py"), passed, failed), patch(EDIT)));

        step("").run(RUN, steps);

        verify(fixRuns)
                .finish(
                        "run-1",
                        new Finish(Outcome.NO_FIX, Reason.CHECK_FAILED, "pytest", null, List.of(passed, failed), 0),
                        NOW);
        assertEquals(List.of(), destination.changes);
    }

    @Test
    void aPatchTessaryMayNotWriteIsNeverDelivered() {
        seedReportAndCase();
        destination.start = go(null);
        PatchFile hook = new PatchFile(".git/hooks/pre-commit", "100755", "eA==");
        when(agent.run(any(), any()))
                .thenReturn(new FixAgent.Result(fixed(List.of("code:src/agent.py")), patch(EDIT, hook)));

        step("").run(RUN, steps);

        verify(fixRuns)
                .finish(
                        "run-1",
                        new Finish(Outcome.NO_FIX, Reason.PATCH_REFUSED, "It retries.", null, List.of(), 0),
                        NOW);
        assertEquals(List.of(), destination.changes);
    }

    @Test
    void aDeliveredFixRecordsThePullRequestAndAddsALineToTheCase() {
        seedReportAndCase();
        when(projects.findById("proj-1"))
                .thenReturn(Optional.of(
                        new Project("proj-1", "org-1", "agent", "Agent", null, "t", null, null, true, null)));
        when(orgs.findById("org-1"))
                .thenReturn(Optional.of(new Organization("org-1", null, "acme", "Acme", "t", null, null)));
        destination.start = go(null);
        destination.deliveries.add(new Delivery.Delivered(Outcome.PR_OPENED, "pr-1", 41, "https://pr/41"));
        FixAnswer answer =
                fixed(List.of("code:src/agent.py", "made:up"), new FixCheck("pytest", FixCheck.PASSED, null));
        when(agent.run(any(), any())).thenReturn(new FixAgent.Result(answer, patch(EDIT)));

        step("https://app.example/").run(RUN, steps);

        List<FixableCause> addressed = List.of(causes().get(0));
        FixPrText.Facts facts = new FixPrText.Facts(
                "C-7",
                "Search fails more often.",
                "https://app.example/orgs/acme/projects/agent/cases/case-1",
                "Search fails on timeouts.",
                addressed,
                List.of(),
                answer);
        assertEquals(
                List.of(new Change(
                        List.of(EDIT),
                        "base",
                        List.of("code:src/agent.py"),
                        "Retry search",
                        FixPrText.body(facts),
                        FixPrText.commitMessage(facts))),
                destination.changes);
        verify(fixRuns)
                .finish("run-1", new Finish(Outcome.PR_OPENED, null, "It retries.", "pr-1", answer.checks(), 1), NOW);
        verify(events)
                .append(
                        "proj-1",
                        "case-1",
                        CaseEventRow.Kind.FIX_PR_OPENED,
                        null,
                        "Opened pull request #41 to fix 1 cause.",
                        null,
                        NOW);
    }

    @Test
    void anAgentThatNamesNoOfferedCauseIsTakenToFixEveryCauseItWasGiven() {
        seedReportAndCase();
        FixPrRow open = new FixPrRow(
                "pr-1",
                "proj-1",
                "case-1",
                "tessary/fix",
                41,
                "https://pr/41",
                "open",
                "h",
                List.of("tool:t.py"),
                "t",
                "t");
        destination.start = go(open);
        destination.deliveries.add(new Delivery.Moved());
        destination.deliveries.add(new Delivery.Delivered(Outcome.COMMIT_ADDED, "pr-1", 41, "https://pr/41"));
        when(agent.run(any(), any())).thenReturn(new FixAgent.Result(fixed(List.of()), patch(EDIT)));

        step("").run(RUN, steps);

        verify(agent, times(2)).run(any(), any());
        assertEquals(
                List.of("code:src/agent.py", "prompt:prompts/system.md"),
                destination.changes.get(1).causes());
        verify(events)
                .append(
                        "proj-1",
                        "case-1",
                        CaseEventRow.Kind.FIX_PR_UPDATED,
                        null,
                        "Added a commit to pull request #41 for 2 new causes.",
                        null,
                        NOW);
    }

    @Test
    void aBranchThatMovesTwiceFailsTheRunRatherThanLoop() {
        seedReportAndCase();
        destination.start = go(null);
        destination.deliveries.add(new Delivery.Moved());
        destination.deliveries.add(new Delivery.Moved());
        when(agent.run(any(), any())).thenReturn(new FixAgent.Result(fixed(List.of("code:src/agent.py")), patch(EDIT)));

        TessaryException e = assertThrows(TessaryException.class, () -> step("").run(RUN, steps));

        assertEquals(GitError.BRANCH_MOVED, e.error());
        verify(agent, times(2)).run(any(), any());
        verify(fixRuns, never()).finish(any(), any(), any());
    }
}
