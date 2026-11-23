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
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
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
    private final LongSupplier nanoClock;
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
        this(http, mapper, sleeper, props, System::nanoTime);
    }

    HttpChatClient(
            HttpClient http, ObjectMapper mapper, Sleeper sleeper, ChatProperties props, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
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
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        codec.body(target, request).toString()));
        codec.authorize(builder, target.apiKey());
        long started = nanoClock.getAsLong();
        String text = post(target, builder, started + timeoutMs * 1_000_000L);
        long latencyMs = (nanoClock.getAsLong() - started) / 1_000_000L;
        return codec.parse(target, parseJson(target, text), latencyMs);
    }

    /**
     * One deadline covers the whole run: every attempt's headers and body, and every wait between attempts, so a slow
     * or dripping provider cannot hold a run slot past {@code timeout-ms}. A failure after the provider started
     * answering is never retried, because the provider may already have billed the call.
     */
    private String post(ChatTarget target, HttpRequest.Builder builder, long deadlineNanos) {
        for (int attempt = 1; ; attempt++) {
            long remainingMs = remainingMs(deadlineNanos);
            if (remainingMs <= 0) throw new TessaryException(PlaygroundError.TIMEOUT, target.provider(), timeoutMs);
            AtomicBoolean answered = new AtomicBoolean();
            HttpResponse.BodyHandler<String> handler = info -> {
                answered.set(true);
                return BoundedBody.string().apply(info);
            };
            HttpResponse<String> response;
            try {
                response = sendWithin(
                        builder.timeout(Duration.ofMillis(remainingMs)).build(), handler, remainingMs);
            } catch (HttpTimeoutException e) {
                if (!(e instanceof HttpConnectTimeoutException)) {
                    warnTransport(target, attempt, e);
                    throw new TessaryException(PlaygroundError.TIMEOUT, e, target.provider(), timeoutMs);
                }
                retryOrFail(target, attempt, deadlineNanos, e);
                continue;
            } catch (IOException e) {
                if (answered.get()) {
                    warnTransport(target, attempt, e);
                    throw new TessaryException(
                            PlaygroundError.PROVIDER_UNAVAILABLE, e, target.provider(), "the answer was cut off");
                }
                retryOrFail(target, attempt, deadlineNanos, e);
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
                long wait = retryAfter != null ? retryAfter : backoffMs(attempt);
                if (attempt < maxAttempts
                        && (retryAfter == null || retryAfter <= MAX_RETRY_AFTER_MS)
                        && wait < remainingMs(deadlineNanos)) {
                    backOff(target, attempt, wait, "HTTP " + status);
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

    /** A failure before the provider answered: retried while attempts and time remain, else unavailable. */
    private void retryOrFail(ChatTarget target, int attempt, long deadlineNanos, IOException e) {
        long wait = backoffMs(attempt);
        if (attempt >= maxAttempts || wait >= remainingMs(deadlineNanos)) {
            warnTransport(target, attempt, e);
            throw new TessaryException(
                    PlaygroundError.PROVIDER_UNAVAILABLE,
                    e,
                    target.provider(),
                    e.getClass().getSimpleName());
        }
        backOff(target, attempt, wait, "transport");
    }

    /**
     * {@code HttpRequest.timeout} stops only the wait for headers, so the send runs on its own thread and the caller
     * stops waiting at the deadline, cancelling the send, however slowly the body arrives.
     */
    private HttpResponse<String> sendWithin(
            HttpRequest request, HttpResponse.BodyHandler<String> handler, long remainingMs)
            throws IOException, InterruptedException {
        FutureTask<HttpResponse<String>> task = new FutureTask<>(() -> http.send(request, handler));
        Thread.ofVirtual().name("chat-call").start(task);
        try {
            return task.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new HttpTimeoutException("no complete answer within the run deadline");
        } catch (InterruptedException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException interrupted) throw interrupted;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException(cause);
        }
    }

    private long remainingMs(long deadlineNanos) {
        return (deadlineNanos - nanoClock.getAsLong()) / 1_000_000L;
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

    private void backOff(ChatTarget target, int attempt, long wait, String cause) {
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
