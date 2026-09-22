// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.config.ObserverProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
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
 * Whether this instance can run an encoder-backed classifier at all: the answer classify-service's
 * {@code GET /healthz} gave the last time it was asked.
 *
 * <p>The instance-level half of the two-level switch on {@code groundedness}.
 * {@link CapabilityService} reports it as unavailable, and resolves it off for every org, while
 * this says no; an org's own override is the other half and only ever narrows. Without this, a
 * fresh install swept groundedness on every project with nowhere to send the requests: the job
 * failed five times a tick, dead-lettered, and retried every half hour, and the only trace was a
 * log line.
 *
 * <p>Probed on boot and every {@code tessary.observer.encoder.probe-interval-ms}, never on a request
 * thread: {@link #available()} is a field read, cheap enough for the capability resolution that
 * runs on every catalog reconcile and every session. The same answer is served as the {@code
 * classifyService} health contributor in the {@code encoder} group, so {@code /actuator/health/encoder} says why when it is no; see {@link #health()} for why that is
 * never {@code DOWN}.
 *
 * <p>A blank URL is "not configured", and reads exactly like an unreachable one to the capability
 * layer; the reason differs only in the detail.
 */
@Component("classifyService")
public class EncoderAvailability implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(EncoderAvailability.class);

    /** The probe's whole budget: a health check that hangs is a down encoder, not a slow one. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** What the last probe found, and when. */
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
     * without waiting out the interval; the capability layer reads the result, it never calls this.
     */
    public Snapshot refresh() {
        Snapshot before = snapshot;
        Snapshot after = probe();
        snapshot = after;
        if (before.available() != after.available()) {
            StructuredLog.info(log, Markers.OPS, "encoder.health")
                    .message("encoder %s: %s", after.available() ? "available" : "unavailable", after.reason())
                    .field("available", after.available())
                    .field("reason", after.reason())
                    .field("url", props.getEncoder().getUrl())
                    .log();
        }
        return after;
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
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) return new Snapshot(true, "healthz answered 200", now);
            return new Snapshot(false, "healthz answered " + response.statusCode(), now);
        } catch (IOException e) {
            return new Snapshot(false, "unreachable: " + e.getClass().getSimpleName(), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Snapshot(false, "probe interrupted", now);
        }
    }

    /**
     * {@code UP} while the encoder answers, {@code UNKNOWN} with the reason otherwise — never {@code
     * DOWN}. The classifiers this gates are optional, and the top-level health document is what a
     * container health check and a load balancer read: an instance that chose not to run
     * classify-service must not report itself unhealthy for it. The reason is in the {@code encoder}
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
