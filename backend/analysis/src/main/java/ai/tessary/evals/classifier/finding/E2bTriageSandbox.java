// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import ai.tessary.evals.config.ObserverProperties;
import ai.tessary.evals.config.ObserverProperties.Agentic;
import ai.tessary.evals.llm.AgenticCredentialResolver;
import ai.tessary.evals.llm.ModelProvider;
import ai.tessary.evals.llm.ProjectModelSettings;
import ai.tessary.evals.llmspi.ModelLane;
import ai.tessary.evals.open.errors.ClassifierError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.pricing.PlatformCallPricer;
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
import java.math.BigDecimal;
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
 * The microVM behind Layer-2 triage: one {@code POST /triage} == one fresh sandbox that materializes
 * the finding's dossier, runs the agent against it and against this platform's own MCP surface, and
 * tears down.
 *
 * <h2>No clone, and the MCP key in its place</h2>
 *
 * <p>This request used to carry a nullable {@code clone_url}, and half its runs paid for a repository
 * clone. Triage audits a CLAIM — is it true, was it measured over enough, does the evidence carry it
 * — and no source file answers any of those. What the run actually needs is the substrate the claim
 * is about, which is why the dossier shrank to the finding's own two files and the sandbox now
 * carries a short-lived project-scoped key ({@code mcp: {url, token}}, the same field
 * {@code E2bRcaSandbox} sends): the agent pages the evidence refs and reads the traces it decides to
 * read, then cites the ids it fetched. Locating a cause is RCA's job, and the repository went with
 * it.
 *
 * <h2>A failed RUN fails open; a failed LAUNCHER does not</h2>
 *
 * <p>A run that happened and produced nothing usable returns {@link Optional#empty()}.
 * {@link BehaviorTriageEngine} turns that into a thrown {@code TRIAGE_RUN_INCOMPLETE}, the job
 * retries and eventually dead-letters, and {@code triage_verdict} stays NULL. That is what makes
 * {@code unclear} safe to close on — a launcher outage cannot manufacture one.
 *
 * <p>A launcher that could not be reached, rejected the credentials, has no {@code /triage} route,
 * or failed internally is a different fact and throws {@code TRIAGE_LAUNCHER_UNAVAILABLE}. It is not
 * about this finding and will be just as true for the next one, so the worker refunds the attempt
 * and stops draining instead of spending every queued job's attempts against a shut door. The
 * distinction is the whole point: before it, a launcher answering 401 looked exactly like
 * twenty-five separate findings that each happened to produce no ruling.
 */
@Service
public class E2bTriageSandbox implements TriageSandbox {

    /** Selector key for {@code evals.classifier.triage-sandbox} — the only driver that ships today. */
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
    /** #939 D4: resolves + decrypts the org's own credential for the sandbox request body. */
    private final AgenticCredentialResolver credentials;

    private final LlmUsageAccountant usage;
    private final PlatformCallPricer pricer;
    private final Tracer tracer;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public E2bTriageSandbox(
            ObserverProperties props,
            ProjectModelSettings modelSettings,
            AgenticCredentialResolver credentials,
            LlmUsageAccountant usage,
            PlatformCallPricer pricer,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper) {
        this.props = props;
        this.modelSettings = modelSettings;
        this.credentials = credentials;
        this.usage = usage;
        this.pricer = pricer;
        // The instrumentation scope is this class's own package, which is the convention every other
        // sandbox here follows (E2bRcaSandbox names ai.tessary.evals.rca, E2bAnalysisSandbox
        // ai.tessary.evals.observer, and so on). It moved with the class in #839 and it is an OBSERVABLE
        // rename: a trace query filtering on the old `...classifier.behavior` scope goes empty rather
        // than wrong. Freezing the old string was the alternative and it would have made this the one
        // scope name in the repo that lies about where its code lives.
        this.tracer = openTelemetry.getTracer("ai.tessary.evals.classifier.finding");
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
                            + " will dead-letter; set EVALS_OBSERVER_AGENTIC_LAUNCHER_URL");
        }
    }

    /**
     * Run one triage. Empty means the run happened and produced nothing usable; a launcher-level
     * failure throws {@code TRIAGE_LAUNCHER_UNAVAILABLE}.
     */
    @Override
    public Optional<SandboxRun> run(SandboxRequest req) {
        Agentic cfg = props.getAgentic();
        if (cfg.getLauncherUrl() == null || cfg.getLauncherUrl().isBlank()) {
            // Thrown rather than skipped, on the same reasoning the MCP door already uses in
            // BehaviorTriageEngine#rule: an unset door is a misconfiguration somebody has to see, and
            // returning empty here made it indistinguishable from a run that simply found nothing.
            throw new EvalsException(
                    ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                    "evals.observer.agentic.launcher-url is unset; set EVALS_OBSERVER_AGENTIC_LAUNCHER_URL");
        }
        Span span = tracer.spanBuilder(OPERATION)
                .setNoParent()
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        try (var _ = span.makeCurrent()) {
            Optional<ProjectModelSettings.ResolvedAgenticModel> resolved = resolvedModel(req.projectId());
            String model = resolved.map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                    .orElseGet(() -> props.getAgentic().getModel());
            span.setAttribute("langfuse.trace.name", OPERATION);
            span.setAttribute("evals.project.id", req.projectId());
            span.setAttribute("langfuse.trace.metadata.project_id", req.projectId());
            span.setAttribute("evals.triage.finding_id", req.findingId());
            // Without the discriminator Langfuse never types this as a generation and the Alloy
            // langfuse branch drops it outright (see E2bAnalysisSandbox).
            span.setAttribute("gen_ai.operation.name", AgentSpanTelemetry.OP_INVOKE_AGENT);
            span.setAttribute("gen_ai.request.model", model);

            ObjectNode body = mapper.createObjectNode();
            ObjectNode files = body.putObject("files");
            req.files().forEach(files::put);
            body.put("prompt", req.prompt());
            body.put("json_schema", req.jsonSchema());
            body.put("model", model);
            // #939 D4: full removal of the launcher's deployment-env-var credential path — the org's
            // own credential now travels ON the request, decrypted here and never logged (see
            // AgenticCredentialResolver's class javadoc). Throws MISSING_CREDENTIALS /
            // AGENTIC_IAM_ROLE_UNSUPPORTED BEFORE the launcher is ever called when there is no usable
            // credential. An unresolved lane (no stored row) defaults to BEDROCK — see
            // E2bRcaSandbox#providerFor's javadoc for why that is the honest default now that there
            // is no deployment-wide fallback left to defer to.
            ModelProvider provider = resolved.map(ProjectModelSettings.ResolvedAgenticModel::provider)
                    .orElse(ModelProvider.BEDROCK);
            body.put("provider", provider.name());
            body.set("credential", mapper.valueToTree(credentials.resolve(req.projectId(), provider)));
            // mcp.token is a live platform key — sent to the launcher, never logged. Not optional on
            // this lane: the dossier carries the detector's numbers and nothing else, so an agent
            // without this reads no trace at all and can only restate the claim back at us.
            ObjectNode mcp = body.putObject("mcp");
            mcp.put("url", req.mcpUrl());
            mcp.put("token", req.mcpToken());
            body.put("timeout_ms", cfg.getTimeoutMs());
            // B (#994): a soft turn cap — see Agentic#maxTurns's javadoc for the mechanism and the
            // (not yet live-verified) caveat.
            body.put("max_turns", cfg.getMaxTurns());

            // Host anchor for the per-turn child spans (in-VM timestamps are offsets from startMs).
            Instant runStart = Instant.now();
            String respBody =
                    postLauncher(mapper.writeValueAsString(body), cfg, req.projectId(), req.findingId(), model);
            if (respBody == null) {
                markError(span, "launcher did not answer");
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(respBody);
            String raw = node.path("raw").asText("");
            if (raw.isBlank()) {
                // F1: a run-level failure body (server.js's buildErrorBody has no `raw`) — postLauncher
                // already booked any usage it carried before returning it here, so there is nothing
                // left to do but stop treating this as a completed run.
                markError(span, "agent returned no result");
                return Optional.empty();
            }
            AgentSpanTelemetry.recordUsage(span, mapper, raw);
            bookUsage(req.projectId(), req.findingId(), model, raw);
            flagIfOverSpendCap(span, req, model, raw, cfg.getMaxCostUsd());
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
        } catch (EvalsException e) {
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
     *   <li><b>5xx → launcher.</b> Its fault, not this request's.
     *   <li><b>Other 4xx → run.</b> The launcher understood us and refused this payload, which is a
     *       fact about this request.
     * </ul>
     *
     * <p>F1: {@code projectId}/{@code findingId}/{@code model} are here only so a failing run's body
     * can be booked against the ledger before this method returns or throws — server.js's {@code
     * buildErrorBody} carries a {@code usage} object (triage.js's failure envelope, extracted by the
     * launcher) whenever the agent burned tokens before the run failed. {@link #bookUsage} already
     * no-ops on a body with nothing usable, so this is safe to call on every non-2xx response.
     */
    private @Nullable String postLauncher(
            String bodyJson, Agentic cfg, String projectId, String findingId, String model)
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
            resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
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
            throw new EvalsException(
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
            // F1: book what the run spent before it failed, REGARDLESS of which bucket the status
            // falls into below — a launcher outage carries no usage (bookUsage no-ops on one), and a
            // run failure (today always a 502, see server.js) is exactly the case this exists for.
            bookUsage(projectId, findingId, model, resp.body());
            if (isLauncherLevel(resp.statusCode())) {
                // A refusal is not an outage. 401/403/404 will answer identically until somebody
                // changes the deployment, so it carries the code the worker parks on rather than the
                // one it retries on a breaker cycle.
                throw new EvalsException(
                        isLauncherRefusal(resp.statusCode())
                                ? ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED
                                : ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                        launcherDiagnosis(resp.statusCode(), cfg.getLauncherUrl(), diag));
            }
            return resp.body();
        }
        return resp.body();
    }

    /**
     * Book the run's tokens and cost against the triage lane, and against the FINDING it ruled on.
     * One run is one ledger entry.
     */
    private void bookUsage(String projectId, String findingId, String model, String envelopeJson) {
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (u == null) return;
        usage.recordSandboxRun(
                projectId,
                ModelLane.TRIAGE.wire(),
                model,
                // #939 D4: never platform-funded any more — see E2bRcaSandbox's identical note.
                false,
                u.inputTokens(),
                u.outputTokens(),
                u.cacheReadTokens(),
                u.cacheWriteTokens(),
                u.costUsd(),
                // The finding this ruling was about. This is the whole of launch H2's "per triage":
                // without it the ledger can say the triage agent cost a project $40 last week and cannot
                // say across how many rulings, which is the number that decides whether an agent session
                // per distinct cause is the right price for a filter (H3).
                new LlmUsageAccountant.Subject(SUBJECT_KIND, findingId));
    }

    /**
     * F4 (#994): the per-run spend cap on TRIAGE — {@code launch decision D6}, landing on the honest
     * meter F1-F3 built. Runs AFTER {@link #bookUsage}, on the run's actual priced cost, and only ever
     * FLAGS — logs a structured OPS line and marks the span — never rejects the ruling: see {@code
     * ObserverProperties.Agentic#maxCostUsd}'s javadoc for why (the spend already happened; discarding
     * a paid-for ruling protects nothing) and for the explicit statement that this is POST-HOC, not
     * preventive — there is no live per-turn cost signal in this codebase to intervene on mid-run.
     *
     * <p>Prices independently of {@link #bookUsage} rather than threading the number through it,
     * because {@link LlmUsageAccountant#recordSandboxRun} computes its own priced total internally and
     * does not hand it back — duplicating the (cheap, side-effect-free) price-book lookup here is
     * smaller surgery than changing that shared method's signature for every other caller's benefit
     * this issue does not need.
     */
    private void flagIfOverSpendCap(
            Span span, SandboxRequest req, String model, String envelopeJson, @Nullable BigDecimal cap) {
        if (cap == null) return;
        AgentSpanTelemetry.AgentUsage u = AgentSpanTelemetry.parseUsage(mapper, envelopeJson);
        if (u == null) return;
        Optional<PlatformCallPricer.PricedCall> priced = pricer.price(
                model,
                null,
                clampToInt(u.inputTokens()),
                clampToInt(u.outputTokens()),
                clampToInt(u.cacheReadTokens()),
                clampToInt(u.cacheWriteTokens()));
        if (priced.isEmpty()) return; // unpriced model: no honest number to compare against the cap
        BigDecimal cost = priced.get().total();
        if (cost.compareTo(cap) <= 0) return;
        log.warn(
                Markers.OPS,
                "triage run over spend cap project={} finding={} model={} cost_usd={} cap_usd={}",
                req.projectId(),
                req.findingId(),
                model,
                cost,
                cap);
        span.setAttribute("evals.triage.over_spend_cap", true);
        span.setAttribute("evals.triage.cost_usd", cost.doubleValue());
        span.setAttribute("evals.triage.spend_cap_usd", cap.doubleValue());
    }

    /** Same clamp {@code LlmUsageAccountant#toInt} applies before a price-book lookup — a launcher-
     *  reported count past {@code Integer.MAX_VALUE} is not realistic, but the lookup takes an Integer. */
    private static @Nullable Integer clampToInt(long value) {
        if (value <= 0) return null;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * The model the agent runs: the project's {@link ModelLane#TRIAGE} choice, else the
     * observer's agentic model — the value this lane used before it had a lane of its own, so a
     * project that has chosen nothing behaves exactly as it did.
     */
    private Optional<ProjectModelSettings.ResolvedAgenticModel> resolvedModel(String projectId) {
        return modelSettings.resolveAgenticModel(projectId, ModelLane.TRIAGE);
    }

    /** Whether a status says the LAUNCHER is the problem rather than this particular request. */
    private static boolean isLauncherLevel(int status) {
        return status == 401 || status == 403 || status == 404 || status / 100 == 5;
    }

    /**
     * The launcher-level statuses that mean "misconfigured", not "down": the run was refused by a
     * launcher that is up and answering, and will be refused again on identical terms until a person
     * changes the deployment. {@code launcherDiagnosis} already names the remedy for each.
     */
    private static boolean isLauncherRefusal(int status) {
        return status == 401 || status == 403 || status == 404;
    }

    /**
     * The message a dead-lettered job and a tripped breaker both carry. Names the remedy for the two
     * statuses that have one, because "401" on its own has cost a day before.
     */
    private static String launcherDiagnosis(int status, String launcherUrl, String diag) {
        String remedy =
                switch (status) {
                    case 401, 403 ->
                        " — the shared secret does not match; EVALS_OBSERVER_AGENTIC_LAUNCHER_API_KEY must equal"
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
