// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static ai.tessary.sandbox.AgentSpanTelemetry.markError;

import ai.tessary.agentrun.AgentModels;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.ProviderStop;
import ai.tessary.agentrun.StepWords;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Layer-2 triage over a launcher session: the finding's dossier, this platform's MCP surface and no repository clone.
 * The session client books each reply on the finding; this class words the run's steps into its {@code agent_run} and
 * turns each way a run ends into the code the worker acts on: a launcher down parks the drain, a full one releases the
 * job unspent, a refusal only a person can clear holds it, and a run with no ruling spends an attempt.
 */
@Service
public class E2bTriageSandbox implements TriageSandbox {

    public static final String KEY = "e2b";

    static final String OPERATION = "layer2-triage";

    /**
     * A ruling's unit of work is the finding, not the case: a case may never open, and the money is spent either way.
     */
    static final String SUBJECT_KIND = "behavior_finding";

    /** How long a job waits in the queue when every session slot is taken. */
    static final int BUSY_RETRY_SECONDS = 30;

    private final ObserverProperties props;
    private final AgentSessionRunner runner;
    private final AgentRunRepository runs;
    private final AgentModels models;
    private final Tracer tracer;
    private final ObjectMapper mapper;

    public E2bTriageSandbox(
            ObserverProperties props,
            AgentSessionRunner runner,
            AgentRunRepository runs,
            AgentModels models,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.runner = runner;
        this.runs = runs;
        this.models = models;
        // The class's own package, as every other sandbox here does.
        this.tracer = openTelemetry.getTracer("ai.tessary.classifier.finding");
        this.mapper = mapper;
    }

    @Override
    public String key() {
        return KEY;
    }

    /** Answers or throws; never empty. */
    @Override
    public Optional<SandboxRun> run(SandboxRequest req) {
        ObserverProperties.Agentic cfg = props.getAgentic();
        Span span = tracer.spanBuilder(OPERATION)
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            span.setAttribute("langfuse.trace.name", OPERATION);
            span.setAttribute("tessary.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("tessary.triage.finding_id", req.findingId());
            // Without the discriminator the trace store never types this as a generation.
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute(
                    "gen_ai.request.model",
                    models.resolve(req.projectId(), ModelLane.TRIAGE).model());
            AgentSessionRunner.Answer answer = runner.run(
                    new AgentSessionRunner.Task(
                            req.projectId(),
                            ModelLane.TRIAGE,
                            new LlmUsageAccountant.Subject(SUBJECT_KIND, req.findingId()),
                            req.systemPrompt(),
                            req.files(),
                            List.of(new AgentSessionClient.McpServer(
                                    StepWords.PLATFORM_SERVER, req.mcpUrl(), req.mcpToken())),
                            null,
                            req.prompt(),
                            req.jsonSchema(),
                            Duration.ofMillis(cfg.getTimeoutMs()),
                            Math.max(1, cfg.getMaxTurns() - BehaviorTriageEngine.TURN_RESERVE),
                            req.words()),
                    new RunSteps(runs, req.runId()));
            recordUsage(span, answer.usage());
            span.setAttribute("tessary.agent.steps", (long) answer.steps());
            AgentSpanTelemetry.recordSpanIo(span, req.prompt(), answer.json());
            return Optional.of(new SandboxRun(answer.json()));
        } catch (RuntimeException e) {
            TessaryException mapped = mapped(e, req.findingId());
            markError(span, mapped.toString());
            span.recordException(mapped);
            throw mapped;
        } finally {
            span.end();
        }
    }

    /** The worker reads the code: hold, release unspent, park the drain, or spend an attempt. */
    private static TessaryException mapped(RuntimeException e, String findingId) {
        if (e instanceof ProviderStop stop) {
            return switch (stop.kind()) {
                case "provider_rejected" ->
                    new TessaryException(ClassifierError.TRIAGE_PROVIDER_REJECTED, e, detail(e));
                case "request_refused" -> new TessaryException(ClassifierError.TRIAGE_REQUEST_REFUSED, e, detail(e));
                default -> new TessaryException(ClassifierError.TRIAGE_PROVIDER_UNAVAILABLE, e, detail(e));
            };
        }
        if (!(e instanceof TessaryException t)) {
            return new TessaryException(ClassifierError.TRIAGE_RUN_INCOMPLETE, e, findingId, e.toString());
        }
        if (!(t.error() instanceof AgentRunError code)) return t;
        return switch (code) {
            case LAUNCHER_UNAVAILABLE ->
                new TessaryException(ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE, e, detail(e));
            case LAUNCHER_MISCONFIGURED ->
                new TessaryException(ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED, e, detail(e));
            case CAPACITY -> new SandboxBusy(t);
            case RUN_FAILED, BAD_OUTPUT, TIMED_OUT ->
                new TessaryException(ClassifierError.TRIAGE_RUN_INCOMPLETE, e, findingId, detail(e));
        };
    }

    private static String detail(RuntimeException e) {
        return String.valueOf(e.getMessage());
    }

    private void recordUsage(Span span, AgentSpanTelemetry.AgentUsage u) {
        ObjectNode envelope = mapper.createObjectNode();
        ObjectNode usage = envelope.putObject("usage");
        usage.put("input_tokens", u.inputTokens());
        usage.put("output_tokens", u.outputTokens());
        usage.put("cache_read_input_tokens", u.cacheReadTokens());
        usage.put("cache_creation_input_tokens", u.cacheWriteTokens());
        BigDecimal cost = u.costUsd();
        if (cost != null) usage.put("cost_usd", cost);
        AgentSpanTelemetry.recordUsage(span, mapper, envelope.toString());
    }

    /** Nothing failed: the launcher is at its session cap, so the job goes back to the queue unspent. */
    static final class SandboxBusy extends TessaryException implements Retryable {
        SandboxBusy(TessaryException cause) {
            super(ClassifierError.TRIAGE_SANDBOX_BUSY, cause, cause.getMessage());
        }

        @Override
        public int retryAfterSeconds() {
            return BUSY_RETRY_SECONDS;
        }
    }

    /** Writes the runner's steps to the run the worker opened for this attempt. */
    private record RunSteps(AgentRunRepository runs, String runId) implements AgentSessionRunner.StepSink {

        @Override
        public void session(String sessionId) {
            runs.attachSession(runId, sessionId);
        }

        @Override
        public void started(String callId, StepWords.Words w, long atMs) {
            runs.stepStarted(runId, callId, w.done(), w.doing(), atMs);
        }

        @Override
        public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {
            runs.stepEnded(runId, callId, atMs, ok, ok ? w.done() : StepWords.failed(w));
        }

        @Override
        public void note(String words, long atMs) {
            runs.note(runId, words, atMs);
        }
    }
}
