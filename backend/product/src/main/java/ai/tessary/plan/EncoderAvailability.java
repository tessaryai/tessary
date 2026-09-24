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
 * Whether the groundedness model answers right now: what its {@code GET /healthz} said the last time
 * it was asked, or what the last request to it found.
 *
 * <p>This gates sweeping and nothing else. While it says no, {@code ClassifierService} enqueues no
 * groundedness sweep and {@code ClassifierWorker} hands a claimed one back without spending an
 * attempt, so a model that is asleep or stopped pauses scoring and resumes from the cursor when it
 * answers again. The classifier, its findings and the org's switch stay as they are: capability
 * resolution never reads this. Before, a model that went away hid the classifier and its findings,
 * and a fresh install that had never run it failed the sweep five times a tick and dead-lettered.
 *
 * <p>Up means a 200 whose JSON body lists {@code groundedness} in {@code heads}. A bare 200 is not
 * enough: classify-service answers one with an empty model manifest, and that service does not
 * serve this head.
 *
 * <p>Probed on boot and every {@code tessary.observer.encoder.probe-interval-ms}, never on a request
 * thread: {@link #available()} is a field read. {@link #markUnreachable} flips it down at once when a
 * request finds the model gone, rather than waiting out the interval. The same answer is served as
 * the {@code classifyService} health contributor in the {@code encoder} group, so {@code
 * /actuator/health/encoder} says why when it is no; see {@link #health()} for why that is never
 * {@code DOWN}.
 *
 * <p>A blank URL is "not configured", and reads exactly like an unreachable one to the sweep gate;
 * the reason differs only in the detail.
 */
@Component("classifyService")
public class EncoderAvailability implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(EncoderAvailability.class);

    /** The probe's whole budget: a health check that hangs is a down encoder, not a slow one. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** The head a healthy answer must list; the one encoder-backed classifier this tree ships. */
    static final String HEAD = "groundedness";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What the last probe found, and when. {@code lastAvailableAt} is the newest time the model was
     * found up, kept while it is down; it lives in memory only, so a restart clears it.
     */
    public record Snapshot(
            boolean available,
            String reason,
            @Nullable Instant checkedAt,
            @Nullable Instant lastAvailableAt) {}

    private static final Snapshot UNPROBED = new Snapshot(false, "not probed yet", null, null);

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

    /** The last probe's verdict; false until the first probe has run. */
    public boolean available() {
        return snapshot.available();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** On boot, never fatally: a diagnostic that can stop the platform starting is the worse bug. */
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

    /**
     * Ask the encoder now and record the answer. Public so a configuration change can take effect
     * without waiting out the interval; the sweep gate reads the result, it never calls this.
     */
    public Snapshot refresh() {
        Snapshot before = snapshot;
        Snapshot probed = probe();
        Snapshot after = new Snapshot(
                probed.available(),
                probed.reason(),
                probed.checkedAt(),
                probed.available() ? probed.checkedAt() : before.lastAvailableAt());
        snapshot = after;
        logIfChanged(before, after);
        return after;
    }

    /**
     * Record that a request to the model just found it gone, so the next sweep skips at once instead
     * of waiting for the next probe. The encoder scorer calls this on a refused or timed-out connect;
     * the next probe that finds it up flips it back.
     */
    public void markUnreachable(String reason) {
        Snapshot before = snapshot;
        Snapshot after = new Snapshot(false, reason, Instant.now(), before.lastAvailableAt());
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
            return new Snapshot(false, "tessary.observer.encoder.url is unset", now, null);
        }
        URI uri;
        try {
            uri = URI.create(base.endsWith("/") ? base + "healthz" : base + "/healthz");
        } catch (IllegalArgumentException e) {
            return new Snapshot(false, "tessary.observer.encoder.url is not a URL", now, null);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(TIMEOUT).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Snapshot(false, "healthz answered " + response.statusCode(), now, null);
            }
            if (!listsHead(response.body())) {
                return new Snapshot(false, "healthz answered 200 without the groundedness head", now, null);
            }
            return new Snapshot(true, "healthz answered 200", now, null);
        } catch (IOException e) {
            return new Snapshot(false, "unreachable: " + e.getClass().getSimpleName(), now, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Snapshot(false, "probe interrupted", now, null);
        }
    }

    /** Whether a healthz body is a JSON object whose {@code heads} array holds {@link #HEAD}. */
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
     * {@code UP} while the encoder answers, {@code UNKNOWN} with the reason otherwise — never {@code
     * DOWN}. The classifiers this gates are optional, and the top-level health document is what a
     * container health check and a load balancer read: an instance that chose not to run the
     * groundedness model must not report itself unhealthy for it. The reason is in the {@code encoder}
     * group's details for whoever is asking about the classifier specifically.
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
