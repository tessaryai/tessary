// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.config.ObserverProperties;
import ai.tessary.config.ObserverProperties.Agentic;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
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
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The microVM behind Layer-2 triage: one {@code POST /triage} == one fresh sandbox that materializes
 * the finding's dossier, runs the agent against it and against this platform's own MCP surface, and
 * tears down.
 *
 * <h2>No repository clone</h2>
 *
 * <p>Triage audits a claim: is it true, was it measured over enough, does the evidence carry it.
 * No source file answers that. The dossier is the finding's own two files plus a short-lived
 * project-scoped MCP key ({@code mcp: {url, token}}, the same field {@code E2bRcaSandbox} sends):
 * the agent pages the evidence refs and reads the traces it decides to read, then cites the ids it
 * fetched.
 *
 * <h2>A failed run fails open; a failed launcher does not</h2>
 *
 * <p>A run that happened and produced nothing usable returns {@link Optional#empty()}.
 * {@link BehaviorTriageEngine} turns that into a thrown {@code TRIAGE_RUN_INCOMPLETE}, the job
 * retries and eventually dead-letters, and {@code triage_verdict} stays NULL: a launcher outage
 * cannot manufacture a ruling.
 *
 * <p>A launcher that could not be reached, rejected the credentials, has no {@code /triage} route,
 * or failed internally is a different fact and throws {@code TRIAGE_LAUNCHER_UNAVAILABLE}: it is
 * not about this finding and will be just as true for the next one, so the worker refunds the
 * attempt and stops draining instead of spending every queued job's attempts against a shut door.
 *
 * <p>Not every 502 is the launcher's fault, though — the launcher answers a run it started but
 * could not finish (an agent timeout, a rejection, unparseable output, a bad request body) with a
 * 502 of its own, carrying a {@code kind} that says which. {@link #classifyFailure} reads that kind:
 * {@code timeout}, {@code script_exit}, {@code bad_output} and {@code bad_request} are THIS run's
 * problem and throw {@code TRIAGE_RUN_INCOMPLETE} instead, so a finding that always times out
 * dead-letters on its own after a few attempts rather than tripping the breaker and pausing every
 * other finding's triage. {@code orchestration} (the launcher's own infrastructure failed to even
 * start the run) and a missing or unrecognized kind (a proxy's own 502 page, not the launcher's)
 * stay launcher-level.
 */
@Service
public class E2bTriageSandbox implements TriageSandbox {

    /** Selector key for {@code tessary.classifier.triage-sandbox} — the only driver that ships today. */
    public static final String KEY = "e2b";

    private static final Logger log = LoggerFactory.getLogger(E2bTriageSandbox.class);

    /** Telemetry name for this run's root span and Langfuse trace. */
    static final String OPERATION = "layer2-triage";

    /**
     * The ledger's subject kind for a ruling: the table its {@code subject_id} points into. A ruling's
     * unit of work is the finding, not the case — a case may never open, and the money is spent either way.
     */
    static final String SUBJECT_KIND = "behavior_finding";

    private final ObserverProperties props;
    private final ProjectModelSettings modelSettings;
    /** Resolves and decrypts the org's own credential for the sandbox request body. */
    private final AgenticCredentialResolver credentials;

    private final LlmUsageAccountant usage;
    private final Tracer tracer;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public E2bTriageSandbox(
            ObserverProperties props,
            ProjectModelSettings modelSettings,
            AgenticCredentialResolver credentials,
            LlmUsageAccountant usage,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.modelSettings = modelSettings;
        this.credentials = credentials;
        this.usage = usage;
        // The instrumentation scope is this class's own package, matching the convention every other
        // sandbox here follows (E2bRcaSandbox names ai.tessary.rca). A trace query filtering on a stale scope goes
        // empty rather than
        // wrong, which beats freezing a string that lies about where the code lives.
        this.tracer = openTelemetry.getTracer("ai.tessary.classifier.finding");
        this.mapper = mapper;
    }

    @Override
    public String key() {
        return KEY;
    }

    /**
     * Boot-time guard. The sandbox is triage's only path, so a blank launcher URL means every
     * finding's job burns its attempts and dead-letters — say so once at startup rather than once
     * per finding.
     */
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

    /**
     * Run one triage. Empty means the run happened and produced nothing usable; a run failure throws
     * {@code TRIAGE_RUN_INCOMPLETE} with the launcher's own diagnosis, and a launcher-level failure
     * throws {@code TRIAGE_LAUNCHER_UNAVAILABLE} or {@code TRIAGE_LAUNCHER_MISCONFIGURED}.
     */
    @Override
    public Optional<SandboxRun> run(SandboxRequest req) {
        Agentic cfg = props.getAgentic();
        if (cfg.getLauncherUrl() == null || cfg.getLauncherUrl().isBlank()) {
            // Thrown rather than skipped, on the same reasoning the MCP door already uses in
            // BehaviorTriageEngine#rule: an unset door is a misconfiguration somebody has to see, and
            // returning empty here made it indistinguishable from a run that simply found nothing.
            throw new TessaryException(
                    ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                    "tessary.observer.agentic.launcher-url is unset; set TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL");
        }
        Span span = tracer.spanBuilder(OPERATION)
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            Optional<ProjectModelSettings.ResolvedAgenticModel> resolved = resolvedModel(req.projectId());
            String model = resolved.map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                    .orElseGet(() -> props.getAgentic().getModel());
            String pricingId = resolved.map(ProjectModelSettings.ResolvedAgenticModel::pricingId)
                    .orElseGet(() -> props.getAgentic().getModel());
            span.setAttribute("langfuse.trace.name", OPERATION);
            span.setAttribute("tessary.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("tessary.triage.finding_id", req.findingId());
            // Without the discriminator Langfuse never types this as a generation and the Alloy
            // langfuse branch drops it outright.
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute("gen_ai.request.model", model);

            ObjectNode body = mapper.createObjectNode();
            ObjectNode files = body.putObject("files");
            req.files().forEach(files::put);
            body.put("prompt", req.prompt());
            body.put("json_schema", req.jsonSchema());
            body.put("model", model);
            // The org's own credential travels on the request, decrypted here and never logged (see
            // AgenticCredentialResolver's class javadoc). Throws MISSING_CREDENTIALS or
            // AGENTIC_IAM_ROLE_UNSUPPORTED before the launcher is ever called when there is no usable
            // credential. An unresolved lane (no stored row) defaults to BEDROCK; see
            // E2bRcaSandbox#providerFor's javadoc.
            ModelProvider provider = resolved.map(ProjectModelSettings.ResolvedAgenticModel::provider)
                    .orElse(ModelProvider.BEDROCK);
            body.put("provider", provider.name());
            AgenticCredentialResolver.Credential credential = credentials.resolve(req.projectId(), provider);
            body.set("credential", mapper.valueToTree(credential));
            // mcp.token is a live platform key — sent to the launcher, never logged. Not optional on
            // this lane: the dossier carries the detector's numbers and nothing else, so an agent
            // without this reads no trace at all and can only restate the claim back at us.
            ObjectNode mcp = body.putObject("mcp");
            mcp.put("url", req.mcpUrl());
            mcp.put("token", req.mcpToken());
            body.put("timeout_ms", cfg.getTimeoutMs());
            // A soft turn cap; see Agentic#maxTurns's javadoc for the mechanism and caveat.
            body.put("max_turns", cfg.getMaxTurns());
            // BehaviorTriageEngine always sends its one shared system prompt; the sandbox-side runner
            // (triage.js/agent-stream.js) treats a missing/null system_prompt as "run exactly as before
            // this field existed" — no custom agent, no MCP relay — which stays true for any other
            // caller of this interface with nothing agent-specific to send.
            body.put("system_prompt", req.systemPrompt());

            // Host anchor for the per-turn child spans (in-VM timestamps are offsets from startMs).
            Instant runStart = Instant.now();
            String respBody = postLauncher(
                    mapper.writeValueAsString(body),
                    cfg,
                    req.projectId(),
                    req.findingId(),
                    model,
                    pricingId,
                    credential.platformFunded());
            if (respBody == null) {
                markError(span, "launcher did not answer");
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(respBody);
            String raw = node.path("raw").asText("");
            if (raw.isBlank()) {
                // A run-level failure body (server.js's buildErrorBody has no `raw`); postLauncher
                // already booked any usage it carried before returning it here, so there is nothing
                // left to do but stop treating this as a completed run.
                markError(span, "agent returned no result");
                return Optional.empty();
            }
            AgentSpanTelemetry.recordUsage(span, mapper, raw);
            bookUsage(req.projectId(), req.findingId(), model, pricingId, raw, credential.platformFunded());
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
            // Cancellation must propagate as an interrupt rather than be swallowed as "no ruling".
            Thread.currentThread().interrupt();
            markError(span, "interrupted");
            return Optional.empty();
        } catch (TessaryException e) {
            // A launcher-level verdict the worker must see as such. Recorded on the span, then rethrown
            // rather than folded into the empty that means "the run produced nothing".
            markError(span, e.toString());
            span.recordException(e);
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn(Markers.OPS, "triage run failed finding={}: {}", req.findingId(), e.toString());
            markError(span, e.toString());
            span.recordException(e);
            return Optional.empty();
        } finally {
            span.end();
        }
    }

    /**
     * One {@code POST /triage}. Returns the response body; null means the launcher answered and
     * declined THIS request, and {@code TRIAGE_LAUNCHER_UNAVAILABLE} means the launcher itself is the
     * problem.
     *
     * <p>The split, and why each side falls where it does:
     *
     * <ul>
     *   <li><b>Connect failure → launcher.</b> Nothing was reached, so nothing about this finding was
     *       tested.
     *   <li><b>Read timeout → run.</b> The launcher answered the connection and then this agent took
     *       too long. A slow finding must not trip the breaker for every other one.
     *   <li><b>401 / 403 → launcher.</b> A credential that is wrong now is wrong for the next finding
     *       too. This is the case that spent ~125 attempts against a shut door.
     *   <li><b>404 → launcher.</b> No {@code /triage} route: the sidecar is older than the backend, and
     *       no amount of retrying ships a new image.
     *   <li><b>5xx → split by {@link #classifyFailure}.</b> A 502 carrying a {@code kind} of
     *       {@code timeout}, {@code script_exit}, {@code bad_output} or {@code bad_request} is a run
     *       failure — the launcher started this run and it did not finish cleanly. Everything else
     *       (an {@code orchestration} kind, a missing/unrecognized kind, or any non-502 5xx) is the
     *       launcher's own fault.
     *   <li><b>Other 4xx → run.</b> The launcher understood us and refused this payload, which is a
     *       fact about this request.
     * </ul>
     *
     * <p>{@code projectId}/{@code findingId}/{@code model}/{@code pricingId} are here only so a
     * failing run's body can be booked against the ledger before this method returns or throws:
     * server.js's {@code buildErrorBody} carries a {@code usage} object whenever the agent burned
     * tokens before the run failed. {@link #bookUsage} already no-ops on a body with nothing usable,
     * so this is safe to call on every non-2xx response — booked before the branch below decides who
     * is at fault, since a run failure spends tokens exactly as a completed run does.
     */
    private @Nullable String postLauncher(
            String bodyJson,
            Agentic cfg,
            String projectId,
            String findingId,
            String model,
            String pricingId,
            boolean platformFunded)
            throws InterruptedException {
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(cfg.getLauncherUrl() + "/triage"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .header("Authorization", "Bearer " + cfg.getLauncherApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // The launcher runs a multi-turn agent within timeout_ms; give the HTTP wait
                // headroom beyond the sandbox wall clock.
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
            // 404 here almost certainly means the deployed launcher image predates the /triage
            // route — the backend and the sidecar have to ship together, and this is what it looks
            // like when they did not. Everything else carries the launcher's own classified,
            // pre-scrubbed diagnosis in the body.
            String diag = describeLauncherError(resp.body());
            log.error(
                    Markers.OPS,
                    "triage launcher rejected the run launcherUrl={} status={}{}",
                    cfg.getLauncherUrl(),
                    resp.statusCode(),
                    resp.statusCode() == 404
                            ? " (no /triage route — is the launcher older than the backend?)"
                            : diag.isBlank() ? "" : " " + diag);
            // Book what the run spent before it failed, regardless of which bucket the status falls
            // into below: a launcher outage carries no usage (bookUsage no-ops on one), and a run
            // failure is exactly the case this exists for.
            bookUsage(projectId, findingId, model, pricingId, resp.body(), platformFunded);
            FailureClass failure = classifyFailure(resp.statusCode(), resp.body());
            if (failure == FailureClass.MISCONFIGURED) {
                // A refusal is not an outage. 401/403/404 will answer identically until somebody
                // changes the deployment, so it carries the code the worker parks on rather than the
                // one it retries on a breaker cycle.
                throw new TessaryException(
                        ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED,
                        launcherDiagnosis(resp.statusCode(), cfg.getLauncherUrl(), diag));
            }
            if (failure == FailureClass.LAUNCHER_UNAVAILABLE) {
                throw new TessaryException(
                        ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                        launcherDiagnosis(resp.statusCode(), cfg.getLauncherUrl(), diag));
            }
            // FailureClass.RUN: this request's own fault, not the launcher's. Thrown here rather than
            // returned for the caller to fold into an empty run, so the dead-lettered job's last_error
            // carries the launcher's own classified diagnosis (kind=... detail=...) instead of the
            // generic "the sandbox did not run" BehaviorTriageEngine falls back to when nothing about
            // the failure was ever reported.
            throw new TessaryException(
                    ClassifierError.TRIAGE_RUN_INCOMPLETE,
                    findingId,
                    diag.isBlank() ? ("status " + resp.statusCode()) : diag);
        }
        return resp.body();
    }

    /**
     * The one socket round-trip {@link #postLauncher} makes, split out so
     * {@code E2bTriageSandboxTest} can stub the launcher's answer without a live one — everything
     * around this call (the request body, the status/kind classification, booking usage) stays real.
     */
    HttpResponse<String> send(HttpRequest req) throws java.io.IOException, InterruptedException {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** The three ways a non-2xx {@code /triage} response resolves; see {@link #classifyFailure}. */
    enum FailureClass {
        /** This request's own fault: a run failure, spends the finding's attempt and retries. */
        RUN,
        /** The launcher is up and refusing every request identically until a person fixes it. */
        MISCONFIGURED,
        /** The launcher could not do its job this time; the breaker should stop the drain. */
        LAUNCHER_UNAVAILABLE
    }

    /** The launcher's own {@code kind} values (server.js's {@code buildErrorBody}) that mean the AGENT
     *  run failed rather than the launcher itself — see the class javadoc's "A failed run fails open"
     *  section for what each one is. */
    private static final Set<String> RUN_FAILURE_KINDS = Set.of("timeout", "script_exit", "bad_output", "bad_request");

    /**
     * Who is at fault for one non-2xx {@code /triage} response — pure, so the table in
     * {@code E2bTriageSandboxTest} can drive every status/kind combination without a live launcher.
     *
     * <p>401/403/404 are always {@link FailureClass#MISCONFIGURED}: the launcher answered and refused,
     * on terms that will not change until a person edits the deployment. A 502 is split by its {@code
     * kind}: {@link #RUN_FAILURE_KINDS} means the launcher started this run and it did not finish
     * cleanly, so it is {@link FailureClass#RUN}; {@code orchestration}, a missing/unrecognized kind (a
     * proxy's own 502 page, not the launcher's), or any other 5xx status all mean the launcher itself is
     * the problem. Every remaining 4xx is a run failure, unchanged from before this split existed: the
     * launcher understood the request and refused this payload, which is a fact about this request, not
     * about the launcher.
     */
    static FailureClass classifyFailure(int status, String body) {
        if (status == 401 || status == 403 || status == 404) {
            return FailureClass.MISCONFIGURED;
        }
        if (status / 100 == 5) {
            return status == 502 && RUN_FAILURE_KINDS.contains(kindOf(body))
                    ? FailureClass.RUN
                    : FailureClass.LAUNCHER_UNAVAILABLE;
        }
        return FailureClass.RUN;
    }

    /** The launcher error body's {@code kind} field; blank when absent or the body does not parse. */
    private static String kindOf(String body) {
        try {
            return KIND_MAPPER.readTree(body).path("kind").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** A bare mapper for {@link #kindOf}, which only ever reads one field off an already-scrubbed
     *  launcher body — {@link #classifyFailure} must stay callable with no Spring context around it. */
    private static final ObjectMapper KIND_MAPPER = new ObjectMapper();

    /**
     * Book the run's tokens and cost against the triage lane, and against the FINDING it ruled on.
     * One run is one ledger entry.
     */
    private void bookUsage(
            String projectId,
            String findingId,
            String model,
            String pricingId,
            String envelopeJson,
            boolean platformFunded) {
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (u == null) return;
        usage.recordSandboxRun(
                projectId,
                ModelLane.TRIAGE.wire(),
                model,
                pricingId,
                platformFunded,
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.cacheWriteTokens(),
                u.costUsd(),
                // The finding this ruling was about: without it the ledger can say the triage agent
                // cost a project $40 last week but not across how many rulings, which is the number
                // that decides whether an agent session per distinct cause is the right price for a
                // filter.
                new LlmUsageAccountant.Subject(SUBJECT_KIND, findingId));
    }

    /**
     * The model the agent runs: the project's {@link ModelLane#TRIAGE} choice, else the
     * observer's agentic model — the value this lane used before it had a lane of its own, so a
     * project that has chosen nothing behaves exactly as it did.
     */
    private Optional<ProjectModelSettings.ResolvedAgenticModel> resolvedModel(String projectId) {
        return modelSettings.resolveAgenticModel(projectId, ModelLane.TRIAGE);
    }

    /**
     * The message a dead-lettered job and a tripped breaker both carry. Names the remedy for the two
     * statuses that have one, because "401" on its own has cost a day before.
     */
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

    /** The launcher's {@code buildErrorBody} JSON as a short log fragment; empty when unparseable. */
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
