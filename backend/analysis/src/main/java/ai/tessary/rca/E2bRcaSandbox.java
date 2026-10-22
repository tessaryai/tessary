// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static ai.tessary.sandbox.AgentSpanTelemetry.markError;

import ai.tessary.agentrun.AgentModels;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.AgentSessionRunner;
import ai.tessary.agentrun.ProviderStop;
import ai.tessary.agentrun.StepWords;
import ai.tessary.config.RcaProperties;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.RcaError;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * RCA over a launcher session: the dossier, this platform's MCP surface and, when the project has one, a read-only
 * clone. The agent gets RCA's prompt as its first message and no system prompt, so it reads exactly what it always
 * did. The session client books each reply on the report; this class words the run's steps into the job's
 * {@code agent_run} and names each way a run ends, so a stopped RCA can say why. Every failure throws: a
 * user-triggered RCA must stop rather than degrade to a bogus "no cause found".
 */
@Service
public class E2bRcaSandbox implements RcaSandbox {

    public static final String KEY = "e2b";

    static final String OPERATION = "agentic-rca";

    /**
     * Booked against the report this run investigated: {@code SandboxRequest.subjectId} is the mover's failure subject,
     * which has no honest {@code subject_kind}.
     */
    static final String SUBJECT_KIND = "rca_report";

    /** The turns the agent keeps for writing its answer once its tool calls run out. */
    static final int ANSWER_TURNS = 2;

    /** How long a job waits in the queue when every session slot is taken. */
    static final int BUSY_RETRY_SECONDS = 30;

    private final RcaProperties props;
    private final AgentSessionRunner runner;
    private final AgentRunRepository runs;
    private final AgentModels models;
    private final Tracer tracer;
    private final ObjectMapper mapper;

    public E2bRcaSandbox(
            RcaProperties props,
            AgentSessionRunner runner,
            AgentRunRepository runs,
            AgentModels models,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.runner = runner;
        this.runs = runs;
        this.models = models;
        this.tracer = openTelemetry.getTracer("ai.tessary.rca");
        this.mapper = mapper;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public SandboxRun run(SandboxRequest req) {
        RcaProperties.Agentic cfg = props.agentic();
        Span span = tracer.spanBuilder(OPERATION)
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            span.setAttribute("langfuse.trace.name", "agentic-rca");
            span.setAttribute("tessary.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("tessary.rca.subject_id", req.subjectId());
            span.setAttribute("tessary.head_sha", req.headSha() == null ? "" : req.headSha());
            // Without the discriminator the trace store never types this as a generation.
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute(
                    "gen_ai.request.model",
                    models.resolve(req.projectId(), ModelLane.RCA).model());
            AgentSessionRunner.StepSink steps = runs.openRunOfJob(req.jobId())
                    .<AgentSessionRunner.StepSink>map(run -> new RunSteps(runs, run.id()))
                    .orElse(NO_STEPS);
            AgentSessionRunner.Answer answer = runner.run(
                    new AgentSessionRunner.Task(
                            req.projectId(),
                            ModelLane.RCA,
                            new LlmUsageAccountant.Subject(SUBJECT_KIND, req.reportId()),
                            null,
                            req.files(),
                            List.of(new AgentSessionClient.McpServer(
                                    StepWords.PLATFORM_SERVER, req.mcpUrl(), req.mcpToken())),
                            repo(req),
                            req.prompt(),
                            req.jsonSchema(),
                            Duration.ofMillis(cfg.timeoutMs()),
                            Math.max(1, cfg.maxTurns() - ANSWER_TURNS),
                            req.words()),
                    steps);
            recordUsage(span, answer.usage());
            span.setAttribute("tessary.agent.steps", (long) answer.steps());
            AgentSpanTelemetry.recordSpanIo(span, req.prompt(), answer.json());
            return new SandboxRun(answer.json());
        } catch (RuntimeException e) {
            TessaryException mapped = mapped(e);
            markError(span, mapped.toString());
            span.recordException(mapped);
            throw mapped;
        } finally {
            span.end();
        }
    }

    /** A project with no git integration runs evidence-only. */
    private static AgentSessionClient.@Nullable Clone repo(SandboxRequest req) {
        String url = req.cloneUrl();
        String headSha = req.headSha();
        if (url == null || headSha == null) return null;
        return new AgentSessionClient.Clone(url, headSha, req.onsetAt());
    }

    /** The code the stopped bar names; only a full launcher asks for another try. */
    private static TessaryException mapped(RuntimeException e) {
        if (e instanceof ProviderStop stop) {
            return switch (stop.kind()) {
                case "provider_rejected" -> new TessaryException(RcaError.PROVIDER_REJECTED, e, detail(e));
                case "request_refused" -> new TessaryException(RcaError.REQUEST_REFUSED, e, detail(e));
                default -> new TessaryException(RcaError.PROVIDER_UNAVAILABLE, e, detail(e));
            };
        }
        if (!(e instanceof TessaryException t)) {
            return new TessaryException(RcaError.UPSTREAM_FAILED, e, e.toString());
        }
        if (!(t.error() instanceof AgentRunError code)) return t;
        return switch (code) {
            case LAUNCHER_UNAVAILABLE -> new TessaryException(RcaError.LAUNCHER_UNREACHABLE, e, detail(e));
            case LAUNCHER_MISCONFIGURED -> new TessaryException(RcaError.LAUNCHER_MISCONFIGURED, e, detail(e));
            case CAPACITY -> new SandboxBusy(t);
            case TIMED_OUT -> new TessaryException(RcaError.TIMED_OUT, e, detail(e));
            case RUN_FAILED, BAD_OUTPUT -> new TessaryException(RcaError.UPSTREAM_FAILED, e, detail(e));
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
            super(RcaError.SANDBOX_BUSY, cause, cause.getMessage());
        }

        @Override
        public int retryAfterSeconds() {
            return BUSY_RETRY_SECONDS;
        }
    }

    /** A job the worker opened no run for still analyzes; its steps are not kept. */
    private static final AgentSessionRunner.StepSink NO_STEPS = new AgentSessionRunner.StepSink() {
        @Override
        public void session(String sessionId) {}

        @Override
        public void started(String callId, StepWords.Words w, long atMs) {}

        @Override
        public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {}

        @Override
        public void note(String words, long atMs) {}
    };

    /** Writes the runner's steps to the run the worker opened for this job. */
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
