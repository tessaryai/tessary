// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import ai.tessary.config.ObserverProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.plan.EncoderAvailability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link EncoderScorer} backed by the classify-service's {@code POST /classify} (in-process
 * transformers.js encoder heads). The endpoint comes from {@code tessary.observer.encoder.*} —
 * the standalone classify-service, which runs on ECS Fargate in production and as the
 * {@code classify} container in docker-compose.dev.yml locally. There is deliberately no
 * fallback endpoint: one implementation, one owner.
 *
 * <p>The launcher caps every request body at 8&nbsp;MB, so a sweep batch is never sent as one
 * request: {@code score()} splits the texts into sequential sub-batches bounded by both
 * {@link #MAX_TEXTS_PER_REQUEST} and {@link #MAX_TEXT_BYTES_PER_REQUEST} of summed UTF-8 text
 * bytes, then concatenates the per-chunk scores in order. A single text over the byte budget is
 * sent alone in its own request, never dropped or truncated here — the serving-side tokenizer
 * truncates to the model window itself.
 *
 * <p>Fails loudly by contract: unconfigured launcher, transport failure, non-2xx, a response
 * whose score count doesn't match the request, or a non-numeric score entry all throw, so the
 * encoder sweep fails loudly and is retried on subsequent heartbeats instead of silently scoring
 * everything clean. A connection that never opens is the one transport failure that is not a fault:
 * it throws {@link EncoderUnreachableException} and marks {@link EncoderAvailability} down, so the
 * sweep pauses until the model answers again rather than spending its attempts.
 */
@Service
public class LauncherEncoderScorer implements EncoderScorer {

    private static final Logger log = LoggerFactory.getLogger(LauncherEncoderScorer.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Generous per-request cap: a cold head downloads + loads its model on the first call.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Max texts per {@code /classify} request. Sized so one request's CPU inference on the
     * classify-service (2 vCPU Fargate, fp32 heads, window-filling texts) finishes WELL inside
     * both this client's 5-minute {@link #REQUEST_TIMEOUT} and Node's server-side 5-minute
     * request timeout: 100-text chunks measured ~270-400s there (borderline: three of the four
     * 2026-07-12 backlog jobs drained, the heaviest cycled forever); 32 lands around two
     * minutes. More, smaller requests — same sweep total, but each beats the timeout.
     */
    static final int MAX_TEXTS_PER_REQUEST = 32;

    /**
     * Max summed UTF-8 text bytes per {@code /classify} request — half the launcher's 8 MB body
     * cap, leaving ample headroom for JSON escaping and envelope overhead.
     */
    static final long MAX_TEXT_BYTES_PER_REQUEST = 4L * 1024 * 1024;

    /**
     * Estimated tokens per token-head request, at ~4 UTF-8 bytes per token. The head's cost is
     * roughly quadratic in a response's length and a CPU encoder takes tens of seconds for an 8k
     * response, so a request is sized by the work it carries, not only by count: sixteen short
     * answers travel together, one long one travels alone, and every request finishes well inside
     * {@link #REQUEST_TIMEOUT} and the encoder's queue timeout.
     */
    static final long MAX_RESPONSE_TOKENS_PER_REQUEST = 24_000;

    /** Retries of one request the encoder throttled (429/503) before the sweep fails. */
    static final int MAX_THROTTLE_RETRIES = 4;

    private final ObserverProperties props;
    private final ObjectMapper mapper;
    /** Told the reason when a send finds the encoder unreachable; {@link EncoderAvailability#markUnreachable}. */
    private final Consumer<String> onUnreachable;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    /**
     * The backend-side half of the encoder's concurrency ceiling: every sweep on every head takes a
     * permit before it posts, so the encoder sees at most {@code encoder.max-inflight} requests at
     * once and sheds nothing; the waiting happens here, on a virtual thread, inside the lease.
     */
    private final Semaphore inflight;

    @Autowired
    public LauncherEncoderScorer(ObserverProperties props, ObjectMapper mapper, EncoderAvailability availability) {
        this(props, mapper, availability::markUnreachable);
    }

    LauncherEncoderScorer(ObserverProperties props, ObjectMapper mapper, Consumer<String> onUnreachable) {
        this.props = props;
        this.mapper = mapper;
        this.onUnreachable = onUnreachable;
        this.inflight = new Semaphore(Math.max(1, props.getEncoder().getMaxInflight()), true);
    }

    @Override
    public List<Double> score(String head, List<String> texts) {
        String baseUrl = requireBaseUrl();
        List<List<String>> chunks = chunk(texts, MAX_TEXTS_PER_REQUEST, MAX_TEXT_BYTES_PER_REQUEST);
        List<Double> out = new ArrayList<>(texts.size());
        for (int i = 0; i < chunks.size(); i++) {
            out.addAll(scoreChunk(baseUrl, head, chunks.get(i), i + 1, chunks.size()));
        }
        if (out.size() != texts.size()) {
            throw new IllegalStateException(
                    "launcher /classify returned " + out.size() + " scores for " + texts.size() + " texts");
        }
        return List.copyOf(out);
    }

    @Override
    public List<Double> scorePairs(String head, List<Pair> pairs) {
        String baseUrl = requireBaseUrl();
        List<List<Pair>> chunks = chunkPairs(pairs, MAX_TEXTS_PER_REQUEST, MAX_TEXT_BYTES_PER_REQUEST);
        List<Double> out = new ArrayList<>(pairs.size());
        for (int i = 0; i < chunks.size(); i++) {
            out.addAll(scorePairsChunk(baseUrl, head, chunks.get(i), i + 1, chunks.size()));
        }
        if (out.size() != pairs.size()) {
            throw new IllegalStateException(
                    "launcher /classify returned " + out.size() + " scores for " + pairs.size() + " pairs");
        }
        return List.copyOf(out);
    }

    private String requireBaseUrl() {
        String baseUrl = props.getEncoder().getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("encoder scoring needs tessary.observer.encoder.url");
        }
        return baseUrl;
    }

    /**
     * Split {@code texts} into request-sized sub-batches, preserving order: each chunk holds at
     * most {@code maxCount} texts and at most {@code maxBytes} of summed UTF-8 text bytes. A
     * single text that alone exceeds {@code maxBytes} becomes its own one-text chunk (never
     * dropped or truncated — the serving side owns truncation to the model window).
     */
    static List<List<String>> chunk(List<String> texts, int maxCount, long maxBytes) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long currentBytes = 0;
        for (String text : texts) {
            long bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (!current.isEmpty() && (current.size() >= maxCount || currentBytes + bytes > maxBytes)) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(text);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        return List.copyOf(chunks);
    }

    /**
     * The {@link #chunk} analogue for pair-head requests: splits {@code pairs} into request-sized
     * sub-batches bounded by count and by summed UTF-8 bytes of BOTH the premise and the claim. A
     * single pair that alone exceeds {@code maxBytes} becomes its own one-pair chunk — the serving
     * side (not this budgeting) owns any truncation, and it never truncates the claim (see
     * classify-service's {@code classifyPairs}).
     */
    static List<List<Pair>> chunkPairs(List<Pair> pairs, int maxCount, long maxBytes) {
        List<List<Pair>> chunks = new ArrayList<>();
        List<Pair> current = new ArrayList<>();
        long currentBytes = 0;
        for (Pair pair : pairs) {
            long bytes = utf8Bytes(pair.premise()) + utf8Bytes(pair.claim());
            if (!current.isEmpty() && (current.size() >= maxCount || currentBytes + bytes > maxBytes)) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(pair);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        return List.copyOf(chunks);
    }

    private static long utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long textBytes(List<String> texts) {
        long bytes = 0;
        for (String text : texts) {
            bytes += utf8Bytes(text);
        }
        return bytes;
    }

    private static long pairBytes(List<Pair> pairs) {
        long bytes = 0;
        for (Pair pair : pairs) {
            bytes += utf8Bytes(pair.premise()) + utf8Bytes(pair.claim());
        }
        return bytes;
    }

    /** Max responses per token-head request: classify-service's GROUNDEDNESS_MAX_RESPONSES default. */
    static final int MAX_RESPONSES_PER_REQUEST = 16;

    @Override
    public List<ResponseScore> scoreResponses(String head, List<Response> responses) {
        String baseUrl = requireBaseUrl();
        List<List<Response>> chunks = chunkResponses(
                responses, MAX_RESPONSES_PER_REQUEST, MAX_TEXT_BYTES_PER_REQUEST, MAX_RESPONSE_TOKENS_PER_REQUEST);
        List<ResponseScore> out = new ArrayList<>(responses.size());
        for (int i = 0; i < chunks.size(); i++) {
            out.addAll(scoreResponsesTolerant(baseUrl, head, chunks.get(i), i + 1, chunks.size()));
        }
        if (out.size() != responses.size()) {
            throw new IllegalStateException(
                    "launcher /classify returned " + out.size() + " scores for " + responses.size() + " responses");
        }
        return out;
    }

    /**
     * A 400 from the encoder for one chunk: the request shape was refused, which for a token head
     * means an answer the window cannot hold. Bisect down to the offending response rather than
     * fail the sweep: the other responses in the chunk are scorable, and a sweep that fails on one
     * over-long answer would re-fail on it every tick until it dead-lettered.
     */
    private List<ResponseScore> scoreResponsesTolerant(
            String baseUrl, String head, List<Response> responses, int chunk, int chunks) {
        try {
            return scoreResponsesChunk(baseUrl, head, responses, chunk, chunks);
        } catch (ClientRejection e) {
            if (responses.size() == 1) {
                StructuredLog.warn(log, Markers.OPS, "encoder.classify.rejected")
                        .field("head", head)
                        .field("mode", "responses")
                        .field("bytes", responseBytes(responses.get(0)))
                        .field("httpStatus", e.status)
                        .log();
                return List.of(ResponseScore.UNSCORED);
            }
            int mid = responses.size() / 2;
            List<ResponseScore> out = new ArrayList<>(responses.size());
            out.addAll(scoreResponsesTolerant(baseUrl, head, responses.subList(0, mid), chunk, chunks));
            out.addAll(scoreResponsesTolerant(baseUrl, head, responses.subList(mid, responses.size()), chunk, chunks));
            return out;
        }
    }

    /** The encoder refused the request as malformed for its head (HTTP 400): the caller's to route around. */
    static final class ClientRejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        ClientRejection(int status) {
            super("launcher /classify returned HTTP " + status);
            this.status = status;
        }
    }

    static List<List<Response>> chunkResponses(List<Response> responses, int maxCount, long maxBytes) {
        return chunkResponses(responses, maxCount, maxBytes, Long.MAX_VALUE);
    }

    static List<List<Response>> chunkResponses(List<Response> responses, int maxCount, long maxBytes, long maxTokens) {
        List<List<Response>> chunks = new ArrayList<>();
        List<Response> current = new ArrayList<>();
        long currentBytes = 0;
        for (Response r : responses) {
            long bytes = responseBytes(r);
            boolean overTokens = currentBytes / 4 + bytes / 4 > maxTokens;
            if (!current.isEmpty() && (current.size() >= maxCount || currentBytes + bytes > maxBytes || overTokens)) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(r);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) chunks.add(List.copyOf(current));
        return List.copyOf(chunks);
    }

    private static long responseBytes(Response r) {
        long bytes = utf8Bytes(r.answer());
        String question = r.question();
        if (question != null) bytes += utf8Bytes(question);
        for (String p : r.passages()) bytes += utf8Bytes(p);
        return bytes;
    }

    /**
     * One {@code POST /classify} for one response chunk. The body is {@code {head, responses:[...]}} and
     * the reply {@code {scores:[{unsupported, conflict, spans:[{start,end,unsupported,conflict}]}]}}; a
     * missing or non-numeric score is a failure, never a silent "clean", for the same reason as
     * {@link #postAndParseScores}.
     */
    private List<ResponseScore> scoreResponsesChunk(
            String baseUrl, String head, List<Response> responses, int chunk, int chunks) {
        ObjectNode body = mapper.createObjectNode();
        body.put("head", head);
        ArrayNode arr = body.putArray("responses");
        long bytes = 0;
        for (Response r : responses) {
            ObjectNode o = arr.addObject();
            ArrayNode ps = o.putArray("passages");
            r.passages().forEach(ps::add);
            if (r.question() != null) o.put("question", r.question());
            o.put("answer", r.answer());
            bytes += responseBytes(r);
        }
        JsonNode scores = postAndParse(baseUrl, head, "responses", responses.size(), bytes, chunk, chunks, body);
        List<ResponseScore> out = new ArrayList<>(scores.size());
        for (JsonNode s : scores) {
            JsonNode u = s.get("unsupported");
            JsonNode c = s.get("conflict");
            if (u == null || !u.isNumber() || c == null || !c.isNumber()) {
                throw new IllegalStateException(
                        "launcher /classify returned a non-numeric response score for head " + head);
            }
            List<Span> spans = new ArrayList<>();
            JsonNode sp = s.get("spans");
            if (sp != null && sp.isArray()) {
                for (JsonNode x : sp) {
                    spans.add(new Span(
                            x.path("start").asInt(),
                            x.path("end").asInt(),
                            x.path("unsupported").asDouble(),
                            x.path("conflict").asDouble()));
                }
            }
            out.add(new ResponseScore(u.asDouble(), c.asDouble(), List.copyOf(spans)));
        }
        return out;
    }

    /** One {@code POST /classify} for one chunk; throws on any transport/serving/shape failure. */
    private List<Double> scoreChunk(String baseUrl, String head, List<String> texts, int chunk, int chunks) {
        ObjectNode body = mapper.createObjectNode();
        body.put("head", head);
        ArrayNode arr = body.putArray("texts");
        texts.forEach(arr::add);
        long bytes = textBytes(texts);
        return postAndParseScores(baseUrl, head, "texts", texts.size(), bytes, chunk, chunks, body);
    }

    /** One {@code POST /classify} for one pair chunk; throws on any transport/serving/shape failure. */
    private List<Double> scorePairsChunk(String baseUrl, String head, List<Pair> pairs, int chunk, int chunks) {
        ObjectNode body = mapper.createObjectNode();
        body.put("head", head);
        ArrayNode arr = body.putArray("pairs");
        for (Pair pair : pairs) {
            ObjectNode p = arr.addObject();
            p.put("premise", pair.premise());
            p.put("claim", pair.claim());
        }
        long bytes = pairBytes(pairs);
        return postAndParseScores(baseUrl, head, "pairs", pairs.size(), bytes, chunk, chunks, body);
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? baseUrl : host;
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }

    /**
     * The transport and the array-shape check shared by every request mode: POST, non-2xx and
     * count-mismatch are failures with the same structured log; the caller reads the entries.
     */
    private JsonNode postAndParse(
            String baseUrl, String head, String mode, int count, long bytes, int chunk, int chunks, ObjectNode body) {
        Instant start = Instant.now();
        String host = hostOf(baseUrl);
        try {
            String payload = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/classify"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .header("Authorization", "Bearer " + props.getEncoder().getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = sendThrottled(req, head, mode, count, bytes, chunk, chunks, host, start);
            int status = resp.statusCode();
            if (status == 400) {
                StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                        .field("head", head)
                        .field("mode", mode)
                        .field("count", count)
                        .field("bytes", bytes)
                        .field("chunk", chunk)
                        .field("chunks", chunks)
                        .field("host", host)
                        .field("httpStatus", status)
                        .field("reason", "rejected")
                        .durationMs(start)
                        .log();
                throw new ClientRejection(status);
            }
            JsonNode scores = status / 100 == 2 ? mapper.readTree(resp.body()).get("scores") : null;
            if (status / 100 != 2 || scores == null || !scores.isArray() || scores.size() != count) {
                StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                        .field("head", head)
                        .field("mode", mode)
                        .field("count", count)
                        .field("bytes", bytes)
                        .field("chunk", chunk)
                        .field("chunks", chunks)
                        .field("host", host)
                        .field("httpStatus", status)
                        .field("scoreCount", scores == null ? 0 : scores.size())
                        .durationMs(start)
                        .log();
                throw new IllegalStateException(
                        status / 100 != 2
                                ? "launcher /classify returned HTTP " + status
                                : "launcher /classify returned " + (scores == null ? 0 : scores.size()) + " scores for "
                                        + count + " requested");
            }
            // Size and duration on the success path too: this mode sends whole retrieved documents,
            // so its cost is the one an operator most needs in Loki (backend/AGENTS.md § Logging).
            StructuredLog.info(log, Markers.OPS, "encoder.classify.complete")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("httpStatus", status)
                    .field("scoreCount", scores.size())
                    .durationMs(start)
                    .log();
            return scores;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("reason", "interrupted")
                    .durationMs(start)
                    .log();
            throw new IllegalStateException("encoder scoring interrupted", e);
        } catch (JsonProcessingException e) {
            // Jackson echoes body snippets in its message; OPS stays categorical and the throw is
            // unchained so ClassifierWorker's .cause(e) cannot re-egress it. Detail at DEBUG.
            StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("reason", "parse")
                    .durationMs(start)
                    .log();
            log.debug("encoder.classify parse failure detail head={} host={}", head, host, e);
            throw new IllegalStateException("launcher /classify parse failure"); // NOPMD PreserveStackTrace
        } catch (IOException e) {
            throw transportFailure(e, head, mode, count, bytes, chunk, chunks, host, start);
        }
    }

    /**
     * The exception for a failed send. A connection that never opened ({@link #isUnreachable}) means
     * the model is not running: the availability is marked down at once, so the next sweep skips
     * instead of trying again, and the throw is an {@link EncoderUnreachableException} that the worker
     * hands back without spending an attempt. Any other transport failure is a fault and fails the
     * sweep as before.
     */
    private IllegalStateException transportFailure(
            IOException e,
            String head,
            String mode,
            int count,
            long bytes,
            int chunk,
            int chunks,
            String host,
            Instant start) {
        if (isUnreachable(e)) {
            String reason = "unreachable: " + e.getClass().getSimpleName();
            onUnreachable.accept(reason);
            StructuredLog.info(log, Markers.OPS, "encoder.classify.unreachable")
                    .message("encoder at %s is not answering (%s); pausing until it does", host, reason)
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("reason", reason)
                    .durationMs(start)
                    .log();
            return new EncoderUnreachableException("launcher /classify unreachable", e);
        }
        StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                .field("head", head)
                .field("mode", mode)
                .field("count", count)
                .field("bytes", bytes)
                .field("chunk", chunk)
                .field("chunks", chunks)
                .field("host", host)
                .field("reason", "transport")
                .durationMs(start)
                .cause(e)
                .log();
        return new IllegalStateException("launcher /classify transport failure", e);
    }

    /**
     * Whether a send failed before a connection existed: refused, connect timeout, closed channel, or
     * an unresolvable host. The JDK client sometimes reports a refusal as a {@code ConnectException}
     * whose cause is the channel's, so the causes are checked too.
     */
    static boolean isUnreachable(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof HttpConnectTimeoutException
                    || t instanceof ClosedChannelException
                    || t instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    /**
     * One POST under the inflight permit, retried with backoff when the encoder throttles (429, 503):
     * the encoder's queue is short by design (it bounds memory), so the wait belongs here. Honours a
     * numeric Retry-After; otherwise 2 s doubling, capped at 30 s. After {@link #MAX_THROTTLE_RETRIES}
     * the last response is returned and the caller fails the request as before.
     */
    private HttpResponse<String> sendThrottled(
            HttpRequest req,
            String head,
            String mode,
            int count,
            long bytes,
            int chunk,
            int chunks,
            String host,
            Instant start)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            inflight.acquire();
            HttpResponse<String> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            } finally {
                inflight.release();
            }
            int status = resp.statusCode();
            if ((status != 429 && status != 503) || attempt >= MAX_THROTTLE_RETRIES) {
                return resp;
            }
            long waitMs = resp.headers()
                    .firstValue("Retry-After")
                    .map(v -> v.matches("\\d+") ? Long.parseLong(v) * 1000 : -1L)
                    .filter(v -> v >= 0)
                    .orElse(Math.min(30_000L, 2_000L << attempt));
            StructuredLog.info(log, Markers.OPS, "encoder.classify.throttled")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("httpStatus", status)
                    .field("attempt", attempt + 1)
                    .field("waitMs", waitMs)
                    .durationMs(start)
                    .log();
            Thread.sleep(waitMs);
        }
    }

    private List<Double> postAndParseScores(
            String baseUrl, String head, String mode, int count, long bytes, int chunk, int chunks, ObjectNode body) {
        Instant start = Instant.now();
        String host = hostOf(baseUrl);
        try {
            String payload = mapper.writeValueAsString(body);
            if (log.isDebugEnabled()) {
                log.debug(
                        "encoder.classify.request.body head={} mode={} count={} bytes={} chunk={} chunks={} body={}",
                        head,
                        mode,
                        count,
                        bytes,
                        chunk,
                        chunks,
                        payload);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/classify"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .header("Authorization", "Bearer " + props.getEncoder().getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = sendThrottled(req, head, mode, count, bytes, chunk, chunks, host, start);
            int status = resp.statusCode();
            if (status / 100 != 2) {
                StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                        .field("head", head)
                        .field("mode", mode)
                        .field("count", count)
                        .field("bytes", bytes)
                        .field("chunk", chunk)
                        .field("chunks", chunks)
                        .field("host", host)
                        .field("httpStatus", status)
                        .durationMs(start)
                        .log();
                throw new IllegalStateException("launcher /classify returned HTTP " + status);
            }
            JsonNode scores = mapper.readTree(resp.body()).get("scores");
            if (scores == null || !scores.isArray() || scores.size() != count) {
                int got = scores == null ? 0 : scores.size();
                StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                        .field("head", head)
                        .field("mode", mode)
                        .field("count", count)
                        .field("bytes", bytes)
                        .field("chunk", chunk)
                        .field("chunks", chunks)
                        .field("host", host)
                        .field("httpStatus", status)
                        .field("scoreCount", got)
                        .durationMs(start)
                        .log();
                throw new IllegalStateException(
                        "launcher /classify returned " + got + " scores for " + count + " requested");
            }
            List<Double> out = new ArrayList<>(scores.size());
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0;
            for (JsonNode s : scores) {
                // A NaN score in Node serializes to JSON null; asDouble() would coerce it (or any
                // non-numeric entry) to 0.0 — a silent "clean", the exact outcome this class forbids.
                if (!s.isNumber()) {
                    StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                            .field("head", head)
                            .field("mode", mode)
                            .field("count", count)
                            .field("bytes", bytes)
                            .field("chunk", chunk)
                            .field("chunks", chunks)
                            .field("host", host)
                            .field("httpStatus", status)
                            .field("reason", "non-numeric")
                            .durationMs(start)
                            .log();
                    throw new IllegalStateException("launcher /classify returned a non-numeric score entry");
                }
                double v = s.asDouble();
                out.add(v);
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
                sum += v;
            }
            StructuredLog.info(log, Markers.OPS, "encoder.classify.complete")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("httpStatus", status)
                    .field("scoreCount", out.size())
                    .field("scoreMin", String.format(Locale.ROOT, "%.4f", min))
                    .field("scoreMax", String.format(Locale.ROOT, "%.4f", max))
                    .field("scoreMean", String.format(Locale.ROOT, "%.4f", sum / out.size()))
                    .durationMs(start)
                    .log();
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("reason", "interrupted")
                    .durationMs(start)
                    .log();
            throw new IllegalStateException("encoder scoring interrupted", e);
        } catch (JsonProcessingException e) {
            // Jackson echoes request/response body snippets in its message; OPS must stay
            // categorical with no throwable (backend/AGENTS.md § Logging). Detail at DEBUG.
            // Unchained throw so ClassifierWorker's .cause(e) on sweep failure cannot re-egress.
            StructuredLog.warn(log, Markers.OPS, "encoder.classify.failed")
                    .field("head", head)
                    .field("mode", mode)
                    .field("count", count)
                    .field("bytes", bytes)
                    .field("chunk", chunk)
                    .field("chunks", chunks)
                    .field("host", host)
                    .field("reason", "parse")
                    .durationMs(start)
                    .log();
            log.debug("encoder.classify parse failure detail head={} host={}", head, host, e);
            throw new IllegalStateException("launcher /classify parse failure"); // NOPMD PreserveStackTrace
        } catch (IOException e) {
            throw transportFailure(e, head, mode, count, bytes, chunk, chunks, host, start);
        }
    }
}
