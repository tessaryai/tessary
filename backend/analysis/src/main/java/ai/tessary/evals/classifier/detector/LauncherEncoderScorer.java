// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import ai.tessary.evals.config.ObserverProperties;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link EncoderScorer} backed by the classify-service's {@code POST /classify} (in-process
 * transformers.js encoder heads). The endpoint comes from {@code evals.observer.encoder.*} —
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
 * everything clean.
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

    private final ObserverProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public LauncherEncoderScorer(ObserverProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
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
            throw new IllegalStateException("encoder scoring needs evals.observer.encoder.url");
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
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
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
            throw new IllegalStateException("launcher /classify transport failure", e);
        }
    }
}
