// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static ai.tessary.agentrun.FakeAgentSessionClient.CUT;
import static ai.tessary.agentrun.FakeAgentSessionClient.end;
import static ai.tessary.agentrun.FakeAgentSessionClient.failure;
import static ai.tessary.agentrun.FakeAgentSessionClient.text;
import static ai.tessary.agentrun.FakeAgentSessionClient.toolEnd;
import static ai.tessary.agentrun.FakeAgentSessionClient.toolStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentSessionRunner} over a scripted session: which text is the answer, when it corrects the agent once, how it
 * stops at the deadline, and that every path stops the session and reads it to its close so the last reply is booked.
 */
class AgentSessionRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEADLINE = Duration.ofMinutes(30);
    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["verdict", "why"]
            }
            """;

    /** What the one-shot lane appended to every prompt: the schema instruction with the schema compacted. */
    private static final String INSTRUCTION = "\n\nRespond with ONLY a single JSON object that validates against this"
            + " JSON Schema. No prose, no markdown fence, no commentary before or after it.\n\n"
            + "{\"type\":\"object\",\"required\":[\"verdict\",\"why\"]}";

    private static final String RULING = "{\"verdict\":\"real\",\"why\":\"the answer cites no source\"}";
    private static final LlmUsageAccountant.Subject SUBJECT = new LlmUsageAccountant.Subject("finding", "f-1");
    private static final AgentSessionClient.McpServer MCP =
            new AgentSessionClient.McpServer("tessary-evals", "https://api.example/mcp", "tsy_a_secret");

    private final FakeAgentSessionClient client = new FakeAgentSessionClient();
    private final FakeAgentSessionClient.FakeDeadlines deadlines = new FakeAgentSessionClient.FakeDeadlines();
    private final List<String> sink = new ArrayList<>();

    private AgentSessionRunner runner() {
        return new AgentSessionRunner(
                client, MAPPER, Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC), deadlines);
    }

    private static AgentSessionRunner.Task task() {
        return new AgentSessionRunner.Task(
                "proj",
                ModelLane.TRIAGE,
                SUBJECT,
                "You rule on findings.",
                Map.of("finding.md", "the finding"),
                List.of(MCP),
                null,
                "Rule on finding f-1.",
                SCHEMA,
                DEADLINE,
                50,
                StepWords.Context.NONE);
    }

    private final AgentSessionRunner.StepSink recording = new AgentSessionRunner.StepSink() {
        @Override
        public void session(String sessionId) {
            sink.add("session " + sessionId);
        }

        @Override
        public void started(String callId, StepWords.Words w, long atMs) {
            sink.add("started " + callId + " " + w.doing());
        }

        @Override
        public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {
            sink.add("ended " + callId + " " + (ok ? w.done() : StepWords.failed(w)));
        }

        @Override
        public void note(String words, long atMs) {}
    };

    private AgentSessionRunner.Answer run() {
        return runner().run(task(), recording);
    }

    private TessaryException fails() {
        return assertThrows(TessaryException.class, this::run);
    }

    /**
     * A ruling parsed from "Let me look": a reply's answer is the text after its last tool call, not the narration
     * before it, even when the narration holds an object of its own. The session starts with the task's lane, subject,
     * prompt and tool cap, and the first message is the task with the schema instruction.
     */
    @Test
    void aRepliesAnswerIsTheTextAfterItsLastToolCallNotTheNarrationBeforeIt() {
        client.reply(
                text("Let me look at {\"verdict\":\"not_real\",\"why\":\"guess\"} first."),
                toolStart("c-1", null, "read", "{\"filePath\":\"/home/user/dossier/finding.md\"}"),
                toolEnd("c-1", true),
                text("Now I know. "),
                text(RULING.substring(0, 10)),
                text(RULING.substring(10)),
                end());

        AgentSessionRunner.Answer answer = run();

        assertEquals(
                new AgentSessionRunner.Answer(
                        RULING, new AgentSpanTelemetry.AgentUsage(10, 5, 0, 0, BigDecimal.ONE), 1),
                answer);
        assertEquals(
                List.of(new AgentSessionClient.Start(
                        "proj",
                        ModelLane.TRIAGE,
                        "You rule on findings.",
                        Map.of("finding.md", "the finding"),
                        List.of(MCP),
                        120_000L,
                        50,
                        SUBJECT,
                        null)),
                client.starts);
        assertEquals(List.of("Rule on finding f-1." + INSTRUCTION), client.sent);
        assertEquals(
                List.of(
                        "session s-1",
                        "started c-1 Reading the finding and its numbers",
                        "ended c-1 Read the finding and its numbers"),
                sink);
    }

    /** A rejected key retried as an empty reply: a provider failure ends the run with its kind, even after an agent error. */
    @Test
    void aProviderRejectedFailureEndsTheRunWithThatKindEvenWhenAnAgentErrorCameFirst() {
        client.reply(failure("agent"), failure("provider_rejected"), end(SessionEvent.EndReason.DONE, 0));

        TessaryException e = fails();

        assertEquals(AgentRunError.RUN_FAILED, e.error());
        assertEquals("provider_rejected", ((ProviderStop) e).kind());
        assertEquals(1, client.sent.size(), "a provider refusal gets no correction");
    }

    /** An endless correction loop: an answer missing a required key gets one correction naming the keys, then fails. */
    @Test
    void anAnswerMissingARequiredKeyGetsOneCorrectionNamingTheKeysThenFailsBadOutput() {
        client.reply(text("{\"verdict\":\"real\"}"), end()).reply(text("{\"verdict\":\"real\"}"), end());

        TessaryException e = fails();

        assertEquals(AgentRunError.BAD_OUTPUT, e.error());
        assertEquals(
                List.of(
                        "Rule on finding f-1." + INSTRUCTION,
                        "Your previous reply was missing why. Do NOT investigate further — you already have what you"
                                + " need. Reply with the COMPLETE JSON object and nothing else." + INSTRUCTION),
                client.sent);
    }

    /** A run that stops at its cap with no answer: a reply cut at the tool cap gets the no-tool-budget correction. */
    @Test
    void aReplyCutAtTheToolCapGetsTheNoToolBudgetCorrection() {
        client.reply(
                        toolStart("c-1", null, "bash", "{\"command\":\"ls\"}"),
                        toolEnd("c-1", true),
                        text("I still need to check"),
                        end(SessionEvent.EndReason.LIMIT, 5))
                .reply(text(RULING), end());

        assertEquals(RULING, run().json());
        assertEquals(
                "You have no tool budget left. Do NOT call any tool. Using only the evidence you have already read,"
                        + " reply now with the ruling JSON object and nothing else." + INSTRUCTION,
                client.sent.get(1));
    }

    /** Prose with no object after real work gets the unparseable correction. */
    @Test
    void proseWithNoObjectGetsTheUnparseableCorrection() {
        client.reply(text("It is real."), end()).reply(text(RULING), end());

        assertEquals(RULING, run().json());
        assertEquals(
                "Your previous reply could not be parsed as a single JSON object. Do NOT investigate further — you"
                        + " already have what you need. Reply with the JSON object and nothing else: no prose, no"
                        + " markdown fence, nothing before or after it. Keep every string field short enough that the"
                        + " whole object fits in one reply." + INSTRUCTION,
                client.sent.get(1));
    }

    /** The empty completion some models return: an empty reply with no tool calls re-sends the task once. */
    @Test
    void anEmptyReplyWithNoToolCallsReSendsTheTaskOnce() {
        client.reply(end(SessionEvent.EndReason.DONE, 0)).reply(text(RULING), end());

        AgentSessionRunner.Answer answer = run();

        assertEquals(RULING, answer.json());
        assertEquals(new AgentSpanTelemetry.AgentUsage(20, 5, 0, 0, BigDecimal.TWO), answer.usage());
        assertEquals(List.of("Rule on finding f-1." + INSTRUCTION, "Rule on finding f-1." + INSTRUCTION), client.sent);
    }

    /** A session that runs past the lease: the deadline aborts and stops the session and fails TIMED_OUT. */
    @Test
    void theDeadlineAbortsAndStopsTheSessionAndFailsTimedOut() {
        client.reply(
                toolStart("c-1", null, "bash", "{\"command\":\"sleep 9999\"}"),
                new FakeAgentSessionClient.Do(() -> deadlines.fire(DEADLINE)));

        TessaryException e = fails();

        assertEquals(AgentRunError.TIMED_OUT, e.error());
        assertEquals(List.of("send", "events 0", "abort", "stop"), client.calls.subList(0, 4));
        assertTrue(client.closedRead, "the session was read to its close");
    }

    /** A reply's spend lost: the session is stopped and drained to closed on every path. */
    @Test
    void theSessionIsStoppedAndDrainedToClosedOnEveryPath() {
        client.reply(text(RULING), end());
        run();
        assertEquals(List.of("send", "events 0", "close", "stop", "events 2", "close"), client.calls);
        assertTrue(client.closedRead);

        FakeAgentSessionClient failing =
                new FakeAgentSessionClient().reply(text("no"), end()).reply(text("still no"), end());
        assertThrows(
                TessaryException.class,
                () -> new AgentSessionRunner(failing, MAPPER, Clock.systemUTC(), deadlines).run(task(), recording));
        assertEquals(
                List.of("stop", "events 4", "close"),
                failing.calls.subList(failing.calls.size() - 3, failing.calls.size()));
        assertTrue(failing.closedRead);
        assertTrue(deadlines.scheduled.stream().allMatch(s -> s.cancelled().get()), "no timer outlives its run");
    }

    /**
     * Duplicate steps after a reconnect: a stream that ends without closed resumes from the last seq, after a pause
     * only when it read nothing, and no step is written twice.
     */
    @Test
    void aStreamThatEndsWithoutClosedResumesFromTheLastSeqAndNoStepIsWrittenTwice() {
        client.reply(
                CUT,
                toolStart("c-1", null, "read", "{\"filePath\":\"/home/user/repo/a.py\"}"),
                CUT,
                toolEnd("c-1", true),
                text(RULING),
                end());

        assertEquals(RULING, run().json());
        assertEquals(
                List.of("events 0", "events 0", "events 1"),
                client.calls.stream()
                        .filter(c -> c.startsWith("events"))
                        .toList()
                        .subList(0, 3));
        assertEquals(List.of(Duration.ofSeconds(1)), deadlines.pauses);
        assertEquals(List.of("session s-1", "started c-1 Reading a.py", "ended c-1 Read a.py"), sink);
    }

    /** A session closed under a running reply fails the run instead of waiting on it. */
    @Test
    void aSessionClosedBeforeItsReplyEndedFailsTheRun() {
        client.reply(text("thinking"), FakeAgentSessionClient.closed(SessionEvent.CloseReason.FAILED));

        assertEquals(AgentRunError.RUN_FAILED, fails().error());
    }

    /** A run that never times out, or one stopped by a timer it cancelled: the real timers fire once and cancel. */
    @Test
    void theVirtualDeadlinesFireAfterTheirDelayAndACancelledOneDoesNot() throws InterruptedException {
        AgentSessionRunner.Deadlines real = AgentSessionRunner.Deadlines.VIRTUAL;
        CountDownLatch fired = new CountDownLatch(1);
        AgentSessionRunner.Deadlines.Timer done = real.after(Duration.ofMillis(1), fired::countDown);
        assertTrue(fired.await(10, TimeUnit.SECONDS), "the timer fired");
        done.cancel();

        AtomicBoolean ran = new AtomicBoolean();
        AgentSessionRunner.Deadlines.Timer cancelled = real.after(Duration.ofHours(1), () -> ran.set(true));
        cancelled.cancel();
        cancelled.cancel();
        real.pause(Duration.ZERO);

        assertFalse(ran.get());
    }

    /**
     * A run left holding its lease: an abort and a stop that fail at the deadline are logged, the run still fails
     * TIMED_OUT, and a stop that fails afterwards still reads the session for its close.
     */
    @Test
    void anAbortAndStopThatFailAtTheDeadlineAreLoggedAndTheRunStillTimesOut() {
        client.abortFails = new TessaryException(AgentRunError.LAUNCHER_UNAVAILABLE, "down");
        client.stopFailures.add(new IllegalStateException("socket closed"));
        client.stopFailures.add(new IllegalStateException("socket closed"));
        client.reply(
                toolStart("c-1", null, "bash", "{\"command\":\"sleep 9999\"}"),
                new FakeAgentSessionClient.Do(() -> deadlines.fire(DEADLINE)));

        assertEquals(AgentRunError.TIMED_OUT, fails().error());
        assertEquals(
                List.of("stop", "events 1", "close", "events 1", "close", "events 1", "close", "events 1", "close"),
                client.calls.subList(client.calls.size() - 9, client.calls.size()));
    }

    /**
     * A run that hangs on a session deaf to its stop: the drain timer set at the deadline closes the open stream, so
     * no later event of that reply becomes a step.
     */
    @Test
    void aSessionThatIgnoresItsStopHasItsOpenStreamClosedWhenTheDrainRunsOut() {
        client.silent = true;
        client.reply(
                toolStart("c-1", null, "bash", "{\"command\":\"sleep 9999\"}"),
                new FakeAgentSessionClient.Do(() -> {
                    deadlines.fire(DEADLINE);
                    deadlines.fire(AgentSessionRunner.DRAIN);
                }),
                toolStart("c-2", null, "bash", "{\"command\":\"ls\"}"),
                end());

        assertEquals(AgentRunError.TIMED_OUT, fails().error());
        assertEquals(List.of("session s-1", "started c-1 Running a command"), sink);
        assertFalse(client.closedRead);
    }

    /** A run that never returns: a session that never closes after the stop is given up when the drain runs out. */
    @Test
    void aSessionThatNeverClosesIsGivenUpWhenTheDrainRunsOut() {
        client.silent = true;
        deadlines.immediate.add(AgentSessionRunner.DRAIN);
        client.reply(text(RULING), end());

        assertEquals(RULING, run().json());
        assertEquals("stop", client.calls.getLast(), "no read after the drain ran out");
    }

    /** A session started for a task that can never be checked: a schema that does not parse fails before the start. */
    @Test
    void aTaskWhoseSchemaDoesNotParseFailsBeforeAnySessionStarts() {
        AgentSessionRunner.Task t = task();
        AgentSessionRunner.Task broken = new AgentSessionRunner.Task(
                t.projectId(),
                t.lane(),
                t.subject(),
                t.systemPrompt(),
                t.files(),
                t.mcp(),
                t.repo(),
                t.prompt(),
                "{not a schema",
                t.deadline(),
                t.maxToolCalls(),
                t.words());

        TessaryException e = assertThrows(TessaryException.class, () -> runner().run(broken, recording));

        assertEquals(CommonError.VALIDATION_FAILED, e.error());
        assertEquals(List.of(), client.starts);
    }

    /**
     * A run lost to its step list: a sink that throws on the session, on each step and on a note is logged and the run
     * answers.
     */
    @Test
    void aStepSinkThatThrowsIsLoggedAndTheRunStillAnswers() {
        AgentSessionRunner.StepSink throwing = new AgentSessionRunner.StepSink() {
            @Override
            public void session(String sessionId) {
                throw new IllegalStateException("no run row");
            }

            @Override
            public void started(String callId, StepWords.Words w, long atMs) {
                throw new IllegalStateException("no run row");
            }

            @Override
            public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {
                throw new IllegalStateException("no run row");
            }

            @Override
            public void note(String words, long atMs) {
                throw new IllegalStateException("no run row");
            }
        };
        client.reply(
                text("Let me read it. "),
                toolStart("c-1", null, "read", "{\"filePath\":\"/home/user/repo/a.py\"}"),
                toolEnd("c-1", true),
                text(RULING),
                end());

        AgentSessionRunner.Answer answer = runner().run(task(), throwing);

        assertEquals(RULING, answer.json());
        assertEquals(1, answer.steps());
    }

    /** A run that waits on a session that took nothing: a message the session refuses fails RUN_FAILED at once. */
    @Test
    void aMessageTheSessionRefusesFailsRunFailed() {
        client.refuse = AgentSessionClient.SendOutcome.BUSY;

        assertEquals(AgentRunError.RUN_FAILED, fails().error());
        assertEquals(List.of(), client.sent);
    }

    /** A timed-out run blamed on the session: a deadline that passes before the task is sent fails TIMED_OUT. */
    @Test
    void aDeadlineThatPassesBeforeTheTaskIsSentFailsTimedOut() {
        AgentSessionRunner.StepSink late = new AgentSessionRunner.StepSink() {
            @Override
            public void session(String sessionId) {
                deadlines.fire(DEADLINE);
            }

            @Override
            public void started(String callId, StepWords.Words w, long atMs) {}

            @Override
            public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {}

            @Override
            public void note(String words, long atMs) {}
        };

        TessaryException e = assertThrows(TessaryException.class, () -> runner().run(task(), late));

        assertEquals(AgentRunError.TIMED_OUT, e.error());
        assertEquals(List.of(), client.sent);
        assertTrue(client.closedRead);
    }

    /** A run that polls a dead session until its lease ends: events that stop give up after four empty reads. */
    @Test
    void aSessionWhoseEventsStopFailsRunFailedAfterFourEmptyReads() {
        TessaryException e = fails();

        assertEquals(AgentRunError.RUN_FAILED, e.error());
        assertEquals(
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)),
                deadlines.pauses);
    }

    /** A worker that cannot shut down: an interrupted pause fails INTERRUPTED and keeps the thread's interrupt. */
    @Test
    void anInterruptedPauseFailsInterruptedAndKeepsTheInterrupt() {
        deadlines.interruptPauses = true;

        TessaryException e = fails();

        assertEquals(CommonError.INTERRUPTED, e.error());
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
    }

    /** A run failed after its answer: a drain that cannot reach the launcher is logged and the answer returns. */
    @Test
    void aDrainThatCannotReachTheLauncherIsLoggedAndTheAnswerStillReturns() {
        client.streamFailures.put(1, new TessaryException(AgentRunError.LAUNCHER_UNAVAILABLE, "down"));
        client.reply(text(RULING), end());

        assertEquals(RULING, run().json());
        assertEquals(
                List.of("stop", "events 2", "close"),
                client.calls.subList(client.calls.size() - 3, client.calls.size()));
        assertFalse(client.closedRead, "the drain gave up at the failed read");
    }

    /** A run failed after its answer: a stop the launcher refuses after the answer is logged and the answer returns. */
    @Test
    void aStopTheLauncherRefusesAfterTheAnswerIsLoggedAndTheAnswerStillReturns() {
        client.stopFailures.add(new TessaryException(AgentRunError.LAUNCHER_UNAVAILABLE, "down"));
        client.reply(text(RULING), end());

        assertEquals(RULING, run().json());
        assertEquals("stop", client.calls.get(client.calls.size() - 9), "the drain still reads after a failed stop");
    }

    /** A drain that reads past its time: a drain that runs out while its stream opens closes it and reads nothing. */
    @Test
    void aDrainThatRunsOutWhileItsStreamOpensClosesItAndReadsNothing() {
        client.whileOpening.put(1, () -> deadlines.fire(AgentSessionRunner.DRAIN));
        client.reply(text(RULING), end());

        assertEquals(RULING, run().json());
        assertEquals(
                List.of("stop", "events 2", "close"),
                client.calls.subList(client.calls.size() - 3, client.calls.size()));
        assertFalse(client.closedRead, "the closed event the stop wrote was never read");
    }

    /** A clock the script moves forward with {@link FakeAgentSessionClient.Do} items. */
    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T10:00:00Z");

        FakeAgentSessionClient.Do advance(long ms) {
            return new FakeAgentSessionClient.Do(() -> now = now.plusMillis(ms));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final AgentSessionRunner.StepSink noting = new AgentSessionRunner.StepSink() {
        @Override
        public void session(String sessionId) {}

        @Override
        public void started(String callId, StepWords.Words w, long atMs) {
            sink.add("started " + callId + " @" + atMs);
        }

        @Override
        public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {}

        @Override
        public void note(String words, long atMs) {
            sink.add("note " + words + " @" + atMs);
        }
    };

    private static AgentSessionRunner.Task task(ModelLane lane) {
        AgentSessionRunner.Task t = task();
        return new AgentSessionRunner.Task(
                t.projectId(),
                lane,
                t.subject(),
                t.systemPrompt(),
                t.files(),
                t.mcp(),
                t.repo(),
                t.prompt(),
                t.jsonSchema(),
                t.deadline(),
                t.maxToolCalls(),
                t.words());
    }

    /**
     * A live line stuck on "Thinking" while the agent narrates, and a database written on every streamed token: the
     * agent's newest whole sentence is its note, written at most once a second and not again while it is unchanged,
     * and the answer it streams after its last tool call reads "Writing the ruling" instead of its JSON.
     */
    @Test
    void theAgentsWordsBecomeANoteAtMostOnceASecondAndTheAnswerReadsWritingTheRuling() {
        MovingClock clock = new MovingClock();
        client.reply(
                text("I'll read the finding first. "),
                clock.advance(400),
                text("Then the commit history. "),
                clock.advance(700),
                text("Then I will rule. "),
                clock.advance(1500),
                text("And then"),
                toolStart("c-1", null, "read", "{\"filePath\":\"/home/user/dossier/finding.md\"}"),
                toolEnd("c-1", true),
                clock.advance(2000),
                text(RULING.substring(0, 10)),
                clock.advance(1500),
                text(RULING.substring(10)),
                end());

        AgentSessionRunner.Answer answer = new AgentSessionRunner(client, MAPPER, clock, deadlines).run(task(), noting);

        assertEquals(RULING, answer.json());
        assertEquals(
                List.of(
                        "note I'll read the finding first @0",
                        "note Then I will rule @1100",
                        "started c-1 @2600",
                        "note Writing the ruling @4600"),
                sink);
    }

    /** An RCA whose live line says it is ruling: an RCA's answer, fenced or not, reads "Writing the analysis". */
    @Test
    void anRcaAnswerReadsWritingTheAnalysis() {
        client.reply(text("Here it is.\n```json\n" + RULING + "\n```"), end());

        assertEquals(RULING, runner().run(task(ModelLane.RCA), noting).json());
        assertEquals(List.of("note Writing the analysis @0"), sink);
    }
}
