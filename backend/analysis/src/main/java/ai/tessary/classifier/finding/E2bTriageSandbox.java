// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static ai.tessary.sandbox.AgentSpanTelemetry.markError;

import ai.tessary.agentrun.E2bAgentRunSandbox;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.ObserverProperties.Agentic;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The microVM behind Layer-2 triage: one fresh sandbox per {@code POST /triage} that reads the finding's dossier and
 * this platform's MCP surface, with no repository clone. A run that produced nothing returns empty and the job retries,
 * so an outage never manufactures a ruling. A launcher fault throws {@code TRIAGE_LAUNCHER_UNAVAILABLE} so the worker
 * stops draining; {@link #classifyFailure} keeps a run's own 502 from tripping that breaker.
 */
@Service
public class E2bTriageSandbox implements TriageSandbox {

    public static final String KEY = "e2b";

    private static final Logger log = LoggerFactory.getLogger(E2bTriageSandbox.class);

    static final String OPERATION = "layer2-triage";

    /**
     * A ruling's unit of work is the finding, not the case: a case may never open, and the money is spent either way.
     */
    static final String SUBJECT_KIND = "behavior_finding";

    private final ObserverProperties props;
    private final ProjectModelSettings modelSettings;
    private final AgenticCredentialResolver credentials;

    private final LlmUsageAccountant usage;
    private final ModelsDevRates rates;
    private final Tracer tracer;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public E2bTriageSandbox(
            ObserverProperties props,
            ProjectModelSettings modelSettings,
            AgenticCredentialResolver credentials,
            LlmUsageAccountant usage,
            ModelsDevRates rates,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.modelSettings = modelSettings;
        this.credentials = credentials;
        this.usage = usage;
        this.rates = rates;
        // The class's own package, as every other sandbox here does.
        this.tracer = openTelemetry.getTracer("ai.tessary.classifier.finding");
        this.mapper = mapper;
    }

    @Override
    public String key() {
        return KEY;
    }

    /** A blank launcher URL dead-letters every finding's job; say so once at startup. */
    @PostConstruct
    void warnIfUnconfigured() {
        Agentic cfg = props.getAgentic();
        if (cfg.getLauncherUrl() == null || cfg.getLauncherUrl().isBlank()) {
            log.warn(
                    Markers.OPS,
                    "triage agentic.launcher-url is unset — no Layer-2 ruling can run and every triage job"
                            + " will dead-letter; set TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL");
        }
    }

    /** Empty means the run produced nothing usable; run and launcher failures throw distinct codes. */
    @Override
    public Optional<SandboxRun> run(SandboxRequest req) {
        Agentic cfg = props.getAgentic();
        if (cfg.getLauncherUrl() == null || cfg.getLauncherUrl().isBlank()) {
            // Thrown: an unset MCP door is a misconfiguration somebody has to see, not a run that found nothing.
            throw new TessaryException(
                    ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                    "tessary.observer.agentic.launcher-url is unset; set TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL");
        }
        Span span = tracer.spanBuilder(OPERATION)
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        AgenticCredentialResolver.@Nullable Credential credential = null;
        try (var _ = span.makeCurrent()) {
            Optional<ProjectModelSettings.ResolvedAgenticModel> resolved = resolvedModel(req.projectId());
            String model = resolved.map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                    .orElseGet(() -> props.getAgentic().getModel());
            // A Bedrock inference-profile id by default; see E2bRcaSandbox#modelsDevId.
            String modelsDevId = resolved.isPresent()
                    ? resolved.get().modelsDevId()
                    : ModelCatalog.modelsDevId(ModelProvider.BEDROCK, model).orElse(null);
            span.setAttribute("langfuse.trace.name", OPERATION);
            span.setAttribute("tessary.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("tessary.triage.finding_id", req.findingId());
            // Without the discriminator Langfuse never types this as a generation and Alloy drops it.
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute("gen_ai.request.model", model);

            ObjectNode body = mapper.createObjectNode();
            ObjectNode files = body.putObject("files");
            req.files().forEach(files::put);
            body.put("prompt", req.prompt());
            body.put("json_schema", req.jsonSchema());
            body.put("model", model);
            rates.putModelCost(body, modelsDevId);
            // Decrypted here and never logged. Throws before the launcher is called when there is no usable credential;
            // an unresolved lane defaults to BEDROCK.
            ModelProvider provider = resolved.map(ProjectModelSettings.ResolvedAgenticModel::provider)
                    .orElse(ModelProvider.BEDROCK);
            body.put("provider", provider.name());
            credential = credentials.resolve(req.projectId(), provider);
            body.set("credential", mapper.valueToTree(credential));
            // A live platform key: never logged. Required, since the dossier alone lets the agent only restate the
            // claim.
            ObjectNode mcp = body.putObject("mcp");
            mcp.put("url", req.mcpUrl());
            mcp.put("token", req.mcpToken());
            body.put("timeout_ms", cfg.getTimeoutMs());
            body.put("max_turns", cfg.getMaxTurns());
            // A null system_prompt makes the sandbox runner use no custom agent and no MCP relay.
            body.put("system_prompt", req.systemPrompt());

            Instant runStart = Instant.now();
            String respBody = postLauncher(
                    mapper.writeValueAsString(body),
                    cfg,
                    req.projectId(),
                    req.findingId(),
                    model,
                    credential.platformFunded());
            if (respBody == null) {
                markError(span, "launcher did not answer");
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(respBody);
            String raw = node.path("raw").asText("");
            if (raw.isBlank()) {
                // postLauncher already booked any usage this body carried.
                markError(span, "agent returned no result");
                return Optional.empty();
            }
            AgentSpanTelemetry.recordUsage(span, mapper, raw);
            bookUsage(req.projectId(), req.findingId(), model, raw, credential.platformFunded());
            String resultText = mapper.readTree(raw).path("result").asText("");
            if (resultText.isBlank()) {
                markError(span, "agent returned no result");
                return Optional.empty();
            }
            AgentSpanTelemetry.recordSpanIo(span, req.prompt(), resultText);
            AgentSpanTelemetry.recordTurns(
                    tracer, node.path("turns"), node.path("startMs").asLong(0L), runStart);
            return Optional.of(new SandboxRun(resultText));
        } catch (InterruptedException e) {
            // Cancellation must propagate rather than be swallowed as "no ruling".
            Thread.currentThread().interrupt();
            markError(span, "interrupted");
            return Optional.empty();
        } catch (TessaryException e) {
            // Rethrown so the worker sees a launcher-level verdict.
            markError(span, e.toString());
            span.recordException(e);
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn(Markers.OPS, "triage run failed finding={}: {}", req.findingId(), e.toString());
            markError(span, e.toString());
            span.recordException(e);
            return Optional.empty();
        } finally {
            if (credential != null) credentials.release(credential);
            span.end();
        }
    }

    /**
     * Null means the launcher declined THIS request; {@code TRIAGE_LAUNCHER_UNAVAILABLE} means the launcher is the
     * problem. Connect failures, 401/403/404 and launcher-kind 5xx blame the launcher; read timeouts, run-kind 502s and
     * other 4xx blame the run, so one slow finding cannot trip the breaker. Usage is booked before the branch, since a
     * failed run spends tokens too.
     */
    private @Nullable String postLauncher(
            String bodyJson, Agentic cfg, String projectId, String findingId, String model, boolean platformFunded)
            throws InterruptedException {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(cfg.getLauncherUrl() + "/triage"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .header("Authorization", "Bearer " + cfg.getLauncherApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // Headroom beyond the sandbox's own wall clock.
                .timeout(Duration.ofMillis(cfg.getTimeoutMs() + 30_000))
                .build();
        HttpResponse<String> resp;
        try {
            resp = send(httpReq);
        } catch (java.io.IOException e) {
            boolean timedOut = e instanceof java.net.http.HttpTimeoutException;
            log.error(
                    Markers.OPS,
                    "triage launcher unreachable launcherUrl={} timedOut={} cause={}",
                    cfg.getLauncherUrl(),
                    timedOut,
                    e.getClass().getSimpleName(),
                    e);
            if (timedOut) return null;
            throw new TessaryException(
                    ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                    e,
                    "unreachable at " + cfg.getLauncherUrl() + " ("
                            + e.getClass().getSimpleName() + ")");
        }
        if (resp.statusCode() / 100 != 2) {
            // 404 almost certainly means the launcher image predates the /triage route.
            String diag = E2bAgentRunSandbox.describeLauncherError(resp.body());
            log.error(
                    Markers.OPS,
                    "triage launcher rejected the run launcherUrl={} status={}{}",
                    cfg.getLauncherUrl(),
                    resp.statusCode(),
                    resp.statusCode() == 404
                            ? " (no /triage route — is the launcher older than the backend?)"
                            : diag.isBlank() ? "" : " " + diag);
            bookUsage(projectId, findingId, model, resp.body(), platformFunded);
            FailureClass failure = classifyFailure(resp.statusCode(), resp.body());
            if (failure == FailureClass.MISCONFIGURED) {
                // 401/403/404 answer identically until the deployment changes, so they carry the code the worker parks
                // on.
                throw new TessaryException(
                        ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED,
                        launcherDiagnosis(resp.statusCode(), cfg.getLauncherUrl(), diag));
            }
            if (failure == FailureClass.LAUNCHER_UNAVAILABLE) {
                throw new TessaryException(
                        ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                        launcherDiagnosis(resp.statusCode(), cfg.getLauncherUrl(), diag));
            }
            ClassifierError providerError =
                    switch (failure) {
                        case PROVIDER_REJECTED -> ClassifierError.TRIAGE_PROVIDER_REJECTED;
                        case REQUEST_REFUSED -> ClassifierError.TRIAGE_REQUEST_REFUSED;
                        case PROVIDER_UNAVAILABLE -> ClassifierError.TRIAGE_PROVIDER_UNAVAILABLE;
                        default -> null;
                    };
            if (providerError != null) {
                throw new TessaryException(providerError, diag.isBlank() ? ("status " + resp.statusCode()) : diag);
            }
            // Thrown so the dead-lettered job's last_error carries the launcher's own diagnosis.
            throw new TessaryException(
                    ClassifierError.TRIAGE_RUN_INCOMPLETE,
                    findingId,
                    diag.isBlank() ? ("status " + resp.statusCode()) : diag);
        }
        return resp.body();
    }

    /** The one socket round-trip, split out so tests can stub the launcher. */
    HttpResponse<String> send(HttpRequest req) throws java.io.IOException, InterruptedException {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    enum FailureClass {
        RUN,
        MISCONFIGURED,
        LAUNCHER_UNAVAILABLE,
        PROVIDER_REJECTED,
        REQUEST_REFUSED,
        PROVIDER_UNAVAILABLE
    }

    /** The launcher's {@code kind} values that mean the agent run failed, not the launcher. */
    private static final Set<String> RUN_FAILURE_KINDS = Set.of("timeout", "script_exit", "bad_output", "bad_request");

    /** The kinds a lane names when the model provider stopped it, so none of them blames the launcher. */
    private static final Map<String, FailureClass> PROVIDER_FAILURE_KINDS = Map.of(
            "provider_rejected", FailureClass.PROVIDER_REJECTED,
            "request_refused", FailureClass.REQUEST_REFUSED,
            "provider_unavailable", FailureClass.PROVIDER_UNAVAILABLE);

    /**
     * Pure, so tests drive every status and kind. 401/403/404 are MISCONFIGURED; a 502 with a
     * {@link #RUN_FAILURE_KINDS} kind is RUN, and one with a {@link #PROVIDER_FAILURE_KINDS} kind is its own class;
     * other 5xx, including a 502 with no recognized kind (a proxy's page), are LAUNCHER_UNAVAILABLE; other 4xx are RUN.
     */
    static FailureClass classifyFailure(int status, String body) {
        if (status == 401 || status == 403 || status == 404) {
            return FailureClass.MISCONFIGURED;
        }
        if (status / 100 == 5) {
            if (status != 502) return FailureClass.LAUNCHER_UNAVAILABLE;
            String kind = E2bAgentRunSandbox.kindOf(body);
            if (RUN_FAILURE_KINDS.contains(kind)) return FailureClass.RUN;
            return PROVIDER_FAILURE_KINDS.getOrDefault(kind, FailureClass.LAUNCHER_UNAVAILABLE);
        }
        return FailureClass.RUN;
    }

    private void bookUsage(
            String projectId, String findingId, String model, String envelopeJson, boolean platformFunded) {
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (u == null) return;
        usage.recordSandboxRun(
                projectId,
                ModelLane.TRIAGE.wire(),
                model,
                platformFunded,
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.cacheWriteTokens(),
                u.costUsd(),
                // Per finding, so the ledger can price an agent session per distinct cause.
                new LlmUsageAccountant.Subject(SUBJECT_KIND, findingId));
    }

    /** Falls back to the observer's agentic model, so a project that chose nothing behaves as before. */
    private Optional<ProjectModelSettings.ResolvedAgenticModel> resolvedModel(String projectId) {
        return modelSettings.resolveAgenticModel(projectId, ModelLane.TRIAGE);
    }

    /** Names the remedy, because a bare "401" has cost a day before. */
    private static String launcherDiagnosis(int status, String launcherUrl, String diag) {
        String remedy =
                switch (status) {
                    case 401, 403 ->
                        " — the shared secret does not match; TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY must equal"
                                + " the launcher's SANDBOX_API_KEY";
                    case 404 -> " — no /triage route; the launcher image is older than this backend";
                    default -> diag.isBlank() ? "" : " — " + diag;
                };
        return "status " + status + " from " + launcherUrl + remedy;
    }
}
