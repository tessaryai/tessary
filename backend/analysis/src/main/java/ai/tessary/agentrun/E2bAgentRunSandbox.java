// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The launcher-backed {@link AgentRunSandbox}: one {@code POST /authoring} to the sidecar is one
 * fresh sandbox that materializes the caller's files under {@code dossier/}, clones the repository
 * when the request names one, runs the agent against them and the platform's MCP surface, and tears
 * down. The body is triage's plus {@code clone_url}/{@code head_sha} when present; {@code
 * system_prompt} always rides, which is what selects the custom agent and the MCP relay in the VM.
 *
 * <p>The credential and the two tokens on the request travel to the launcher and nowhere else:
 * never a log line, never a span attribute. The launcher keeps them off the sandbox disk.
 *
 * <p>Every failure is a thrown {@link AgentRunError}, split the way {@code E2bTriageSandbox} splits
 * its own: a launcher that cannot be reached or failed before the run started is
 * {@code LAUNCHER_UNAVAILABLE}, a 401/403/404 is {@code LAUNCHER_MISCONFIGURED} because the next
 * request will be refused identically, and a run the launcher started and could not finish is this
 * run's own fault, read off the {@code kind} in its error body. Usage is booked on both paths,
 * since a failed run spends tokens exactly as a completed one does.
 */
@Component
public class E2bAgentRunSandbox implements AgentRunSandbox {

    public static final String KEY = "e2b";

    private static final Logger log = LoggerFactory.getLogger(E2bAgentRunSandbox.class);

    /** Telemetry name for the run's root span and Langfuse trace; the lane rides as an attribute. */
    static final String OPERATION = "agent-run";

    private static final String ROUTE = "/authoring";

    private final AgentRunProperties props;
    private final ObserverProperties observerProps;
    private final LlmUsageAccountant usage;
    private final Tracer tracer;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public E2bAgentRunSandbox(
            AgentRunProperties props,
            ObserverProperties observerProps,
            LlmUsageAccountant usage,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this(
                props,
                observerProps,
                usage,
                openTelemetry,
                mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    E2bAgentRunSandbox(
            AgentRunProperties props,
            ObserverProperties observerProps,
            LlmUsageAccountant usage,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper,
            HttpClient client) {
        this.props = props;
        this.observerProps = observerProps;
        this.usage = usage;
        this.tracer = openTelemetry.getTracer("ai.tessary.agentrun");
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(Request req) {
        String launcherUrl = launcherUrl();
        if (launcherUrl.isBlank()) {
            throw new TessaryException(
                    AgentRunError.LAUNCHER_MISCONFIGURED,
                    "tessary.agentrun.launcher-url and tessary.observer.agentic.launcher-url are both unset;"
                            + " set TESSARY_AGENTRUN_LAUNCHER_URL");
        }
        Span span = tracer.spanBuilder(OPERATION)
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            span.setAttribute("langfuse.trace.name", OPERATION);
            span.setAttribute("tessary.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("tessary.agentrun.lane", req.lane());
            span.setAttribute("tessary.agentrun.subject_kind", req.subject().kind());
            span.setAttribute("tessary.agentrun.subject_id", req.subject().id());
            span.setAttribute("tessary.head_sha", req.headSha() == null ? "" : req.headSha());
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute("gen_ai.request.model", req.model());

            Instant runStart = Instant.now();
            String respBody = postLauncher(mapper.writeValueAsString(body(req)), launcherUrl, req);
            long durationMs = Duration.between(runStart, Instant.now()).toMillis();
            JsonNode node = mapper.readTree(respBody);
            String raw = node.path("raw").asText("");
            if (raw.isBlank()) {
                throw badOutput(span, "the launcher answered without a result envelope");
            }
            AgentSpanTelemetry.recordUsage(span, mapper, raw);
            bookUsage(req, raw);
            JsonNode envelope = mapper.readTree(raw);
            JsonNode structured = envelope.path("structured_output");
            String resultText = envelope.path("result").asText("");
            if (!structured.isObject() && resultText.isBlank()) {
                throw badOutput(span, "the agent returned no result");
            }
            AgentSpanTelemetry.recordSpanIo(
                    span, req.prompt(), structured.isObject() ? structured.toString() : resultText);
            AgentSpanTelemetry.recordTurns(
                    tracer, node.path("turns"), node.path("startMs").asLong(0L), runStart);
            int turns = envelope.path("num_turns").asInt(node.path("turns").size());
            StructuredLog.info(log, Markers.OPS, "agentrun.finished")
                    .message(
                            "agent run on lane %s finished in %dms over %d turns, repo %s",
                            req.lane(), durationMs, turns, req.cloneUrl() == null ? "absent" : "cloned")
                    .field("project", req.projectId())
                    .field("lane", req.lane())
                    .field("subjectKind", req.subject().kind())
                    .field("subjectId", req.subject().id())
                    .field("turns", turns)
                    .field("durationMs", durationMs)
                    .field("repo", req.cloneUrl() != null)
                    .field("structured", structured.isObject())
                    .log();
            return new Result(
                    structured.isObject() ? structured : null,
                    resultText.isBlank() ? null : resultText,
                    turns,
                    durationMs);
        } catch (TessaryException e) {
            markError(span, e.getMessage() == null ? e.error().code() : e.getMessage());
            throw e;
        } catch (JsonProcessingException e) {
            // Jackson's message echoes the body it could not parse: it rides as the cause for a debugger
            // and stays off the span and off every log line this class writes.
            markError(span, "unparseable launcher answer");
            throw new TessaryException(AgentRunError.BAD_OUTPUT, e, "the launcher's answer did not parse");
        } finally {
            span.end();
        }
    }

    /** The launcher this sandbox posts to: this lane's own, else the observer's, which every agent lane rides. */
    private String launcherUrl() {
        String own = props.getLauncherUrl();
        return own == null || own.isBlank()
                ? blankToEmpty(observerProps.getAgentic().getLauncherUrl())
                : own;
    }

    private String launcherApiKey() {
        String own = props.getLauncherApiKey();
        return own == null || own.isBlank()
                ? blankToEmpty(observerProps.getAgentic().getLauncherApiKey())
                : own;
    }

    private static String blankToEmpty(String v) {
        return v == null ? "" : v;
    }

    private ObjectNode body(Request req) {
        ObjectNode body = mapper.createObjectNode();
        if (req.cloneUrl() != null && req.headSha() != null) {
            body.put("clone_url", req.cloneUrl());
            body.put("head_sha", req.headSha());
        }
        ObjectNode files = body.putObject("files");
        req.files().forEach(files::put);
        body.put("system_prompt", req.systemPrompt());
        body.put("prompt", req.prompt());
        if (req.jsonSchema() != null) body.put("json_schema", req.jsonSchema());
        body.put("model", req.model());
        ModelsDevRates.ModelCost modelCost = req.modelCost();
        if (modelCost != null) body.set("model_cost", modelCost.toOpencodeCost(mapper));
        body.put("provider", req.provider());
        body.set("credential", mapper.valueToTree(req.credential()));
        ObjectNode mcp = body.putObject("mcp");
        mcp.put("url", req.mcpUrl());
        mcp.put("token", req.mcpToken());
        body.put("timeout_ms", req.timeoutMs());
        body.put("max_turns", req.maxTurns());
        return body;
    }

    /**
     * One {@code POST /authoring}; the 2xx body, or a thrown {@link AgentRunError}. A non-2xx body is
     * booked against the ledger before the throw, since the launcher carries the run's usage in its
     * error body whenever the agent spent tokens before failing.
     */
    private String postLauncher(String bodyJson, String launcherUrl, Request req) {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(launcherUrl + ROUTE))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .header("Authorization", "Bearer " + launcherApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(req.timeoutMs() + 30_000))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(CommonError.INTERRUPTED, e, "agent run");
        } catch (IOException e) {
            boolean timedOut = e instanceof HttpTimeoutException;
            StructuredLog.error(log, Markers.OPS, "agentrun.launcher-unreachable")
                    .message(
                            "agent launcher at %s did not answer (%s)",
                            launcherUrl, timedOut ? "timed out" : e.getClass().getSimpleName())
                    .field("launcherUrl", launcherUrl)
                    .field("timedOut", timedOut)
                    .field("lane", req.lane())
                    .cause(e)
                    .log();
            if (timedOut) throw new TessaryException(AgentRunError.TIMED_OUT, e, req.timeoutMs());
            throw new TessaryException(
                    AgentRunError.LAUNCHER_UNAVAILABLE,
                    e,
                    "unreachable at " + launcherUrl + " (" + e.getClass().getSimpleName() + ")");
        }
        if (resp.statusCode() / 100 == 2) return resp.body();
        String diag = describeLauncherError(resp.body());
        AgentRunError failure = classify(resp.statusCode(), resp.body());
        StructuredLog.error(log, Markers.OPS, "agentrun.launcher-rejected")
                .message(
                        "agent launcher answered %d to the run (%s)%s",
                        resp.statusCode(), failure.name(), diag.isBlank() ? "" : ": " + diag)
                .field("launcherUrl", launcherUrl)
                .field("status", resp.statusCode())
                .field("failure", failure.name())
                .field("lane", req.lane())
                .log();
        bookUsage(req, resp.body());
        String detail = diag.isBlank() ? "status " + resp.statusCode() : diag;
        throw switch (failure) {
            case LAUNCHER_MISCONFIGURED ->
                new TessaryException(
                        AgentRunError.LAUNCHER_MISCONFIGURED, launcherDiagnosis(resp.statusCode(), launcherUrl, diag));
            case LAUNCHER_UNAVAILABLE ->
                new TessaryException(
                        AgentRunError.LAUNCHER_UNAVAILABLE, launcherDiagnosis(resp.statusCode(), launcherUrl, diag));
            case TIMED_OUT -> new TessaryException(AgentRunError.TIMED_OUT, req.timeoutMs());
            case BAD_OUTPUT -> new TessaryException(AgentRunError.BAD_OUTPUT, detail);
            case RUN_FAILED -> new TessaryException(AgentRunError.RUN_FAILED, detail);
        };
    }

    /**
     * Who is at fault for one non-2xx answer, pure so a table can drive every status/kind pair.
     * 401/403/404 are refusals a retry cannot change. A 502 is split by the launcher's own
     * {@code kind}: {@code timeout}, {@code script_exit}/{@code bad_request} and {@code bad_output}
     * are this run's, and {@code orchestration}, a missing or unknown kind (a proxy's own page) and
     * every other 5xx are the launcher's. Every remaining 4xx is a payload the launcher understood
     * and refused, a fact about this request.
     */
    static AgentRunError classify(int status, String body) {
        if (status == 401 || status == 403 || status == 404) return AgentRunError.LAUNCHER_MISCONFIGURED;
        if (status / 100 == 5) {
            if (status != 502) return AgentRunError.LAUNCHER_UNAVAILABLE;
            return switch (kindOf(body)) {
                case "timeout" -> AgentRunError.TIMED_OUT;
                case "script_exit", "bad_request" -> AgentRunError.RUN_FAILED;
                case "bad_output" -> AgentRunError.BAD_OUTPUT;
                default -> AgentRunError.LAUNCHER_UNAVAILABLE;
            };
        }
        return AgentRunError.RUN_FAILED;
    }

    private static final ObjectMapper KIND_MAPPER = new ObjectMapper();

    private static String kindOf(String body) {
        try {
            return KIND_MAPPER.readTree(body).path("kind").asText("");
        } catch (JsonProcessingException | RuntimeException e) {
            return "";
        }
    }

    private void bookUsage(Request req, String envelopeJson) {
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (u == null) return;
        usage.recordSandboxRun(
                req.projectId(),
                req.lane(),
                req.model(),
                req.credential().platformFunded(),
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.cacheWriteTokens(),
                u.costUsd(),
                req.subject());
    }

    private static String launcherDiagnosis(int status, String launcherUrl, String diag) {
        String remedy =
                switch (status) {
                    case 401, 403 ->
                        ", the shared secret does not match; TESSARY_AGENTRUN_LAUNCHER_API_KEY (or the observer's)"
                                + " must equal the launcher's SANDBOX_API_KEY";
                    case 404 -> ", no /authoring route; the launcher image is older than this backend";
                    default -> diag.isBlank() ? "" : ", " + diag;
                };
        return "status " + status + " from " + launcherUrl + remedy;
    }

    private String describeLauncherError(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            StringBuilder sb = new StringBuilder();
            if (n.hasNonNull("kind")) sb.append("kind=").append(n.get("kind").asText());
            if (n.hasNonNull("detail")) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append("detail=").append(n.get("detail").asText());
            }
            if (n.hasNonNull("sandbox_id")) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append("sandbox=").append(n.get("sandbox_id").asText());
            }
            return sb.toString();
        } catch (JsonProcessingException | RuntimeException e) {
            return "";
        }
    }

    private static TessaryException badOutput(Span span, String why) {
        markError(span, why);
        return new TessaryException(AgentRunError.BAD_OUTPUT, why);
    }

    private static void markError(Span span, String message) {
        span.setStatus(StatusCode.ERROR, message);
        span.setAttribute("langfuse.observation.level", "ERROR");
        span.setAttribute("langfuse.observation.status_message", message);
    }
}
