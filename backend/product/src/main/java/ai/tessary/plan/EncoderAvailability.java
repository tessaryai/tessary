// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.config.ObserverProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Whether the groundedness model answers. Gates sweeping only: a down model pauses scoring and resumes from the cursor;
 * capability resolution never reads this. Up means a 200 whose {@code heads} lists {@code groundedness}, since a server
 * with no model loaded also answers 200. Probed off the request thread; {@link #markUnreachable} flips it down
 * immediately.
 */
@Component("classifyService")
public class EncoderAvailability implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(EncoderAvailability.class);

    /** The probe's whole budget: a health check that hangs is a down encoder, not a slow one. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** The head a healthy answer must list; the one encoder-backed classifier this tree ships. */
    static final String HEAD = "groundedness";

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Snapshot(
            boolean available, String reason, @Nullable Instant checkedAt) {}

    private static final Snapshot UNPROBED = new Snapshot(false, "not probed yet", null);

    private final ObserverProperties props;
    private final HttpClient client;
    private volatile Snapshot snapshot = UNPROBED;

    @Autowired
    public EncoderAvailability(ObserverProperties props) {
        this(props, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    EncoderAvailability(ObserverProperties props, HttpClient client) {
        this.props = props;
        this.client = client;
    }

    /** False until the first probe has run. */
    public boolean available() {
        return snapshot.available();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Never fatally: a diagnostic that can stop the platform starting is the worse bug. */
    @EventListener(ApplicationReadyEvent.class)
    public void probeOnBoot() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn(
                    "encoder health probe failed on boot error={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${tessary.observer.encoder.probe-interval-ms:60000}",
            initialDelayString = "${tessary.observer.encoder.probe-interval-ms:60000}")
    public void probeOnSchedule() {
        refresh();
    }

    /** Public so a configuration change can take effect without waiting out the interval. */
    public Snapshot refresh() {
        Snapshot before = snapshot;
        Snapshot after = probe();
        snapshot = after;
        logIfChanged(before, after);
        return after;
    }

    /** The encoder scorer calls this on a refused or timed-out connect. */
    public void markUnreachable(String reason) {
        Snapshot before = snapshot;
        Snapshot after = new Snapshot(false, reason, Instant.now());
        snapshot = after;
        logIfChanged(before, after);
    }

    private void logIfChanged(Snapshot before, Snapshot after) {
        if (before.available() != after.available()) {
            StructuredLog.info(log, Markers.OPS, "encoder.health")
                    .message("encoder %s: %s", after.available() ? "available" : "unavailable", after.reason())
                    .field("available", after.available())
                    .field("reason", after.reason())
                    .field("url", props.getEncoder().getUrl())
                    .log();
        }
    }

    private Snapshot probe() {
        Instant now = Instant.now();
        String base = props.getEncoder().getUrl();
        if (base == null || base.isBlank()) {
            return new Snapshot(false, "tessary.observer.encoder.url is unset", now);
        }
        URI uri;
        try {
            uri = URI.create(base.endsWith("/") ? base + "healthz" : base + "/healthz");
        } catch (IllegalArgumentException e) {
            return new Snapshot(false, "tessary.observer.encoder.url is not a URL", now);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(TIMEOUT).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Snapshot(false, "healthz answered " + response.statusCode(), now);
            }
            if (!listsHead(response.body())) {
                return new Snapshot(false, "healthz answered 200 without the groundedness head", now);
            }
            return new Snapshot(true, "healthz answered 200", now);
        } catch (IOException e) {
            return new Snapshot(false, "unreachable: " + e.getClass().getSimpleName(), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Snapshot(false, "probe interrupted", now);
        }
    }

    private static boolean listsHead(@Nullable String body) {
        if (body == null || body.isBlank()) return false;
        try {
            JsonNode heads = JSON.readTree(body).path("heads");
            if (!heads.isArray()) return false;
            for (JsonNode head : heads) {
                if (HEAD.equals(head.asText())) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Never {@code DOWN}: the gated classifiers are optional, and container health checks read the top-level document.
     */
    @Override
    public Health health() {
        Snapshot s = snapshot;
        Health.Builder h = s.available() ? Health.up() : Health.unknown();
        return h.withDetail("reason", s.reason())
                .withDetail(
                        "checkedAt",
                        s.checkedAt() == null ? "never" : s.checkedAt().toString())
                .withDetail("configured", !props.getEncoder().getUrl().isBlank())
                .build();
    }
}
