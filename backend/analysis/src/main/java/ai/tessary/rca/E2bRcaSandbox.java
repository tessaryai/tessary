// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.config.ObserverProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.RcaProperties.Agentic;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
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
 * Model credentials are resolved and decrypted here via {@link AgenticCredentialResolver}, never logged. Every failure
 * throws {@link TessaryException}: a user-triggered RCA must stamp {@code failed} rather than degrade to a bogus "no
 * cause found".
 */
@Service
public class E2bRcaSandbox implements RcaSandbox {

    public static final String KEY = "e2b";

    private static final Logger log = LoggerFactory.getLogger(E2bRcaSandbox.class);

    private final RcaProperties props;
    /** RCA has no model config of its own; a project can override it on the {@link ModelLane#RCA} lane. */
    private final ObserverProperties observerProps;

    private final ProjectModelSettings modelSettings;
    private final AgenticCredentialResolver credentials;

    private final LlmUsageAccountant usage;
    private final ModelsDevRates rates;
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
            ModelsDevRates rates,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.observerProps = observerProps;
        this.modelSettings = modelSettings;
        this.credentials = credentials;
        this.usage = usage;
        this.rates = rates;
        this.tracer = openTelemetry.getTracer("ai.tessary.rca");
        this.mapper = mapper;
    }

    static final String SUBJECT_KIND = "rca_report";

    /**
     * Booked against the report this run investigated: {@code SandboxRequest.subjectId} is the mover's failure subject,
     * which has no honest {@code subject_kind}.
     */
    private void bookUsage(String projectId, String reportId, String envelopeJson, boolean platformFunded) {
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (u == null) return;
        usage.recordSandboxRun(
                projectId,
                ModelLane.RCA.wire(),
                model(projectId),
                platformFunded,
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.cacheWriteTokens(),
                u.costUsd(),
                new LlmUsageAccountant.Subject(SUBJECT_KIND, reportId));
    }

    /**
     * The project's {@link ModelLane#RCA} choice — Bedrock/mantle inference-profile id or a bare
     * {@link ai.tessary.llm.ModelCatalog} model name for one of the four new providers — or empty
     * when the project has chosen nothing (a project older than lane seeding; every seeded project
     * has a row). See {@link #providerFor} for how that gap is resolved now that there is no
     * deployment-level default provider left to fall through to.
     */
    private Optional<ProjectModelSettings.ResolvedAgenticModel> resolvedModel(String projectId) {
        return modelSettings.resolveAgenticModel(projectId, ModelLane.RCA);
    }

    private String model(String projectId) {
        return resolvedModel(projectId)
                .map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                .orElseGet(() -> observerProps.getAgentic().getModel());
    }

    /** The deployment default is a Bedrock inference-profile id, so it is looked up on Bedrock. */
    private @Nullable String modelsDevId(String projectId) {
        Optional<ProjectModelSettings.ResolvedAgenticModel> resolved = resolvedModel(projectId);
        // A resolved model with no models.dev id is unpriced; it must never fall back to the default's rates.
        if (resolved.isPresent()) return resolved.get().modelsDevId();
        return ModelCatalog.modelsDevId(
                        ModelProvider.BEDROCK, observerProps.getAgentic().getModel())
                .orElse(null);
    }

    /**
     * An unresolved lane defaults to {@link ModelProvider#BEDROCK}; the org either has a Bedrock credential or
     * {@link AgenticCredentialResolver#resolve} throws {@code MISSING_CREDENTIALS}.
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

    /** The sandbox is RCA's only path, so a blank launcher URL fails every run; say so once at startup. */
    @PostConstruct
    void warnIfUnconfigured() {
        Agentic cfg = props.agentic();
        if (cfg.launcherUrl() == null || cfg.launcherUrl().isBlank()) {
            log.warn(
                    Markers.OPS,
                    "rca agentic.launcher-url is unset — every RCA will fail (the sandboxed agent is the only"
                            + " analysis path); set TESSARY_RCA_AGENTIC_LAUNCHER_URL");
        }
    }

    @Override
    public SandboxRun run(SandboxRequest req) {
        Agentic cfg = props.agentic();
        if (cfg.launcherUrl() == null || cfg.launcherUrl().isBlank()) {
            throw new TessaryException(RcaError.UPSTREAM_FAILED, "agentic RCA launcher URL not configured");
        }
        Span span = tracer.spanBuilder("agentic-rca")
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        AgenticCredentialResolver.@Nullable Credential credential = null;
        try (var _ = span.makeCurrent()) {
            span.setAttribute("langfuse.trace.name", "agentic-rca");
            span.setAttribute("tessary.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("tessary.rca.subject_id", req.subjectId());
            span.setAttribute("tessary.head_sha", req.headSha() == null ? "" : req.headSha());
            // Without the invoke_agent discriminator Langfuse never types this as a generation and the Alloy langfuse
            // branch drops it.
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute("gen_ai.request.model", model(req.projectId()));

            ObjectNode body = mapper.createObjectNode();
            // clone_url and mcp.token are secrets, never logged. The clone fields are omitted, not null, for a
            // repo-less project: the sandbox script branches on their presence.
            if (req.cloneUrl() != null && req.headSha() != null) {
                body.put("clone_url", req.cloneUrl());
                body.put("head_sha", req.headSha());
                if (req.onsetAt() != null) body.put("onset_at", req.onsetAt());
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
            rates.putModelCost(body, modelsDevId(req.projectId()));
            // Throws before the launcher is called when there is no usable credential. `provider` still picks the
            // OpenCode provider block in the launcher.
            ModelProvider provider = providerFor(req.projectId());
            body.put("provider", provider.name());
            credential = credentials.resolve(req.projectId(), provider);
            body.set("credential", mapper.valueToTree(credential));
            ObjectNode mcp = body.putObject("mcp");
            mcp.put("url", req.mcpUrl());
            mcp.put("token", req.mcpToken());
            body.put("timeout_ms", cfg.timeoutMs());
            body.put("max_turns", cfg.maxTurns());

            Instant runStart = Instant.now();
            String respBody = postLauncher(mapper.writeValueAsString(body), cfg, req.projectId(), req.reportId());
            JsonNode node = mapper.readTree(respBody);
            String raw = node.path("raw").asText("");
            AgentSpanTelemetry.recordUsage(span, mapper, raw);
            bookUsage(req.projectId(), req.reportId(), raw, credential.platformFunded());
            // Prefer `structured_output`, which the sandbox already validated against the schema: `result` is the raw
            // reply, possibly fenced or wrapped in prose, and binding it strictly once threw away a complete run at its
            // last step.
            JsonNode envelope = mapper.readTree(raw);
            JsonNode structured = envelope.path("structured_output");
            String resultText = structured.isObject()
                    ? mapper.writeValueAsString(structured)
                    : envelope.path("result").asText("");
            if (resultText.isBlank()) {
                markError(span, "agent returned no result");
                throw new TessaryException(RcaError.UPSTREAM_FAILED, "agentic RCA run produced no result");
            }
            AgentSpanTelemetry.recordSpanIo(span, req.prompt(), resultText);
            AgentSpanTelemetry.recordTurns(
                    tracer, node.path("turns"), node.path("startMs").asLong(0L), runStart);
            return new SandboxRun(resultText);
        } catch (TessaryException e) {
            markError(span, e.getMessage() == null ? "agentic rca failed" : e.getMessage());
            span.recordException(e);
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn(Markers.OPS, "agentic rca failure subject={}: {}", req.subjectId(), e.toString());
            markError(span, e.toString());
            span.recordException(e);
            throw new TessaryException(RcaError.UPSTREAM_FAILED, e, "agentic RCA failed: " + e.getMessage());
        } finally {
            if (credential != null) credentials.release(credential);
            span.end();
        }
    }

    /**
     * Throws {@link TessaryException} on any failure. {@code projectId}/{@code reportId} are here so a failing run's
     * usage can still be booked.
     */
    @ExcludeFromJacocoGeneratedReport(
            "parses the body this class serialized one call earlier, so the checked catch cannot fire")
    private boolean platformFunded(String bodyJson) {
        try {
            return mapper.readTree(bodyJson)
                    .path("credential")
                    .path("platform_funded")
                    .asBoolean(false);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(cfg.launcherUrl() + "/rca"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .header("Authorization", "Bearer " + cfg.launcherApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // Give the HTTP wait headroom beyond the sandbox wall clock.
                .timeout(Duration.ofMillis(cfg.timeoutMs() + 30_000))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(CommonError.INTERRUPTED, e, "agentic rca");
        } catch (java.io.IOException e) {
            // A launcher that is not running must not read as an LLM failure, so log the target and the cause.
            boolean timedOut = e instanceof java.net.http.HttpTimeoutException;
            log.error(
                    Markers.OPS,
                    "rca launcher unreachable launcherUrl={} timedOut={} cause={}",
                    cfg.launcherUrl(),
                    timedOut,
                    e.getClass().getSimpleName(),
                    e);
            throw new TessaryException(
                    RcaError.LAUNCHER_UNREACHABLE,
                    e,
                    cfg.launcherUrl()
                            + (timedOut ? " (timed out)" : " (" + e.getClass().getSimpleName() + ")"));
        }
        if (resp.statusCode() / 100 != 2) {
            // 401 is almost always a launcher-key mismatch with SANDBOX_API_KEY; 404 means the image predates /rca.
            // Otherwise the body carries a pre-scrubbed diagnosis from the launcher's buildErrorBody.
            String diag = describeLauncherError(resp.body());
            log.error(
                    Markers.OPS,
                    "rca launcher rejected the run launcherUrl={} status={}{}",
                    cfg.launcherUrl(),
                    resp.statusCode(),
                    diag.isBlank() ? "" : " " + diag);
            // Book what the run spent before it failed. postLauncher's signature is pinned by test overrides, so the
            // funding flag is read back from the body sent.
            bookUsage(projectId, reportId, resp.body(), platformFunded(bodyJson));
            throw new TessaryException(
                    RcaError.UPSTREAM_FAILED,
                    "agentic RCA launcher HTTP " + resp.statusCode() + (diag.isBlank() ? "" : " (" + diag + ")"));
        }
        return resp.body();
    }

    /** Best-effort: an unparseable body (e.g. a proxy's own 502 page) yields an empty string. */
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
