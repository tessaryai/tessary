// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import ai.tessary.llm.ModelCatalog;
import ai.tessary.open.errors.DecisionError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pricing.PlatformCallPricer;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link DecisionClient} over the JDK {@link HttpClient}, for TypeSafe's Jev on either gateway: TypeSafe
 * direct ({@code POST /v1/systemone}) or OpenRouter ({@code POST /api/alpha/decisions}). Both take the
 * same {@code {model, state, questions}} body with a bearer key and answer the same envelope.
 *
 * <p>429, 5xx and transport failures are retried with exponential back-off and jitter, honouring
 * {@code Retry-After}; 401 and 403 are not, since only a new key can change the answer.
 *
 * <p><b>Pricing.</b> Every call is priced under {@code typesafe/<bare model id>} whichever gateway
 * carried it, on the REQUESTED id, as {@code LlmCaller} prices on the resolved name: the book has no
 * {@code openrouter/typesafe/...} key, and the echoed dated version is not a book key. A cost the
 * provider reports in its body stays in the returned response body as an audit copy and is never the
 * booked figure.
 */
@Component
public class JevDecisionClient implements DecisionClient {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionClient.class);

    static final String SPAN_NAME = "decision-call";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final long BASE_BACKOFF_MS = 1_000L;

    /** A provider asking for a longer wait than this is treated as down for this call. */
    static final long MAX_RETRY_AFTER_MS = 30_000L;

    private static final SecureRandom JITTER = new SecureRandom();

    /** How a retry waits; a seam so tests do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Tracer tracer;
    private final @Nullable PlatformCallPricer pricer;
    private final @Nullable LlmUsageAccountant accountant;
    private final Sleeper sleeper;
    private final Duration timeout;
    private final int maxAttempts;

    @Autowired
    public JevDecisionClient(
            ObjectMapper mapper,
            OpenTelemetry openTelemetry,
            @Nullable PlatformCallPricer pricer,
            @Nullable LlmUsageAccountant accountant) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                mapper,
                openTelemetry,
                pricer,
                accountant,
                Thread::sleep,
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_ATTEMPTS);
    }

    JevDecisionClient(
            HttpClient http,
            ObjectMapper mapper,
            OpenTelemetry openTelemetry,
            @Nullable PlatformCallPricer pricer,
            @Nullable LlmUsageAccountant accountant,
            Sleeper sleeper,
            Duration timeout,
            int maxAttempts) {
        this.http = http;
        this.mapper = mapper;
        this.tracer = openTelemetry.getTracer("ai.tessary.llm");
        this.pricer = pricer;
        this.accountant = accountant;
        this.sleeper = sleeper;
        this.timeout = timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public DecisionAnswer decide(String projectId, String lane, DecisionTarget target, DecisionRequest request) {
        ObjectNode body = requestBody(target, request);
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setSpanKind(SpanKind.CLIENT)
                .setNoParent()
                .startSpan();
        try (var _ = span.makeCurrent()) {
            recordRequest(span, projectId, lane, target);
            long started = System.nanoTime();
            String responseText = post(target, body);
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            JsonNode response = parseJson(target, responseText);
            Map<String, DecisionAnswer.Answer> answers = answers(target, request, response);
            Integer in = intOrNull(response.path("usage").path("input_tokens"));
            Integer out = intOrNull(response.path("usage").path("output_tokens"));
            String responded = response.path("model").asText("");
            PlatformCallPricer.PricedCall priced = pricer == null
                    ? null
                    : pricer.price(pricingId(target), null, in, out, null, null).orElse(null);
            DecisionAnswer answer = new DecisionAnswer(
                    target.provider(),
                    target.modelId(),
                    responded.isBlank() ? target.modelId() : responded,
                    answers,
                    in,
                    out,
                    priced == null ? null : priced.total(),
                    priced == null ? null : priced.priceBookVersion(),
                    latencyMs,
                    body,
                    response);
            recordResponse(span, answer);
            book(projectId, lane, answer);
            return answer;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.setAttribute("langfuse.observation.level", "ERROR");
            if (e.getMessage() != null) span.setAttribute("langfuse.observation.status_message", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    /** The id this call is priced under, see {@link ModelCatalog#decisionPricingId}. */
    static String pricingId(DecisionTarget target) {
        return ModelCatalog.decisionPricingId(target.provider(), target.modelId());
    }

    ObjectNode requestBody(DecisionTarget target, DecisionRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", target.modelId());
        body.set("state", request.state());
        body.set("questions", mapper.valueToTree(request.questions()));
        return body;
    }

    private String post(DecisionTarget target, ObjectNode body) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new TessaryException(
                    DecisionError.MALFORMED_ANSWER, e, target.provider(), "request not serializable");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(target.endpoint())
                .timeout(timeout)
                .header("Authorization", "Bearer " + target.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    throw new TessaryException(
                            DecisionError.PROVIDER_UNAVAILABLE,
                            e,
                            target.provider(),
                            e.getClass().getSimpleName());
                }
                backOff(target, attempt, null, "transport");
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TessaryException(DecisionError.PROVIDER_UNAVAILABLE, e, target.provider(), "interrupted");
            }
            int status = response.statusCode();
            if (status / 100 == 2) return response.body();
            if (status == 401 || status == 403) {
                throw new TessaryException(DecisionError.PROVIDER_REJECTED, target.provider(), status);
            }
            if (status == 429 || status >= 500) {
                Long retryAfter = retryAfterMs(response);
                if (attempt >= maxAttempts || (retryAfter != null && retryAfter > MAX_RETRY_AFTER_MS)) {
                    throw new TessaryException(DecisionError.PROVIDER_UNAVAILABLE, target.provider(), "HTTP " + status);
                }
                backOff(target, attempt, retryAfter, "HTTP " + status);
                continue;
            }
            throw new TessaryException(DecisionError.REQUEST_REFUSED, target.provider(), status);
        }
    }

    private void backOff(DecisionTarget target, int attempt, @Nullable Long retryAfterMs, String cause) {
        long wait = retryAfterMs != null ? retryAfterMs : backoffMs(attempt);
        log.debug(
                "decision call to {} failed on attempt {} ({}), retrying in {}ms",
                target.provider(),
                attempt,
                cause,
                wait);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(DecisionError.PROVIDER_UNAVAILABLE, e, target.provider(), "interrupted");
        }
    }

    /** 1s, 2s, 4s, ... plus up to a quarter of that in jitter, so concurrent turns do not retry in step. */
    static long backoffMs(int attempt) {
        long base = BASE_BACKOFF_MS << Math.min(attempt - 1, 10);
        return base + JITTER.nextLong(base / 4 + 1);
    }

    private static @Nullable Long retryAfterMs(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(String::trim)
                .filter(v -> v.matches("\\d{1,9}"))
                .map(v -> Long.parseLong(v) * 1_000L)
                .orElse(null);
    }

    private JsonNode parseJson(DecisionTarget target, String text) {
        try {
            JsonNode node = mapper.readTree(text);
            if (node == null || !node.isObject()) {
                throw new TessaryException(DecisionError.MALFORMED_ANSWER, target.provider(), "body is not an object");
            }
            return node;
        } catch (IOException e) {
            // No throwable attached: a parse error echoes the body, which can carry customer text.
            throw new TessaryException( // NOPMD - PreserveStackTrace: see above
                    DecisionError.MALFORMED_ANSWER, target.provider(), "body is not JSON");
        }
    }

    /** Every question asked must come back answered, in its own type's documented fields. */
    private static Map<String, DecisionAnswer.Answer> answers(
            DecisionTarget target, DecisionRequest request, JsonNode response) {
        JsonNode answers = response.path("answers");
        Map<String, DecisionAnswer.Answer> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, DecisionRequest.Question> q : request.questions().entrySet()) {
            JsonNode a = answers.path(q.getKey());
            if (!a.isObject()) {
                throw new TessaryException(
                        DecisionError.MALFORMED_ANSWER, target.provider(), "no answer for " + q.getKey());
            }
            String type = a.path("type").asText(q.getValue().type());
            Map<String, Double> probabilities = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> p : a.path("probabilities").properties()) {
                if (!p.getValue().isNumber()) {
                    throw new TessaryException(
                            DecisionError.MALFORMED_ANSWER, target.provider(), "non-numeric probability");
                }
                probabilities.put(p.getKey(), p.getValue().doubleValue());
            }
            String choice = a.path("choice").isTextual() ? a.path("choice").asText() : null;
            if ("choice".equals(q.getValue().type())) {
                if (choice == null || probabilities.isEmpty()) {
                    throw new TessaryException(
                            DecisionError.MALFORMED_ANSWER, target.provider(), "choice answer without probabilities");
                }
                if (!q.getValue().criteria().keySet().containsAll(probabilities.keySet())) {
                    throw new TessaryException(
                            DecisionError.MALFORMED_ANSWER, target.provider(), "probability for an unasked option");
                }
            }
            Double score = a.path("score").isNumber() ? a.path("score").doubleValue() : null;
            Double confidence =
                    a.path("confidence").isNumber() ? a.path("confidence").doubleValue() : null;
            parsed.put(q.getKey(), new DecisionAnswer.Answer(type, choice, score, confidence, probabilities));
        }
        return parsed;
    }

    private static @Nullable Integer intOrNull(JsonNode node) {
        return node.canConvertToInt() && node.isIntegralNumber() ? node.intValue() : null;
    }

    private static void recordRequest(Span span, String projectId, String lane, DecisionTarget target) {
        span.setAttribute("gen_ai.operation.name", "decision");
        span.setAttribute("gen_ai.system", "typesafe");
        span.setAttribute("gen_ai.request.model", target.modelId());
        span.setAttribute("tessary.project.id", projectId);
        span.setAttribute("tessary.decision.provider", target.provider().name());
        span.setAttribute("langfuse.observation.metadata.project_id", projectId);
        span.setAttribute("langfuse.observation.metadata.lane", lane);
        span.setAttribute(
                "langfuse.observation.metadata.provider", target.provider().name());
    }

    private static void recordResponse(Span span, DecisionAnswer answer) {
        span.setAttribute("gen_ai.response.model", answer.respondedModel());
        span.setAttribute("tessary.latency_ms", answer.latencyMs());
        Integer in = answer.inputTokens();
        if (in != null) span.setAttribute("gen_ai.usage.input_tokens", in.longValue());
        Integer out = answer.outputTokens();
        if (out != null) span.setAttribute("gen_ai.usage.output_tokens", out.longValue());
        BigDecimal cost = answer.costUsd();
        if (cost != null) span.setAttribute("gen_ai.usage.cost", cost.doubleValue());
    }

    private void book(String projectId, String lane, DecisionAnswer answer) {
        LlmUsageAccountant ledger = accountant;
        if (ledger == null) return;
        ledger.recordDecisionCall(
                projectId,
                lane,
                answer.requestedModel(),
                answer.inputTokens(),
                answer.outputTokens(),
                answer.costUsd(),
                answer.priceBookVersion(),
                (int) Math.min(Integer.MAX_VALUE, answer.latencyMs()));
    }
}
