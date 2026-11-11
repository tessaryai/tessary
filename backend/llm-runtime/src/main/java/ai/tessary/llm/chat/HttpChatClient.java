// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import ai.tessary.ingest.BoundedBody;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link ChatClient} over the JDK client. 429, 5xx and transport failures retry with jittered back-off honouring
 * {@code Retry-After}; a timeout does not (a person is waiting), nor do 401, 402, 403 or another 4xx. The caller has
 * already guarded the endpoint ({@link ChatProviderResolver}). No request body, answer or key is ever logged.
 */
@Component
public class HttpChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(HttpChatClient.class);

    static final long BASE_BACKOFF_MS = 1_000L;

    /** A provider asking for a longer wait than this is treated as down for this call. */
    static final long MAX_RETRY_AFTER_MS = 30_000L;

    static final int REFUSAL_CHARS = 200;

    private static final SecureRandom JITTER = new SecureRandom();

    /** A seam so tests do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Sleeper sleeper;
    private final long timeoutMs;
    private final int maxAttempts;
    private final OpenAiChatCodec openAi;
    private final AnthropicChatCodec anthropic;

    @Autowired
    public HttpChatClient(ObjectMapper mapper, ChatProperties props) {
        // The builder's default Redirect.NEVER stays: a redirect must never carry the org's key to another host.
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), mapper, Thread::sleep, props);
    }

    HttpChatClient(HttpClient http, ObjectMapper mapper, Sleeper sleeper, ChatProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.sleeper = sleeper;
        this.timeoutMs = Math.max(1, props.timeoutMs());
        this.maxAttempts = Math.max(1, props.maxAttempts());
        this.openAi = new OpenAiChatCodec(mapper);
        this.anthropic = new AnthropicChatCodec(mapper, props.maxOutputTokens());
    }

    @Override
    public ChatResult complete(ChatTarget target, ChatRequest request) {
        ChatCodec codec =
                switch (target.dialect()) {
                    case OPENAI_CHAT_COMPLETIONS -> openAi;
                    case ANTHROPIC_MESSAGES -> anthropic;
                };
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.endpoint())
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        codec.body(target, request).toString()));
        codec.authorize(builder, target.apiKey());
        long started = System.nanoTime();
        String text = post(target, builder.build());
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        return codec.parse(target, parseJson(target, text), latencyMs);
    }

    private String post(ChatTarget target, HttpRequest httpRequest) {
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(httpRequest, BoundedBody.string());
            } catch (HttpTimeoutException e) {
                warnTransport(target, attempt, e);
                throw new TessaryException(PlaygroundError.TIMEOUT, e, target.provider(), timeoutMs);
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    warnTransport(target, attempt, e);
                    throw new TessaryException(
                            PlaygroundError.PROVIDER_UNAVAILABLE,
                            e,
                            target.provider(),
                            e.getClass().getSimpleName());
                }
                backOff(target, attempt, null, "transport");
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warnTransport(target, attempt, e);
                throw new TessaryException(PlaygroundError.PROVIDER_UNAVAILABLE, e, target.provider(), "interrupted");
            }
            int status = response.statusCode();
            if (status / 100 == 2) return response.body();
            if (status == 429 || status >= 500) {
                Long retryAfter = retryAfterMs(response);
                if (attempt < maxAttempts && (retryAfter == null || retryAfter <= MAX_RETRY_AFTER_MS)) {
                    backOff(target, attempt, retryAfter, "HTTP " + status);
                    continue;
                }
            }
            warnStatus(target, status, attempt);
            if (status == 402) {
                throw new TessaryException(PlaygroundError.PROVIDER_NO_CREDIT, target.provider(), status);
            }
            if (status == 401 || status == 403) {
                throw new TessaryException(PlaygroundError.PROVIDER_REJECTED, target.provider(), status);
            }
            if (status == 429 || status >= 500) {
                throw new TessaryException(PlaygroundError.PROVIDER_UNAVAILABLE, target.provider(), "HTTP " + status);
            }
            throw new TessaryException(
                    PlaygroundError.REQUEST_REFUSED, target.provider(), status, refusal(mapper, response.body()));
        }
    }

    /**
     * A refusal names what was wrong with the request; the cap bounds a provider that echoes the prompt back. The
     * provider's JSON error message, else the raw body, whitespace folded.
     */
    static String refusal(ObjectMapper mapper, @Nullable String body) {
        if (body == null || body.isBlank()) return "no detail";
        String text = refusalMessage(mapper, body).orElse(body).strip().replaceAll("\\s+", " ");
        return text.length() <= REFUSAL_CHARS ? text : text.substring(0, REFUSAL_CHARS) + "…";
    }

    private static Optional<String> refusalMessage(ObjectMapper mapper, String body) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (IOException notJson) {
            return Optional.empty();
        }
        if (!node.isObject()) return Optional.empty();
        JsonNode message = node.path("error").path("message");
        if (message.isMissingNode()) message = node.path("message");
        return message.isTextual() && !message.asText().isBlank() ? Optional.of(message.asText()) : Optional.empty();
    }

    private void backOff(ChatTarget target, int attempt, @Nullable Long retryAfterMs, String cause) {
        long wait = retryAfterMs != null ? retryAfterMs : backoffMs(attempt);
        StructuredLog.debug(log, "chat.call.retry")
                .message(
                        "chat call to %s failed on attempt %d (%s), retrying in %dms",
                        target.provider(), attempt, cause, wait)
                .field("provider", target.provider().name())
                .field("attempt", attempt)
                .field("waitMs", wait)
                .log();
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(PlaygroundError.PROVIDER_UNAVAILABLE, e, target.provider(), "interrupted");
        }
    }

    /** Jitter so concurrent runs do not retry in step. */
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

    private JsonNode parseJson(ChatTarget target, String text) {
        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (IOException e) {
            // No throwable attached: a parse error echoes the body, which can quote the prompt.
            throw new TessaryException(PlaygroundError.MALFORMED_RESPONSE, target.provider(), "body is not JSON");
        }
        if (!node.isObject()) {
            throw new TessaryException(PlaygroundError.MALFORMED_RESPONSE, target.provider(), "body is not an object");
        }
        return node;
    }

    private static void warnStatus(ChatTarget target, int status, int attempt) {
        StructuredLog.warn(log, Markers.OPS, "chat.call.failed")
                .message("chat call to %s answered HTTP %s on attempt %d", target.provider(), status, attempt)
                .field("provider", target.provider().name())
                .field("status", status)
                .field("attempt", attempt)
                .log();
    }

    private static void warnTransport(ChatTarget target, int attempt, Exception cause) {
        StructuredLog.warn(log, Markers.OPS, "chat.call.failed")
                .message("chat call to %s got no answer on attempt %d", target.provider(), attempt)
                .field("provider", target.provider().name())
                .field("attempt", attempt)
                .cause(cause)
                .log();
    }
}
