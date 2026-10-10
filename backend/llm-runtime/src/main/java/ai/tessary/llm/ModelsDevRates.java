// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.config.ModelsDevProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The per-model rates a sandbox run is billed at: models.dev's, the catalog OpenCode itself prices
 * from. The launcher writes them into the model it declares to OpenCode, and the run reports the cost
 * OpenCode computes with them, so the price book never prices a sandbox run.
 *
 * <p><b>Live first, bundled always.</b> The live file is fetched at most once per {@link
 * ModelsDevProperties#getRefreshInterval()}, and a failed fetch is not retried for {@link #RETRY_AFTER},
 * so a models.dev outage costs one run one {@link ModelsDevProperties#getFetchTimeout()} rather than
 * every run. Until a fetch succeeds, and for any model the live file lacks, the answer comes from the
 * copy bundled in the jar ({@link #BUNDLED_RESOURCE}), which {@code scripts/refresh-models-dev.sh}
 * keeps current. Only one run fetches at a time; the others read what is already in hand.
 */
@Component
public class ModelsDevRates {

    private static final Logger log = LoggerFactory.getLogger(ModelsDevRates.class);

    /** The vendored models.dev file, refreshed by {@code scripts/refresh-models-dev.sh}. */
    public static final String BUNDLED_RESOURCE = "models-dev/api.json";

    /** How long a failed fetch keeps every run on the copy already in hand before the next attempt. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    /**
     * One model's rates in USD per million tokens, as models.dev states them. A null bucket is one
     * models.dev gives no rate for, which OpenCode then bills at zero.
     */
    public record ModelCost(
            @Nullable BigDecimal input,
            @Nullable BigDecimal output,
            @Nullable BigDecimal cacheRead,
            @Nullable BigDecimal cacheWrite) {

        /** The {@code cost} block of an OpenCode model declaration. */
        public ObjectNode toOpencodeCost(ObjectMapper mapper) {
            ObjectNode cost = mapper.createObjectNode();
            if (input != null) cost.put("input", input);
            if (output != null) cost.put("output", output);
            if (cacheRead != null) cost.put("cache_read", cacheRead);
            if (cacheWrite != null) cost.put("cache_write", cacheWrite);
            return cost;
        }
    }

    private record Rates(Map<String, ModelCost> byId, Instant fetchedAt) {}

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ModelsDevProperties props;
    private final Clock clock;
    private final Map<String, ModelCost> bundled;
    private final ReentrantLock fetching = new ReentrantLock();
    private volatile @Nullable Rates live;
    private volatile Instant nextAttempt = Instant.MIN;

    @Autowired
    public ModelsDevRates(ObjectMapper mapper, ModelsDevProperties props) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(props.getFetchTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                mapper,
                props,
                Clock.systemUTC(),
                loadBundled(mapper));
    }

    ModelsDevRates(
            HttpClient http,
            ObjectMapper mapper,
            ModelsDevProperties props,
            Clock clock,
            Map<String, ModelCost> bundled) {
        this.http = http;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.bundled = Map.copyOf(bundled);
    }

    /**
     * The rates for {@code modelsDevId} ({@code <provider>/<model>}, see {@link ModelCatalog#modelsDevId}),
     * or empty when neither the live file nor the bundled copy prices it. Empty means the run is
     * unpriced; it never means free.
     */
    public Optional<ModelCost> cost(@Nullable String modelsDevId) {
        if (modelsDevId == null || modelsDevId.isBlank()) return Optional.empty();
        Rates current = current();
        ModelCost found = current == null ? null : current.byId().get(modelsDevId);
        return Optional.ofNullable(found != null ? found : bundled.get(modelsDevId));
    }

    /** The bundled copy only, never a fetch: what CI checks every sandbox model against. */
    public Optional<ModelCost> bundledCost(String modelsDevId) {
        return Optional.ofNullable(bundled.get(modelsDevId));
    }

    /**
     * Put the rates for {@code modelsDevId} on a launcher request as {@code model_cost}, or leave the
     * field off when there are none, which the launcher reads as "declare the model with no rates".
     */
    public void putModelCost(ObjectNode launcherBody, @Nullable String modelsDevId) {
        cost(modelsDevId).ifPresent(c -> launcherBody.set("model_cost", c.toOpencodeCost(mapper)));
    }

    private @Nullable Rates current() {
        Rates held = live;
        Instant now = clock.instant();
        boolean fresh = held != null
                && held.fetchedAt().plus(props.getRefreshInterval()).isAfter(now);
        if (fresh || props.getUrl().isBlank() || now.isBefore(nextAttempt) || !fetching.tryLock()) return held;
        try {
            Rates fetched = fetch(now);
            if (fetched != null) live = fetched;
            else nextAttempt = now.plus(RETRY_AFTER);
            return live;
        } finally {
            fetching.unlock();
        }
    }

    private @Nullable Rates fetch(Instant now) {
        Instant start = clock.instant();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getUrl()))
                    .timeout(props.getFetchTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                warnUnreachable(start, "status " + resp.statusCode());
                return null;
            }
            Map<String, ModelCost> byId = parse(mapper, resp.body());
            if (byId.isEmpty()) {
                warnUnreachable(start, "no priced models in the response");
                return null;
            }
            StructuredLog.info(log, Markers.OPS, "models_dev.refreshed")
                    .message("fetched models.dev rates for %d models", byId.size())
                    .field("models", byId.size())
                    .field("bytes", resp.body().length)
                    .durationMs(start)
                    .log();
            return new Rates(byId, now);
        } catch (IOException | RuntimeException e) {
            // A malformed tessary.models-dev.url throws from URI.create: it must cost the live file, never the run.
            warnUnreachable(start, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void warnUnreachable(Instant start, String reason) {
        StructuredLog.warn(log, Markers.OPS, "models_dev.unreachable")
                .message("models.dev fetch failed (%s); sandbox runs price from the bundled copy", reason)
                .field("reason", reason)
                .field("retryAfterMinutes", RETRY_AFTER.toMinutes())
                .durationMs(start)
                .log();
    }

    private static Map<String, ModelCost> loadBundled(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource(BUNDLED_RESOURCE).getInputStream()) {
            return parse(mapper, in.readAllBytes());
        } catch (IOException e) {
            log.warn(
                    Markers.OPS,
                    "bundled models.dev copy unreadable at {}; sandbox runs depend on the live file",
                    BUNDLED_RESOURCE,
                    e);
            return Map.of();
        }
    }

    /**
     * models.dev's file, flattened to {@code <provider>/<model>} → rates. A model with neither an input
     * nor an output rate is left out, so it reads as unpriced rather than as free.
     */
    static Map<String, ModelCost> parse(ObjectMapper mapper, byte[] json) throws IOException {
        JsonNode root = mapper.readTree(json);
        Map<String, ModelCost> byId = new HashMap<>();
        if (root == null || !root.isObject()) return byId;
        for (Map.Entry<String, JsonNode> provider : root.properties()) {
            for (Map.Entry<String, JsonNode> model :
                    provider.getValue().path("models").properties()) {
                JsonNode cost = model.getValue().path("cost");
                BigDecimal input = rate(cost.path("input"));
                BigDecimal output = rate(cost.path("output"));
                if (input == null && output == null) continue;
                byId.put(
                        provider.getKey() + "/" + model.getKey(),
                        new ModelCost(input, output, rate(cost.path("cache_read")), rate(cost.path("cache_write"))));
            }
        }
        return byId;
    }

    /** Read from the number's own text, so 0.1 stays 0.1 rather than its binary approximation. */
    private static @Nullable BigDecimal rate(JsonNode n) {
        return n.isNumber() ? new BigDecimal(n.asText()) : null;
    }
}
