// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import ai.tessary.evals.config.ObserverProperties;
import ai.tessary.evals.config.RcaProperties;
import ai.tessary.evals.config.RcaProperties.Agentic;
import ai.tessary.evals.llm.AgenticCredentialResolver;
import ai.tessary.evals.llm.ModelProvider;
import ai.tessary.evals.llm.ProjectModelSettings;
import ai.tessary.evals.llmspi.ModelLane;
import ai.tessary.evals.open.errors.CommonError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.RcaError;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.sandbox.AgentSpanTelemetry;
import ai.tessary.evals.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives one agentic RCA run through the Node launcher sidecar ({@code POST /rca}) — the same
 * one-request-one-fresh-microVM shape as the observer's {@code E2bAnalysisSandbox}: the sidecar
 * clones the repo, materializes the evidence dossier as files, runs the agent (optionally wired
 * to the platform's MCP surface via a short-lived key), and tears the sandbox down.
 *
 * <p><b>#939 D4: model credentials are resolved and decrypted HERE</b> (via {@link
 * AgenticCredentialResolver}), not in the launcher — the launcher reads no provider secret from
 * its own process env any more (the deployment-wide {@code AGENT_PROVIDER} path was FULLY
 * REMOVED). The org's own credential rides on the {@code credential} field below, injected on
 * every request and never logged.
 *
 * <p>Wire ABI (Bearer-authed with the launcher key):
 *
 * <pre>
 * POST /rca { clone_url, head_sha, files, prompt, json_schema, model, provider, credential,
 *             mcp: {url, token}|null, timeout_ms }
 *   -&gt; { "raw": "&lt;result envelope&gt;", "turns": [...], "startMs": n }
 * </pre>
 *
 * <p>Each run is wrapped in a standalone OTel span ({@code gen_ai.*} + {@code langfuse.*}) so it
 * flows through the same Alloy→Langfuse pipeline as judge calls. Unlike the observer's fail-open
 * drift analysis, every transport/HTTP/parse failure here throws {@link EvalsException} — a
 * user-triggered RCA must stamp {@code failed} rather than silently degrade to a bogus
 * "inconclusive" verdict.
 */
@Service
public class E2bRcaSandbox implements RcaSandbox {

    /** Selector key for {@code evals.rca.agentic.sandbox} — the only driver that ships today. */
    public static final String KEY = "e2b";

    private static final Logger log = LoggerFactory.getLogger(E2bRcaSandbox.class);

    private final RcaProperties props;
    /** RCA shares the observer's launcher sidecar, and its {@code ObserverProperties.Agentic#model}
     *  is still the default — RCA has no model config of its own. A project can override it per
     *  project on the {@link ModelLane#RCA} lane. */
    private final ObserverProperties observerProps;

    private final ProjectModelSettings modelSettings;
    /** #939 D4: resolves + decrypts the org's own credential for the sandbox request body. */
    private final AgenticCredentialResolver credentials;

    private final LlmUsageAccountant usage;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Tracer tracer;

    public E2bRcaSandbox(
            RcaProperties props,
            ObserverProperties observerProps,
            ProjectModelSettings modelSettings,
            AgenticCredentialResolver credentials,
            LlmUsageAccountant usage,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.observerProps = observerProps;
        this.modelSettings = modelSettings;
        this.credentials = credentials;
        this.usage = usage;
        this.tracer = openTelemetry.getTracer("ai.tessary.evals.rca");
        this.mapper = mapper;
    }

    /**
     * The ledger's subject kind for an RCA run: the table its {@code subject_id} points into. Mirrors
     * {@code E2bTriageSandbox.SUBJECT_KIND} (which uses {@code behavior_finding}) — F2's fix for the
     * gap noted below.
     */
    static final String SUBJECT_KIND = "rca_report";

    /**
     * Book the run's tokens and cost against the {@link ModelLane#RCA} lane AND against the REPORT it
     * ran for. One agentic run is one ledger entry: the launcher reports usage for the whole run, and
     * the run is the unit the lane is billed for.
     *
     * <p>F2: {@code reportId} used to be sent as a hard {@code null} subject — {@code
     * SandboxRequest.subjectId} is the mover's FAILURE subject (an issue, a session, a trace), not a
     * row in one table, so it had no honest {@code subject_kind} and a guessed one would have been a
     * fabricated attribution. {@code SandboxRequest.reportId} is the real, always-present {@code
     * rca_report} row this run investigated (see that field's javadoc), so the ledger can now say what
     * an RCA run cost the same way {@code E2bTriageSandbox} already says it for a triage ruling.
     */
    private void bookUsage(@Nullable String projectId, @Nullable String reportId, String envelopeJson) {
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (projectId == null || u == null) return;
        usage.recordSandboxRun(
                projectId,
                ModelLane.RCA.wire(),
                model(projectId),
                // #939 D4: never platform-funded any more — the run carries the org's own injected
                // credential (AgenticCredentialResolver), so this lane's spend belongs to the org's
                // bill, not the platform's.
                false,
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.cacheWriteTokens(),
                u.costUsd(),
                reportId == null ? null : new LlmUsageAccountant.Subject(SUBJECT_KIND, reportId));
    }

    /**
     * The project's {@link ModelLane#RCA} choice — Bedrock/mantle inference-profile id or, since
     * #939, a bare {@link ai.tessary.evals.llm.ModelCatalog} model name for one of the four new
     * providers — or empty when the project has chosen nothing (a project older than lane seeding;
     * every seeded project has a row). See {@link #providerFor} for how D4 resolves that gap now
     * that there is no deployment-level default provider left to fall through to.
     */
    private Optional<ProjectModelSettings.ResolvedAgenticModel> resolvedModel(String projectId) {
        return modelSettings.resolveAgenticModel(projectId, ModelLane.RCA);
    }

    /** The model id the sandbox agent runs, resolved from {@link #resolvedModel} or the deployment default. */
    private String model(String projectId) {
        return resolvedModel(projectId)
                .map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                .orElseGet(() -> observerProps.getAgentic().getModel());
    }

    /**
     * The provider whose org credential this run must carry (#939 D4). A resolved lane names its
     * own provider directly; an unresolved one (no stored row — only a project older than lane
     * seeding) defaults to {@link ModelProvider#BEDROCK}, this lane's historical default endpoint
     * (SigV4 Converse), since that is the only honest translation of "no explicit choice" left once
     * the launcher's deployment-wide ambient-identity fallback is gone. There is no silent
     * "whatever the launcher happens to be configured with" any more — the org either has a Bedrock
     * credential or {@link AgenticCredentialResolver#resolve} throws {@code MISSING_CREDENTIALS}.
     */
    private ModelProvider providerFor(String projectId) {
        return resolvedModel(projectId)
                .map(ProjectModelSettings.ResolvedAgenticModel::provider)
                .orElse(ModelProvider.BEDROCK);
    }

    @Override
    public String key() {
        return KEY;
    }

    /** Boot-time guard, mirroring {@code E2bAnalysisSandbox}: the sandbox is now RCA's only analysis
     *  path, so a blank launcher URL fails every RCA at run time; say so once at startup instead. */
    @PostConstruct
    void warnIfUnconfigured() {
        Agentic cfg = props.getAgentic();
        if (cfg.getLauncherUrl() == null || cfg.getLauncherUrl().isBlank()) {
            log.warn(
                    Markers.OPS,
                    "rca agentic.launcher-url is unset — every RCA will fail (the sandboxed agent is the only"
                            + " analysis path); set EVALS_RCA_AGENTIC_LAUNCHER_URL");
        }
    }

    @Override
    public SandboxRun run(SandboxRequest req) {
        Agentic cfg = props.getAgentic();
        if (cfg.getLauncherUrl() == null || cfg.getLauncherUrl().isBlank()) {
            throw new EvalsException(RcaError.UPSTREAM_FAILED, "agentic RCA launcher URL not configured");
        }
        // Standalone Langfuse trace (no run parent) — same attribute set as the observer's agentic span.
        Span span = tracer.spanBuilder("agentic-rca")
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            span.setAttribute("langfuse.trace.name", "agentic-rca");
            span.setAttribute("evals.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("evals.rca.subject_id", req.subjectId());
            span.setAttribute("evals.head_sha", req.headSha() == null ? "" : req.headSha());
            // One clone+analyze run == invoke_agent; without the discriminator Langfuse never types
            // this as a generation and the Alloy langfuse branch drops it (see E2bAnalysisSandbox).
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute("gen_ai.request.model", model(req.projectId()));

            ObjectNode body = mapper.createObjectNode();
            // clone_url and mcp.token are secrets — sent to the launcher, never logged. Both clone
            // fields are OMITTED rather than sent null for a repo-less project: the sandbox script
            // branches on their presence.
            if (req.cloneUrl() != null && req.headSha() != null) {
                body.put("clone_url", req.cloneUrl());
                body.put("head_sha", req.headSha());
            }
            ObjectNode files = body.putObject("files");
            req.files().forEach(files::put);
            body.put("prompt", req.prompt());
            body.put("json_schema", req.jsonSchema());
            Optional<ProjectModelSettings.ResolvedAgenticModel> resolved = resolvedModel(req.projectId());
            body.put(
                    "model",
                    resolved.map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                            .orElseGet(() -> model(req.projectId())));
            // #939 D4: full removal of the launcher's deployment-env-var credential path — the org's
            // own credential now travels ON the request, decrypted here and never logged (see
            // AgenticCredentialResolver's class javadoc). Throws MISSING_CREDENTIALS /
            // AGENTIC_IAM_ROLE_UNSUPPORTED BEFORE the launcher is ever called when there is no usable
            // credential, per D4's "fail closed before calling the launcher" rule. `provider` is also
            // sent plainly (it is not a secret) — the launcher's providerConfig()/toProviderModel()
            // still key off it to pick which OpenCode provider block to build.
            ModelProvider provider = providerFor(req.projectId());
            body.put("provider", provider.name());
            body.set("credential", mapper.valueToTree(credentials.resolve(req.projectId(), provider)));
            if (req.mcpUrl() != null && req.mcpToken() != null) {
                ObjectNode mcp = body.putObject("mcp");
                mcp.put("url", req.mcpUrl());
                mcp.put("token", req.mcpToken());
            }
            body.put("timeout_ms", cfg.getTimeoutMs());
            // B (#994): a soft turn cap — see Agentic#maxTurns's javadoc for the mechanism and the
            // (not yet live-verified) caveat.
            body.put("max_turns", cfg.getMaxTurns());

            // Host anchor for the per-turn child spans (in-VM timestamps are offsets from startMs).
            Instant runStart = Instant.now();
            String respBody = postLauncher(mapper.writeValueAsString(body), cfg, req.projectId(), req.reportId());
            JsonNode node = mapper.readTree(respBody);
            String raw = node.path("raw").asText("");
            AgentSpanTelemetry.recordUsage(span, mapper, raw);
            bookUsage(req.projectId(), req.reportId(), raw);
            // `structured_output` is the schema-constrained object the sandbox ALREADY extracted and
            // validated: agent-stream.js's runAgent refuses to exit 0 under `rejectOn: 'error'` unless
            // every key the response schema requires is present in it. `result` is the SAME answer in
            // the model's own raw reply text, which may arrive wrapped in a markdown fence or a
            // sentence of prose. Reading `result` first is what discarded a complete 4m48s / $0.80
            // investigation at its last step: RcaSynthesisOutput binds it strictly, so one fence or one
            // unexpected key threw the whole run away while the validated object sat unread beside it.
            // `result` stays the fallback for an envelope that carries no structured output at all.
            JsonNode envelope = mapper.readTree(raw);
            JsonNode structured = envelope.path("structured_output");
            String resultText = structured.isObject()
                    ? mapper.writeValueAsString(structured)
                    : envelope.path("result").asText("");
            if (resultText.isBlank()) {
                markError(span, "agent returned no result");
                throw new EvalsException(RcaError.UPSTREAM_FAILED, "agentic RCA run produced no result");
            }
            AgentSpanTelemetry.recordSpanIo(span, req.prompt(), resultText);
            // Render the agent's per-turn conversation as child llm_request spans so Langfuse shows
            // the back-and-forth instead of one opaque generation.
            AgentSpanTelemetry.recordTurns(
                    tracer, node.path("turns"), node.path("startMs").asLong(0L), runStart);
            return new SandboxRun(resultText);
        } catch (EvalsException e) {
            markError(span, e.getMessage() == null ? "agentic rca failed" : e.getMessage());
            span.recordException(e);
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn(Markers.OPS, "agentic rca failure subject={}: {}", req.subjectId(), e.toString());
            markError(span, e.toString());
            span.recordException(e);
            throw new EvalsException(RcaError.UPSTREAM_FAILED, e, "agentic RCA failed: " + e.getMessage());
        } finally {
            span.end();
        }
    }

    /**
     * The launcher-POST seam (package-private for tests): one {@code POST /rca}, returning the
     * response body. Throws {@link EvalsException} on a non-2xx status, a transport failure, or
     * interruption — this lane fails closed (see the class comment).
     *
     * <p>F1: {@code projectId}/{@code reportId} are here only so a failing run's body can be booked
     * (see {@link #bookUsage}) before the {@link EvalsException} below propagates — server.js's {@code
     * buildErrorBody} carries a {@code usage} object whenever rca.js's failure envelope reached it,
     * and {@code bookUsage} already no-ops on a body with nothing usable.
     */
    String postLauncher(String bodyJson, Agentic cfg, @Nullable String projectId, @Nullable String reportId) {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(cfg.getLauncherUrl() + "/rca"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .header("Authorization", "Bearer " + cfg.getLauncherApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // The launcher does clone + a multi-turn agent run within timeout_ms;
                // give the HTTP wait headroom beyond the sandbox wall clock.
                .timeout(Duration.ofMillis(cfg.getTimeoutMs() + 30_000))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalsException(CommonError.INTERRUPTED, e, "agentic rca");
        } catch (java.io.IOException e) {
            // The launcher never answered. This used to throw a bare "transport failure" that named
            // no host and carried no cause, so a launcher that simply was not running (its compose
            // service gated off) read in the UI as an LLM failure and left NOTHING in the logs to
            // contradict that. Log the target and the throwable — an infrastructure exception, so
            // the cause belongs on the record (backend/AGENTS.md § Logging).
            boolean timedOut = e instanceof java.net.http.HttpTimeoutException;
            log.error(
                    Markers.OPS,
                    "rca launcher unreachable launcherUrl={} timedOut={} cause={}",
                    cfg.getLauncherUrl(),
                    timedOut,
                    e.getClass().getSimpleName(),
                    e);
            throw new EvalsException(
                    RcaError.LAUNCHER_UNREACHABLE,
                    e,
                    cfg.getLauncherUrl()
                            + (timedOut ? " (timed out)" : " (" + e.getClass().getSimpleName() + ")"));
        }
        if (resp.statusCode() / 100 != 2) {
            // 401 here is almost always the backend's launcher key disagreeing with the launcher's
            // own SANDBOX_API_KEY; 404 means the image predates the /rca route. For everything else
            // the launcher's body already carries a classified, pre-scrubbed diagnosis
            // (buildErrorBody in sandbox-runner/launcher/server.js: kind/detail/sandbox_id/elapsed_ms
            // — never sandbox stdout/stderr or secrets, by that file's own hard rule), so read it
            // instead of leaving a bare status code as the whole story.
            String diag = describeLauncherError(resp.body());
            log.error(
                    Markers.OPS,
                    "rca launcher rejected the run launcherUrl={} status={}{}",
                    cfg.getLauncherUrl(),
                    resp.statusCode(),
                    diag.isBlank() ? "" : " " + diag);
            // F1: book what the run spent before it failed (a launcher outage carries no usage —
            // bookUsage no-ops on a body with nothing parseable — but today's always-502 run failure
            // does, whenever rca.js reached its catch block).
            bookUsage(projectId, reportId, resp.body());
            throw new EvalsException(
                    RcaError.UPSTREAM_FAILED,
                    "agentic RCA launcher HTTP " + resp.statusCode() + (diag.isBlank() ? "" : " (" + diag + ")"));
        }
        return resp.body();
    }

    /**
     * Turn the launcher's {@code buildErrorBody} JSON into a short "kind=... detail=... sandbox=..."
     * string for the log line and the report's summary. Best-effort: a body that fails to parse
     * (e.g. an upstream proxy's own 502 page) yields an empty string rather than a second failure.
     */
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
            if (n.has("elapsed_ms")) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append("elapsed_ms=").append(n.get("elapsed_ms").asLong());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void markError(Span span, String message) {
        span.setStatus(StatusCode.ERROR, message);
        span.setAttribute("langfuse.observation.level", "ERROR");
        span.setAttribute("langfuse.observation.status_message", message);
    }
}
