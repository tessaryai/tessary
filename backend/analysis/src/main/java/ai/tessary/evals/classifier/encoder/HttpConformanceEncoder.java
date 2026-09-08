// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.encoder;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The production {@link ConformanceEncoder}: the classify-service's {@code POST /embed} endpoint,
 * which serves the conformance fit contract (the checkpoint's own {@code tokenizer.json} at
 * truncation 256 → real ONNX session → mask mean pooling → L2 normalisation) inside the process
 * that exists so CPU inference "can only ever kill its own task" — the accepted plan that retires
 * {@code OnnxConformanceEncoder}'s in-JVM interim posture. Selected by
 * {@code evals.classifier.conformance.encoder-mode=http} (the default); the endpoint and secret
 * are the SAME {@code evals.observer.encoder.url} / {@code .api-key} the encoder classifiers
 * already reach the service with ({@code LauncherEncoderScorer}) — one service, one config.
 *
 * <p><b>Enablement blockers, for the record:</b> this endpoint removed ONE of the two named
 * {@code sop_conformance_enabled} blockers (embedding ran in the backend JVM); the other,
 * intent-at-ingest, has since shipped too — conversation-grain intent resolution, owned by the
 * conformance classifier (paid-only since #1072).
 * The capability flag nonetheless stays off for every org — what holds it now is that no compiled
 * bundle exists to serve, which is a supply question rather than a capability one (see
 * {@code Capability}'s {@code SOP_CONFORMANCE} note).
 *
 * <p><b>Fail-loud, end to end.</b> Same posture as {@code OnnxConformanceEncoder} and
 * {@code LauncherEncoderScorer}: an unconfigured URL, transport failure, non-2xx (including the
 * service's 400 refusal of a checkpoint absent from its {@code embedders.json}), a response whose
 * checkpoint echo differs from the requested one, a vector count that doesn't match the request,
 * inconsistent dimensions, or a non-numeric component all throw — the sweep fails loudly and is
 * retried on subsequent heartbeats instead of silently scoring on garbage. There are deliberately
 * no in-client retries: per the classify-service contract ("the backend's sweep retry is the
 * backpressure"), the bounded retry loop is the classifier worker's heartbeat + dead-letter
 * budget ({@code evals.classifier.max-attempts}), exactly as every other {@code /classify} call.
 *
 * <p><b>Request shaping.</b> Batches are split into sub-requests bounded by
 * {@link #MAX_TEXTS_PER_REQUEST} texts and {@link #MAX_TEXT_BYTES_PER_REQUEST} summed UTF-8 text
 * bytes ({@code LauncherEncoderScorer}'s twin bounds — the service caps bodies at 8&nbsp;MB and
 * embed batches at 256 texts, so every chunk clears both with headroom). A bounded LRU caches
 * embeddings by (checkpoint, text): the sweep re-scores whole conversations as they grow, so the
 * same turn text recurs across passes (the same cache {@code OnnxConformanceEncoder} keeps).
 */
@Service
@ConditionalOnProperty(
        prefix = "evals.classifier.conformance",
        name = "encoder-mode",
        havingValue = "http",
        matchIfMissing = true)
public class HttpConformanceEncoder implements ConformanceEncoder {

    private static final Logger log = LoggerFactory.getLogger(HttpConformanceEncoder.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Generous per-request cap, matching {@code LauncherEncoderScorer}: a 32-text chunk through an
     * fp32 sentence encoder on the service's CPU envelope lands in minutes territory only when the
     * task is saturated, and the service's own queue timeout (20s default) 429s long before this.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /** Max texts per {@code /embed} request — {@code LauncherEncoderScorer}'s measured bound. */
    static final int MAX_TEXTS_PER_REQUEST = 32;

    /** Max summed UTF-8 text bytes per request — half the service's 8 MB body cap. */
    static final long MAX_TEXT_BYTES_PER_REQUEST = 4L * 1024 * 1024;

    private static final int EMBED_CACHE_MAX = 4_096;

    private final ObserverProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /** Bounded (checkpoint, text)-addressed embedding cache; see {@code OnnxConformanceEncoder}'s twin. */
    private final Map<String, float[]> embedCache = Collections.synchronizedMap(new LruCache<>(EMBED_CACHE_MAX));

    private static final class LruCache<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final int max;

        LruCache(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
        }
    }

    public HttpConformanceEncoder(ObserverProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public List<float[]> encode(String checkpoint, List<String> texts) {
        String baseUrl = props.getEncoder().getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("conformance embedding needs evals.observer.encoder.url"
                    + " (encoder-mode http serves checkpoint '" + checkpoint
                    + "' from the classify-service /embed endpoint)");
        }
        Instant start = Instant.now();
        // Resolve cache hits first, then fetch only the distinct misses, in first-seen order.
        Set<String> missSet = new LinkedHashSet<>(); // insertion-ordered: chunking must preserve first-seen order
        for (String text : texts) {
            if (!embedCache.containsKey(cacheKey(checkpoint, text))) {
                missSet.add(text);
            }
        }
        List<String> misses = new ArrayList<>(missSet);
        if (!misses.isEmpty()) {
            List<List<String>> chunks = chunk(misses, MAX_TEXTS_PER_REQUEST, MAX_TEXT_BYTES_PER_REQUEST);
            int dim = -1;
            int cursor = 0;
            for (int i = 0; i < chunks.size(); i++) {
                List<String> chunkTexts = chunks.get(i);
                List<float[]> vectors = embedChunk(baseUrl, checkpoint, chunkTexts, dim, i + 1, chunks.size());
                dim = vectors.get(0).length;
                for (float[] vector : vectors) {
                    embedCache.put(cacheKey(checkpoint, misses.get(cursor++)), vector);
                }
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            float[] vector = embedCache.get(cacheKey(checkpoint, text));
            if (vector == null) {
                // Only reachable if the cache evicted mid-call under extreme concurrent churn;
                // refusing beats returning a partial result.
                throw new IllegalStateException("conformance embedding cache lost a vector mid-call — retry the sweep");
            }
            out.add(vector);
        }
        StructuredLog.info(log, Markers.OPS, "conformance.encoder.embed")
                .message("embedded " + texts.size() + " texts (" + misses.size() + " computed) via /embed for '"
                        + checkpoint + "'")
                .field("checkpoint", checkpoint)
                .field("count", texts.size())
                .field("computed", misses.size())
                .durationMs(start)
                .log();
        return out;
    }

    private static String cacheKey(String checkpoint, String text) {
        // NUL separator: checkpoint names never contain it, so a (checkpoint, text) key cannot
        // collide with any other pair's.
        return checkpoint + '\0' + text;
    }

    /**
     * Split {@code texts} into request-sized sub-batches, preserving order: at most
     * {@code maxCount} texts and {@code maxBytes} summed UTF-8 text bytes per chunk; a single text
     * over the byte budget goes alone, never dropped or truncated ({@code LauncherEncoderScorer}'s
     * twin — the serving-side tokenizer owns truncation to the model window).
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
     * One {@code POST /embed} for one chunk; throws on any transport/serving/shape failure.
     * {@code expectedDim} pins cross-chunk dimensional consistency (-1 on the first chunk).
     */
    private List<float[]> embedChunk(
            String baseUrl, String checkpoint, List<String> texts, int expectedDim, int chunk, int chunks) {
        Instant start = Instant.now();
        String host = hostOf(baseUrl);
        ObjectNode body = mapper.createObjectNode();
        body.put("checkpoint", checkpoint);
        ArrayNode arr = body.putArray("texts");
        texts.forEach(arr::add);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/embed"))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .header("Authorization", "Bearer " + props.getEncoder().getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status / 100 != 2) {
                warnFailed(checkpoint, texts.size(), chunk, chunks, host, status, "http-status", start);
                throw new IllegalStateException("classify-service /embed returned HTTP " + status + " for checkpoint '"
                        + checkpoint + "' — refusing to score without embeddings");
            }
            return parseVectors(resp.body(), checkpoint, texts.size(), expectedDim, chunk, chunks, host, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnFailed(checkpoint, texts.size(), chunk, chunks, host, 0, "interrupted", start);
            throw new IllegalStateException("conformance embedding interrupted", e);
        } catch (JsonProcessingException e) {
            // Jackson echoes body snippets in its message; OPS stays categorical with no throwable
            // (backend/AGENTS.md § Logging). Detail at DEBUG; unchained throw so a caller's
            // .cause(e) cannot re-egress it.
            warnFailed(checkpoint, texts.size(), chunk, chunks, host, 0, "parse", start);
            log.debug("conformance /embed parse failure detail checkpoint={} host={}", checkpoint, host, e);
            throw new IllegalStateException("classify-service /embed parse failure"); // NOPMD PreserveStackTrace
        } catch (IOException e) {
            warnFailed(checkpoint, texts.size(), chunk, chunks, host, 0, "transport", start);
            throw new IllegalStateException(
                    "classify-service /embed transport failure — conformance sweep fails loudly, never scores on"
                            + " garbage",
                    e);
        }
    }

    /** Validate and decode one response: checkpoint echo, count, dim consistency, numeric entries. */
    private List<float[]> parseVectors(
            String responseBody,
            String checkpoint,
            int count,
            int expectedDim,
            int chunk,
            int chunks,
            String host,
            Instant start)
            throws JsonProcessingException {
        JsonNode root = mapper.readTree(responseBody);
        String served = root.path("checkpoint").asText("");
        if (!checkpoint.equals(served)) {
            warnFailed(checkpoint, count, chunk, chunks, host, 200, "checkpoint-mismatch", start);
            throw new IllegalStateException("classify-service /embed served checkpoint '" + served
                    + "' but the bundle was fitted against '" + checkpoint
                    + "' — refusing to embed with a different encoder than the fit");
        }
        JsonNode vectors = root.get("vectors");
        int dim = root.path("dim").asInt(-1);
        if (vectors == null || !vectors.isArray() || vectors.size() != count) {
            warnFailed(checkpoint, count, chunk, chunks, host, 200, "vector-count", start);
            throw new IllegalStateException("classify-service /embed returned " + (vectors == null ? 0 : vectors.size())
                    + " vectors for " + count + " texts");
        }
        if (dim <= 0 || (expectedDim > 0 && dim != expectedDim)) {
            warnFailed(checkpoint, count, chunk, chunks, host, 200, "dim-mismatch", start);
            throw new IllegalStateException("classify-service /embed returned dim " + dim
                    + (expectedDim > 0 ? " after a previous chunk's " + expectedDim : "")
                    + " — refusing dimensionally inconsistent embeddings");
        }
        List<float[]> out = new ArrayList<>(count);
        for (JsonNode vector : vectors) {
            if (!vector.isArray() || vector.size() != dim) {
                warnFailed(checkpoint, count, chunk, chunks, host, 200, "dim-mismatch", start);
                throw new IllegalStateException("classify-service /embed returned a vector of width "
                        + (vector.isArray() ? vector.size() : 0) + " against declared dim " + dim);
            }
            float[] values = new float[dim];
            for (int j = 0; j < dim; j++) {
                JsonNode component = vector.get(j);
                // A NaN in Node serializes to JSON null; asDouble() would coerce it (or any
                // non-numeric entry) to 0.0 — a silently-zeroed embedding, the exact outcome the
                // ConformanceEncoder contract forbids.
                if (!component.isNumber()) {
                    warnFailed(checkpoint, count, chunk, chunks, host, 200, "non-numeric", start);
                    throw new IllegalStateException("classify-service /embed returned a non-numeric component");
                }
                values[j] = (float) component.asDouble();
            }
            out.add(values);
        }
        return out;
    }

    private void warnFailed(
            String checkpoint,
            int count,
            int chunk,
            int chunks,
            String host,
            int status,
            String reason,
            Instant start) {
        StructuredLog.warn(log, Markers.OPS, "conformance.encoder.embed.failed")
                .message("conformance /embed call failed (" + reason + ") for checkpoint '" + checkpoint + "'")
                .field("checkpoint", checkpoint)
                .field("count", count)
                .field("chunk", chunk)
                .field("chunks", chunks)
                .field("host", host)
                .field("httpStatus", status)
                .field("reason", reason)
                .durationMs(start)
                .log();
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? baseUrl : host;
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }
}
