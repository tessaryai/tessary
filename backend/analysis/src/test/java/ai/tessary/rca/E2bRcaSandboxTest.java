// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentModels;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.ProviderStop;
import ai.tessary.agentrun.StepWords;
import ai.tessary.config.RcaProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.testsupport.Props;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * {@link E2bRcaSandbox} over a stubbed {@link AgentSessionRunner}: what the session is started with, where the run's
 * steps are written, and the code each way a run ends maps to. RCA neither retries nor holds, so the code is what the
 * stopped bar names; each row of that table is a test here.
 */
class E2bRcaSandboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final StepWords.Context WORDS =
            new StepWords.Context(Map.of("witness", "flagged answers"), Map.of("witness", 58L));

    private static final String CLONE_URL = "https://x-access-token:ghs_cloneSecret@github.com/acme/agent.git";

    private static final String PROMPT = "Investigate. The onset commit is {onset_commit}.";

    private final AgentSessionRunner runner = mock(AgentSessionRunner.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final Span span = mock(Span.class);

    private static RcaSandbox.SandboxRequest request(String cloneUrl, String headSha, String onsetAt) {
        return new RcaSandbox.SandboxRequest(
                "proj",
                "subject-1",
                cloneUrl,
                headSha,
                onsetAt,
                Map.of("findings/1-f1/finding.md", "the finding"),
                PROMPT,
                "{\"type\":\"object\",\"required\":[\"verdict\"]}",
                "https://api.example/mcp",
                "tsy_a_secret",
                "report-1",
                "job-1",
                WORDS);
    }

    private static RcaSandbox.SandboxRequest request() {
        return request(CLONE_URL, "a41c0de9", "2026-07-14T12:00:00Z");
    }

    private E2bRcaSandbox sandbox() {
        AgentModels models = mock(AgentModels.class);
        when(models.resolve("proj", ModelLane.RCA))
                .thenReturn(new AgentModels.Choice("grok-4.6", null, ModelProvider.GROK));
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        when(builder.startSpan()).thenReturn(span);
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder(anyString())).thenReturn(builder);
        OpenTelemetry otel = mock(OpenTelemetry.class);
        when(otel.getTracer(anyString())).thenReturn(tracer);
        RcaProperties props = Props.of(RcaProperties.class, "agentic.timeout-ms", 900_000, "agentic.max-turns", 40);
        return new E2bRcaSandbox(props, runner, runs, models, otel, MAPPER);
    }

    private static AgentRunRepository.RunSummary openRun() {
        return new AgentRunRepository.RunSummary(
                "run-1",
                AgentRunRepository.Lane.RCA,
                "report-1",
                1,
                "2026-10-20T10:00:00Z",
                null,
                null,
                null,
                0,
                null,
                null);
    }

    /**
     * The RCA session runs on the report's ledger subject and the RCA lane with no system prompt and the prompt
     * byte for byte, so opencode's default agent reads exactly what the one-shot lane sent (an RCA prompt edited by
     * the move); it gets the repository to clone, the platform's MCP door, RCA's own wall clock and the turn cap
     * less the two the agent keeps for its answer.
     */
    @Test
    void theSessionRunsOnTheReportWithThePromptUnchangedAndTheClone() {
        when(runner.run(any(), any())).thenReturn(new AgentSessionRunner.Answer("{\"verdict\":\"x\"}", usage(), 3));

        RcaSandbox.SandboxRun run = sandbox().run(request());

        assertEquals("{\"verdict\":\"x\"}", run.resultText());
        ArgumentCaptor<AgentSessionRunner.Task> task = ArgumentCaptor.forClass(AgentSessionRunner.Task.class);
        verify(runner).run(task.capture(), any());
        assertEquals(
                new AgentSessionRunner.Task(
                        "proj",
                        ModelLane.RCA,
                        new LlmUsageAccountant.Subject("rca_report", "report-1"),
                        null,
                        Map.of("findings/1-f1/finding.md", "the finding"),
                        List.of(new AgentSessionClient.McpServer(
                                "tessary-evals", "https://api.example/mcp", "tsy_a_secret")),
                        new AgentSessionClient.Clone(CLONE_URL, "a41c0de9", "2026-07-14T12:00:00Z"),
                        PROMPT,
                        "{\"type\":\"object\",\"required\":[\"verdict\"]}",
                        Duration.ofMillis(900_000),
                        38,
                        WORDS),
                task.getValue());
    }

    /** A project with no git integration runs evidence-only: no clone at all, not a clone of nothing. */
    @Test
    void aRepolessRunStartsWithNoClone() {
        when(runner.run(any(), any())).thenReturn(new AgentSessionRunner.Answer("{}", usage(), 0));

        sandbox().run(request(null, null, null));

        ArgumentCaptor<AgentSessionRunner.Task> task = ArgumentCaptor.forClass(AgentSessionRunner.Task.class);
        verify(runner).run(task.capture(), any());
        assertNull(task.getValue().repo());
    }

    /**
     * Each step the runner words lands on the run the worker opened for this job, and a call that failed is stored in
     * its "Could not" form. The bug: an RCA whose step list stays empty, or says a failed read succeeded.
     */
    @Test
    void theRunsSessionAndStepsAreWrittenToTheJobsOpenRun() {
        when(runs.openRunOfJob("job-1")).thenReturn(Optional.of(openRun()));
        StepWords.Words words =
                new StepWords.Words("Read commit a41c0de", "Reading commit a41c0de", "read commit a41c0de");
        when(runner.run(any(), any())).thenAnswer(inv -> {
            AgentSessionRunner.StepSink sink = inv.getArgument(1);
            sink.session("s-1");
            sink.started("c1", words, 5);
            sink.ended("c1", 9, false, words);
            sink.started("c2", words, 10);
            sink.ended("c2", 12, true, words);
            return new AgentSessionRunner.Answer("{}", usage(), 2);
        });

        sandbox().run(request());

        verify(runs).attachSession("run-1", "s-1");
        verify(runs).stepStarted("run-1", "c1", words.done(), words.doing(), 5);
        verify(runs).stepEnded("run-1", "c1", 9, false, "Could not read commit a41c0de");
        verify(runs).stepEnded("run-1", "c2", 12, true, words.done());
    }

    /** A job with no open run still analyzes: a missing step list must not fail a person's RCA. */
    @Test
    void aJobWithNoOpenRunStillRunsAndWritesNoSteps() {
        when(runs.openRunOfJob("job-1")).thenReturn(Optional.empty());
        StepWords.Words words = new StepWords.Words("Ran a command", "Running a command", "run a command");
        when(runner.run(any(), any())).thenAnswer(inv -> {
            AgentSessionRunner.StepSink sink = inv.getArgument(1);
            sink.session("s-1");
            sink.started("c1", words, 5);
            sink.ended("c1", 9, true, words);
            return new AgentSessionRunner.Answer("{\"verdict\":\"x\"}", usage(), 1);
        });

        assertEquals("{\"verdict\":\"x\"}", sandbox().run(request()).resultText());

        verify(runs).openRunOfJob("job-1");
        verifyNoMoreInteractions(runs);
    }

    /**
     * The spend reaches the span from the runner's summed usage, with the step count. The session client books it on
     * the report; this class holds no ledger, so it cannot book it a second time (double-booked spend).
     */
    @Test
    void theRunsUsageAndStepCountReachTheSpan() {
        when(runner.run(any(), any())).thenReturn(new AgentSessionRunner.Answer("{}", usage(), 7));

        sandbox().run(request());

        verify(span).setAttribute("gen_ai.usage.input_tokens", 1200L);
        verify(span).setAttribute("gen_ai.usage.output_tokens", 80L);
        verify(span).setAttribute("gen_ai.usage.cost", 0.0123);
        verify(span).setAttribute("gen_ai.request.model", "grok-4.6");
        verify(span).setAttribute("tessary.agent.steps", 7L);
        verify(span).end();
    }

    /**
     * One row per way a run can end, each to the code the stopped bar names: a rejected key, a refused request and a
     * provider that did not answer each say so instead of UPSTREAM_FAILED (a stopped RCA that cannot say why); a
     * launcher down or refusing, and a run past its wall clock, are named; a run with no usable answer is the generic
     * failure.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endings")
    void eachWayARunEndsMapsToTheCodeTheStoppedBarNames(String why, RuntimeException thrown, ErrorCode expected) {
        when(runner.run(any(), any())).thenThrow(thrown);

        TessaryException e =
                assertThrows(TessaryException.class, () -> sandbox().run(request()));

        assertEquals(expected, e.error(), why);
        verify(span).end();
    }

    static Stream<Arguments> endings() {
        return Stream.of(
                Arguments.of(
                        "a rejected key stops the RCA as PROVIDER_REJECTED, not UPSTREAM_FAILED",
                        new ProviderStop("provider_rejected", "model provider: invalid key (HTTP 401)"),
                        RcaError.PROVIDER_REJECTED),
                Arguments.of(
                        "a refused request stops the RCA as REQUEST_REFUSED",
                        new ProviderStop("request_refused", "model provider: no such model (HTTP 404)"),
                        RcaError.REQUEST_REFUSED),
                Arguments.of(
                        "a provider that did not answer stops the RCA as PROVIDER_UNAVAILABLE",
                        new ProviderStop("provider_unavailable", "model provider: overloaded (HTTP 529)"),
                        RcaError.PROVIDER_UNAVAILABLE),
                Arguments.of(
                        "a launcher that does not answer is unreachable",
                        new TessaryException(AgentRunError.LAUNCHER_UNAVAILABLE, "connection refused"),
                        RcaError.LAUNCHER_UNREACHABLE),
                Arguments.of(
                        "a launcher that refuses the run is misconfigured",
                        new TessaryException(AgentRunError.LAUNCHER_MISCONFIGURED, "status 401"),
                        RcaError.LAUNCHER_MISCONFIGURED),
                Arguments.of(
                        "a run past its wall clock timed out",
                        new TessaryException(AgentRunError.TIMED_OUT, 900_000),
                        RcaError.TIMED_OUT),
                Arguments.of(
                        "a session that closed early is the generic failure",
                        new TessaryException(AgentRunError.RUN_FAILED, "the session closed"),
                        RcaError.UPSTREAM_FAILED),
                Arguments.of(
                        "an answer still unusable after the correction is the generic failure",
                        new TessaryException(AgentRunError.BAD_OUTPUT, "no JSON object"),
                        RcaError.UPSTREAM_FAILED),
                Arguments.of(
                        "a failure that is not a Tessary error is the generic failure",
                        new IllegalStateException("socket closed"),
                        RcaError.UPSTREAM_FAILED));
    }

    /**
     * A full launcher is nothing the run did wrong: the code says busy and asks for a retry in 30 seconds, which the
     * worker turns into an unspent re-queue that still reads Queued (a busy sandbox read as a failure).
     */
    @Test
    void aFullLauncherAsksForARetryWithoutFailingTheRca() {
        when(runner.run(any(), any())).thenThrow(new TessaryException(AgentRunError.CAPACITY, "4 sessions running"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> sandbox().run(request()));

        assertEquals(RcaError.SANDBOX_BUSY, e.error());
        assertEquals(30, assertInstanceOf(Retryable.class, e).retryAfterSeconds());
    }

    /** An org with no usable credential fails the RCA with that code, so the page can point at Providers. */
    @Test
    void aMissingCredentialPassesThrough() {
        TessaryException missing = new TessaryException(ModelConfigError.MISSING_CREDENTIALS, "grok", "rca");
        when(runner.run(any(), any())).thenThrow(missing);

        assertSame(missing, assertThrows(TessaryException.class, () -> sandbox().run(request())));
    }

    /**
     * The clone URL embeds a short-lived token: it rides the session start and nothing else, so no span attribute and
     * no exception message carries it, nor the MCP key (a clone token in a trace or a log line).
     */
    @Test
    void theCloneUrlAndTheMcpKeyReachNoSpanAttributeAndNoError() {
        when(runner.run(any(), any())).thenReturn(new AgentSessionRunner.Answer("{}", usage(), 0));
        sandbox().run(request());
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(span, atLeastOnce()).setAttribute(anyString(), values.capture());
        for (String v : values.getAllValues()) {
            assertFalse(v.contains("ghs_cloneSecret") || v.contains("tsy_a_secret"), v);
        }

        when(runner.run(any(), any())).thenThrow(new IllegalStateException("socket closed"));
        TessaryException e =
                assertThrows(TessaryException.class, () -> sandbox().run(request()));
        assertFalse(String.valueOf(e.getMessage()).contains("ghs_cloneSecret"), e.getMessage());
        assertFalse(String.valueOf(e.getMessage()).contains("tsy_a_secret"), e.getMessage());
    }

    private static AgentSpanTelemetry.AgentUsage usage() {
        return new AgentSpanTelemetry.AgentUsage(1200, 80, 1000, 200, new BigDecimal("0.0123"));
    }
}
