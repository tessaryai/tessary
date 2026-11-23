// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.SessionEvent;
import ai.tessary.agentrun.StepWords;
import ai.tessary.config.FixProperties;
import ai.tessary.fix.FixAgent.FixAnswer;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.testsupport.Props;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The fix session as the launcher receives it, and the answer read back. The bugs: a fix run on another lane's model
 * or booked on another subject, an MCP key minted for an agent that needs none, a clone at the wrong start, a prompt
 * that tells the agent a rerun starts fresh when it starts on the pull request's branch, and an answer read wrongly.
 */
@ExtendWith(MockitoExtension.class)
class FixAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AgentSpanTelemetry.AgentUsage USAGE = new AgentSpanTelemetry.AgentUsage(1, 2, 0, 0, null);

    private static final FixableCause RETRY = FixableCause.of(List.of(new Cause(
                    "The search tool is never retried",
                    Cause.HIGH,
                    "standing",
                    "code",
                    "Search calls fail once and stop.",
                    "Every timeout became a failed answer.",
                    "Retry the search call.",
                    new Attribution(null, "src/agent.py:12", null, "search(q)"),
                    List.of("tr-1"),
                    List.of(),
                    1,
                    1)))
            .get(0);

    @Mock
    private AgentSessionRunner runner;

    @Mock
    private AgentSessionRunner.StepSink steps;

    private FixAgent agent() {
        return new FixAgent(Props.of(FixProperties.class, "timeout-ms", 1_800_000, "max-turns", 80), runner, MAPPER);
    }

    private static FixAgent.Request request(AgentSessionClient.Clone repo, String branch, List<String> addressed) {
        return new FixAgent.Request(
                "proj-1", "run-1", "C-7", "Search fails on timeouts.", repo, branch, List.of(RETRY), addressed);
    }

    @Test
    void aFirstRunStartsOnTheDefaultBranchOnTheFixLaneAndIsBookedOnTheRun() {
        AgentSessionClient.Clone repo = new AgentSessionClient.Clone("https://clone", "base", null);
        SessionEvent.Patch patch = new SessionEvent.Patch(9, "base", List.of(), List.of(), null);
        when(runner.run(any(), any()))
                .thenReturn(new AgentSessionRunner.Answer(
                        "{\"outcome\":\"fixed\",\"title\":\" Retry search \",\"why\":\"It retries.\","
                                + "\"causes_addressed\":[\"code:src/agent.py\"],"
                                + "\"checks\":[{\"command\":\"pytest\",\"result\":\"passed\",\"note\":null}],"
                                + "\"no_fix_reason\":null}",
                        USAGE,
                        4,
                        patch));

        FixAgent.Result result = agent().run(request(repo, "main", List.of()), steps);

        ArgumentCaptor<AgentSessionRunner.Task> task = ArgumentCaptor.forClass(AgentSessionRunner.Task.class);
        verify(runner).run(task.capture(), any());
        assertEquals(
                new AgentSessionRunner.Task(
                        "proj-1",
                        ModelLane.FIX,
                        new LlmUsageAccountant.Subject("fix_run", "run-1"),
                        FixAgent.SYSTEM_PROMPT,
                        Map.of(
                                "causes.md",
                                """
                                # Causes to fix

                                ## `code:src/agent.py`

                                - title: The search tool is never retried
                                - file: `src/agent.py`
                                - what happens: Search calls fail once and stop.
                                - how it caused the failures: Every timeout became a failed answer.
                                - what to fix: Retry the search call.
                                - excerpt:

                                ```
                                search(q)
                                ```
                                """,
                                "report.md",
                                "# RCA report for case C-7\n\nSearch fails on timeouts.\n"),
                        List.of(),
                        repo,
                        """
                        ## This run

                        - case: C-7
                        - causes to fix: `code:src/agent.py`
                        - time budget: 24 minutes

                        `./repo/` is a clone of the repo at the head of its default branch, `main`.
                        """,
                        FixAgent.JSON_SCHEMA,
                        Duration.ofMinutes(30),
                        78,
                        new StepWords.Context(Map.of(), Map.of())),
                task.getValue());
        assertEquals(
                new FixAgent.Result(
                        new FixAnswer(
                                true,
                                "Retry search",
                                "It retries.",
                                List.of("code:src/agent.py"),
                                List.of(new FixCheck("pytest", "passed", null)),
                                null),
                        patch),
                result);
    }

    @Test
    void aRerunStartsOnThePullRequestBranchAndIsToldWhatItsCommitsFix() {
        AgentSessionClient.Clone repo = AgentSessionClient.Clone.atBranch("https://clone", "tessary/fix-c7");
        when(runner.run(any(), any()))
                .thenReturn(new AgentSessionRunner.Answer(
                        "{\"outcome\":\"no_fix\",\"title\":\"\",\"why\":\"\",\"causes_addressed\":[],"
                                + "\"checks\":[{\"command\":\"make test\",\"result\":\"not_run\","
                                + "\"note\":\"needs a database\"}],\"no_fix_reason\":\"The fix needs a schema change.\"}",
                        USAGE,
                        2));

        FixAgent.Result result = agent().run(request(repo, "tessary/fix-c7", List.of("prompt:prompts/a.md")), steps);

        ArgumentCaptor<AgentSessionRunner.Task> task = ArgumentCaptor.forClass(AgentSessionRunner.Task.class);
        verify(runner).run(task.capture(), any());
        assertEquals("""
                ## This run

                - case: C-7
                - causes to fix: `code:src/agent.py`
                - time budget: 24 minutes

                `./repo/` is a clone of the repo at the head of `tessary/fix-c7`, the open pull request for this
                case. Its commits fix `prompt:prompts/a.md`; build on them, keep them, and fix only the causes above.
                """, task.getValue().prompt());
        assertEquals(
                new FixAgent.Result(
                        new FixAnswer(
                                false,
                                "",
                                "",
                                List.of(),
                                List.of(new FixCheck("make test", "not_run", "needs a database")),
                                "The fix needs a schema change."),
                        null),
                result);
    }

    @Test
    void aReportWithNoSummarySaysSoAndACauseWithoutDetailListsOnlyWhatItHas() {
        Cause bare = new Cause(
                "Bare",
                Cause.HIGH,
                null,
                "tool",
                null,
                " ",
                null,
                new Attribution(null, "t.py", null, " "),
                List.of(),
                List.of(),
                0,
                null);
        FixAgent.Request req = new FixAgent.Request(
                "proj-1",
                "run-1",
                "C-7",
                null,
                new AgentSessionClient.Clone("u", "base", null),
                "main",
                FixableCause.of(List.of(bare)),
                List.of());

        assertEquals("# RCA report for case C-7\n\nThe report has no summary.\n", FixAgent.reportFile(req));
        assertEquals(
                "# Causes to fix\n\n## `tool:t.py`\n\n- title: Bare\n- file: `t.py`\n",
                FixAgent.causesFile(req.causes()));
    }

    @Test
    void theAgentIsToldFourFifthsOfItsTimeAndNeverLessThanAMinute() {
        assertEquals(24, FixAgent.timeBudgetMinutes(1_800_000));
        assertEquals(1, FixAgent.timeBudgetMinutes(10_000));
    }
}
