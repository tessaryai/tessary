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
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * No fallback endpoint. The launcher caps request bodies at 8 MB, so responses are sent in bounded sub-batches; one
 * over-budget response travels alone, never truncated. Every malformed reply throws, so the sweep cannot silently score
 * everything clean; a connection that never opens throws {@link EncoderUnreachableException} and pauses the sweep
 * instead.
 */
@Service
public class LauncherEncoderScorer implements EncoderScorer {

    private static final Logger log = LoggerFactory.getLogger(LauncherEncoderScorer.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Generous: a cold head downloads and loads its model on the first call.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /** Half the 8 MB cap, leaving headroom for JSON escaping. */
    static final long MAX_TEXT_BYTES_PER_REQUEST = 4L * 1024 * 1024;

    /**
     * The head's cost is roughly quadratic in length, so requests are sized by work, not count, to finish inside the
     * timeouts.
     */
    static final long MAX_RESPONSE_TOKENS_PER_REQUEST = 24_000;

    static final int MAX_THROTTLE_RETRIES = 4;

    private final ObserverProperties props;
    private final ObjectMapper mapper;
    private final Consumer<String> onUnreachable;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    /**
     * Every sweep takes a permit before posting, so the encoder sees at most {@code encoder.max-inflight} requests and
     * sheds nothing; the waiting happens here.
     */
    private final Semaphore inflight;

    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public LauncherEncoderScorer(ObserverProperties props, ObjectMapper mapper, EncoderAvailability availability) {
        this(props, mapper, availability::markUnreachable);
    }

    LauncherEncoderScorer(ObserverProperties props, ObjectMapper mapper, Consumer<String> onUnreachable) {
        this(props, mapper, onUnreachable, Thread::sleep);
    }

    LauncherEncoderScorer(
            ObserverProperties props, ObjectMapper mapper, Consumer<String> onUnreachable, Sleeper sleeper) {
        this.props = props;
        this.mapper = mapper;
        this.onUnreachable = onUnreachable;
        this.sleeper = sleeper;
        this.inflight = new Semaphore(Math.max(1, props.getEncoder().getMaxInflight()), true);
    }

    private String requireBaseUrl() {
        String baseUrl = props.getEncoder().getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("encoder scoring needs tessary.observer.encoder.url");
        }
        return baseUrl;
    }

    private static long utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

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
        return out;
    }

    /**
     * Bisect to the offending response: one over-long answer would otherwise re-fail the sweep every tick until it
     * dead-lettered.
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

    static final class ClientRejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        ClientRejection(int status) {
            super("launcher /classify returned HTTP " + status);
            this.status = status;
        }
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

    /** A missing or non-numeric score is a failure, never "clean", or a broken encoder would look healthy. */
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

    private static String hostOf(String baseUrl) {
        String host = URI.create(baseUrl).getHost();
        return host == null || host.isBlank() ? baseUrl : host;
    }

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
            // Logged on success too: this mode sends whole documents, so its cost is what an operator needs in Loki.
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
            // Jackson echoes body snippets in its message, so the throw is unchained and detail stays at DEBUG.
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
     * An unreachable model marks availability down and throws {@link EncoderUnreachableException}, which costs no
     * attempt. Any other transport failure fails the sweep.
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
     * The JDK client sometimes reports a refusal as a {@code ConnectException} whose cause is the channel's, so causes
     * are checked too.
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
     * The encoder's queue is short by design, so the wait belongs here. Honours a numeric Retry-After, else 2 s
     * doubling capped at 30 s.
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
            sleeper.sleep(waitMs);
        }
    }
}
