// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentModels;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.ProviderStop;
import ai.tessary.agentrun.StepWords;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.ModelConfigError;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * {@link E2bTriageSandbox} over a stubbed {@link AgentSessionRunner}: what the session is started with, where the
 * run's steps are written, and the code each way a run ends maps to. The codes decide whether the worker holds the job,
 * spends an attempt, releases it unspent or parks the drain, so each row of that table is a test here.
 */
class E2bTriageSandboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final StepWords.Context WORDS =
            new StepWords.Context(Map.of("witness", "flagged answers"), Map.of("witness", 58L));

    private final AgentSessionRunner runner = mock(AgentSessionRunner.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final Span span = mock(Span.class);

    private static TriageSandbox.SandboxRequest request() {
        return new TriageSandbox.SandboxRequest(
                "proj",
                "f1",
                Map.of("finding.md", "the finding"),
                "rule on it",
                "{\"type\":\"object\",\"required\":[\"verdict\"]}",
                "https://api.example/mcp",
                "tsy_a_secret",
                "system prompt",
                "run-1",
                WORDS);
    }

    private E2bTriageSandbox sandbox() {
        AgentModels models = mock(AgentModels.class);
        when(models.resolve("proj", ModelLane.TRIAGE))
                .thenReturn(new AgentModels.Choice("grok-4.6", null, ModelProvider.GROK));
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        when(builder.startSpan()).thenReturn(span);
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder(anyString())).thenReturn(builder);
        OpenTelemetry otel = mock(OpenTelemetry.class);
        when(otel.getTracer(anyString())).thenReturn(tracer);
        ObserverProperties props =
                Props.of(ObserverProperties.class, "agentic.timeout-ms", 1_800_000, "agentic.max-turns", 52);
        return new E2bTriageSandbox(props, runner, runs, models, otel, MAPPER);
    }

    /**
     * The triage session runs on the finding's own ledger subject and the triage lane, with the shared system prompt,
     * the dossier, the platform's MCP door, no clone, the observer's wall clock and the turn count the prompt states.
     * The bug: a run booked on another lane or subject, or a session with a cap the prompt does not promise.
     */
    @Test
    void theSessionRunsOnTheFindingsSubjectWithTheDossierTheDoorAndTheCapThePromptStates() {
        when(runner.run(any(), any())).thenReturn(new AgentSessionRunner.Answer("{\"verdict\":\"real\"}", usage(), 3));

        TriageSandbox.SandboxRun run = sandbox().run(request()).orElseThrow();

        assertEquals("{\"verdict\":\"real\"}", run.resultText());
        ArgumentCaptor<AgentSessionRunner.Task> task = ArgumentCaptor.forClass(AgentSessionRunner.Task.class);
        verify(runner).run(task.capture(), any());
        assertEquals(
                new AgentSessionRunner.Task(
                        "proj",
                        ModelLane.TRIAGE,
                        new LlmUsageAccountant.Subject(E2bTriageSandbox.SUBJECT_KIND, "f1"),
                        "system prompt",
                        Map.of("finding.md", "the finding"),
                        List.of(new AgentSessionClient.McpServer(
                                "tessary-evals", "https://api.example/mcp", "tsy_a_secret")),
                        null,
                        "rule on it",
                        "{\"type\":\"object\",\"required\":[\"verdict\"]}",
                        Duration.ofMillis(1_800_000),
                        50,
                        WORDS),
                task.getValue());
    }

    /**
     * Each step the runner words lands on the run the worker opened, and a call that failed is stored in its "Could
     * not" form. The bug: a step list that stays empty, or that says a failed read succeeded.
     */
    @Test
    void theRunsSessionAndStepsAreWrittenToItsRun() {
        StepWords.Words words = new StepWords.Words(
                "Read flagged answers 1 to 8 of 58", "Reading flagged answers 1 to 8", "read flagged answers 1 to 8");
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
        verify(runs).stepEnded("run-1", "c1", 9, false, "Could not read flagged answers 1 to 8");
        verify(runs).stepEnded("run-1", "c2", 12, true, words.done());
    }

    /**
     * The spend reaches the span from the runner's summed usage, with the step count. The session client books it;
     * this class holds no ledger, so it cannot book it a second time (double-booked spend).
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
     * One row per way a run can end, each to the code that makes the worker do the right thing: a rejected key or a
     * refused request holds the job instead of spending an attempt; a launcher down parks the drain unspent; a launcher
     * that refuses holds; a provider that did not answer, a timeout and an answerless run spend an attempt.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endings")
    void eachWayARunEndsMapsToTheCodeTheWorkerActsOn(String why, TessaryException thrown, ErrorCode expected) {
        when(runner.run(any(), any())).thenThrow(thrown);

        TessaryException e =
                assertThrows(TessaryException.class, () -> sandbox().run(request()));

        assertEquals(expected, e.error(), why);
        verify(span).end();
    }

    static Stream<Arguments> endings() {
        return Stream.of(
                Arguments.of(
                        "a rejected key holds the job instead of spending an attempt",
                        new ProviderStop("provider_rejected", "model provider: invalid key (HTTP 401)"),
                        ClassifierError.TRIAGE_PROVIDER_REJECTED),
                Arguments.of(
                        "a refused request holds the job instead of spending an attempt",
                        new ProviderStop("request_refused", "model provider: no such model (HTTP 404)"),
                        ClassifierError.TRIAGE_REQUEST_REFUSED),
                Arguments.of(
                        "a provider that did not answer spends an attempt",
                        new ProviderStop("provider_unavailable", "model provider: overloaded (HTTP 529)"),
                        ClassifierError.TRIAGE_PROVIDER_UNAVAILABLE),
                Arguments.of(
                        "a launcher that does not answer parks the drain unspent",
                        new TessaryException(AgentRunError.LAUNCHER_UNAVAILABLE, "connection refused"),
                        ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE),
                Arguments.of(
                        "a launcher that refuses the run holds the job until the deployment changes",
                        new TessaryException(AgentRunError.LAUNCHER_MISCONFIGURED, "status 401"),
                        ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED),
                Arguments.of(
                        "a run past its wall clock spends an attempt",
                        new TessaryException(AgentRunError.TIMED_OUT, 1_800_000),
                        ClassifierError.TRIAGE_RUN_INCOMPLETE),
                Arguments.of(
                        "a session that closed early spends an attempt",
                        new TessaryException(AgentRunError.RUN_FAILED, "the session closed"),
                        ClassifierError.TRIAGE_RUN_INCOMPLETE),
                Arguments.of(
                        "an answer that stays unusable after the correction spends an attempt",
                        new TessaryException(AgentRunError.BAD_OUTPUT, "no JSON object"),
                        ClassifierError.TRIAGE_RUN_INCOMPLETE),
                Arguments.of(
                        "an unexpected failure in the run spends an attempt",
                        new TessaryException(ClassifierError.TRIAGE_RUN_INCOMPLETE, "f1", "x"),
                        ClassifierError.TRIAGE_RUN_INCOMPLETE));
    }

    /**
     * A full launcher is nothing the run did wrong: the code says busy and asks for a retry in 30 seconds, which the
     * worker turns into an unspent release that still reads Queued (a busy sandbox shown as a retry).
     */
    @Test
    void aFullLauncherAsksForARetryWithoutSpendingTheAttempt() {
        when(runner.run(any(), any())).thenThrow(new TessaryException(AgentRunError.CAPACITY, "4 sessions running"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> sandbox().run(request()));

        assertEquals(ClassifierError.TRIAGE_SANDBOX_BUSY, e.error());
        assertEquals(30, assertInstanceOf(Retryable.class, e).retryAfterSeconds());
    }

    /** An org with no usable credential is the worker's credential-gap hold, so its code passes through untouched. */
    @Test
    void aMissingCredentialPassesThroughToTheWorkersHold() {
        TessaryException missing = new TessaryException(ModelConfigError.MISSING_CREDENTIALS, "grok", "triage");
        when(runner.run(any(), any())).thenThrow(missing);

        assertSame(missing, assertThrows(TessaryException.class, () -> sandbox().run(request())));
    }

    /** A failure that is not a Tessary error still spends an attempt with the finding named, never escapes raw. */
    @Test
    void aRawFailureSpendsAnAttempt() {
        when(runner.run(any(), any())).thenThrow(new IllegalStateException("socket closed"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> sandbox().run(request()));

        assertEquals(ClassifierError.TRIAGE_RUN_INCOMPLETE, e.error());
        assertEquals(
                IllegalStateException.class,
                e.getCause() == null ? null : e.getCause().getClass());
    }

    /** The span names no token: the MCP key rides the session start only. */
    @Test
    void theSpanCarriesNoToken() {
        when(runner.run(any(), any())).thenReturn(new AgentSessionRunner.Answer("{}", usage(), 0));
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);

        sandbox().run(request());

        verify(span, org.mockito.Mockito.atLeastOnce()).setAttribute(anyString(), values.capture());
        assertNull(values.getAllValues().stream()
                .filter(v -> v.contains("tsy_a_secret"))
                .findFirst()
                .orElse(null));
    }

    private static AgentSpanTelemetry.AgentUsage usage() {
        return new AgentSpanTelemetry.AgentUsage(1200, 80, 1000, 200, new BigDecimal("0.0123"));
    }
}
