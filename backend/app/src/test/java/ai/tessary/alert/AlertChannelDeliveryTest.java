// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.alert.AlertChannelDtos.UpsertChannelRequest;
import ai.tessary.alert.channel.AlertPayload;
import ai.tessary.alert.channel.ChannelHttp;
import ai.tessary.alert.channel.WebhookChannel;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Acceptance for alert channels. Exercised against the real pgvector Postgres
 * (Testcontainers) so the alert schema applies for real. Proves the full delivery path: a fired
 * {@link AlertFiredEvent} fans out through {@code AlertDeliveryListener} to all enabled channels for the
 * project, the real SPI impls serialize, sign, and render the payload, each outbound call is recorded in
 * the delivery-attempt log, and the at-most-once guard de-dupes a re-fire.
 *
 * <p>Outbound HTTP is captured by a recording {@link HttpClient} installed via the {@link ChannelHttp}
 * test seam. Channels are configured with public TEST-NET-3 (203.0.113.x) URLs so {@code UrlGuard} runs
 * for real and passes; the recording client (which never opens a socket) then captures the request URI,
 * headers, and body, so the SSRF guard, the JSON bodies, the HMAC signature, and the connector auth
 * headers are all exercised end to end.
 */
@SpringBootTest
class AlertChannelDeliveryTest {

    @Autowired
    TenantService tenants;

    @Autowired
    ai.tessary.plan.CapabilityService capabilities;

    @Autowired
    AlertChannelService channelService;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    AlertEventRepository alertEvents;

    @Autowired
    DeliveryAttemptRepository attempts;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    ObjectMapper mapper;

    private final RecordingClient client = new RecordingClient();

    @BeforeEach
    void installClient() {
        ChannelHttp.setClientForTest(client);
    }

    @AfterEach
    void resetClient() {
        ChannelHttp.setClientForTest(null);
    }

    @Test
    void firedAlertFansOutToSlackWebhookGenericWebhookAndPagerDuty() throws Exception {
        var fixture = TenantFixture.bootstrap(tenants, "alert-channel");
        String pid = fixture.project().id();
        // The precondition the Slack assertion below rests on, stated rather than assumed: this test
        // exercises the adapter path, not the capability gate.
        assertTrue(
                capabilities.isEnabled(fixture.org().id(), ai.tessary.plan.Capability.SLACK),
                "this test exercises the adapter path, so the org must HAVE slack_enabled");

        // Generic webhook (with HMAC signing secret), public TEST-NET URL passes UrlGuard.
        channelService.create(
                pid,
                new UpsertChannelRequest(
                        "webhook",
                        "ops-webhook",
                        true,
                        json("{\"url\":\"https://203.0.113.10/hook\",\"secret\":\"sek\"}")));
        // A Slack channel. This posts to slack-service, not to Slack directly, and that service is not
        // deployed in this suite, so the attempt is expected to fail and say why in the delivery log: an
        // undeployed adapter must make Slack deliveries visibly fail rather than silently vanish.
        //
        // Not captured by the recording client either, deliberately: SlackDelivery uses its own HttpClient
        // rather than the ChannelHttp seam, because the adapter lives at a private address that the SSRF
        // guard would rightly reject.
        channelService.create(
                pid,
                new UpsertChannelRequest(
                        "slack", "team-slack", true, json("{\"url\":\"https://203.0.113.11/slack\"}")));
        // PagerDuty connector (url overridden to a TEST-NET path so the recorder captures the enqueue).
        channelService.create(
                pid,
                new UpsertChannelRequest(
                        "pagerduty",
                        "oncall",
                        true,
                        json("{\"routing_key\":\"R0UT1NG\",\"url\":\"https://203.0.113.12/v2/enqueue\"}")));
        // A DISABLED channel must never be delivered to.
        channelService.create(
                pid,
                new UpsertChannelRequest(
                        "webhook", "off-webhook", false, json("{\"url\":\"https://203.0.113.99/nope\"}")));

        AlertEventRow event = persistFiredEvent(pid);
        publisher.publishEvent(new AlertFiredEvent(event));

        // Fan-out runs on the @Async("alertDeliveryExecutor") pool (it does not block the publisher),
        // so wait until the delivery-attempt log shows all 3 enabled channels resolved.
        awaitDeliveries(pid, 3);

        // The two directly-delivered channels received the POST; the disabled one did not.
        assertTrue(client.bodies.containsKey("/hook"), "generic webhook reached");
        assertTrue(client.bodies.containsKey("/v2/enqueue"), "pagerduty connector reached (creates an incident)");
        assertTrue(!client.bodies.containsKey("/nope"), "disabled channel skipped");
        assertTrue(!client.bodies.containsKey("/slack"), "slack no longer goes to slack.com — it goes to the adapter");

        // The generic webhook payload is the stable, versioned, signed envelope.
        JsonNode hook = mapper.readTree(client.bodies.get("/hook"));
        assertEquals(AlertPayload.SCHEMA_VERSION, hook.get("schema_version").asText());
        assertEquals(event.id(), hook.get("event_id").asText());
        assertEquals(pid, hook.get("project_id").asText());
        // payload is inlined as a first-class object, not a JSON-in-a-string.
        assertEquals(
                42,
                hook.path("payload")
                        .path("classifiers")
                        .get(0)
                        .path("event_count")
                        .asInt());
        String sig = client.headers
                .get("/hook")
                .firstValue(WebhookChannel.SIGNATURE_HEADER)
                .orElse(null);
        assertNotNull(sig, "HMAC signature header present");
        assertEquals("sha256=" + WebhookChannel.hmacSha256("sek", client.bodies.get("/hook")), sig);

        // PagerDuty got a v2 trigger with the routing key + stable dedup key.
        JsonNode pd = mapper.readTree(client.bodies.get("/v2/enqueue"));
        assertEquals("R0UT1NG", pd.get("routing_key").asText());
        assertEquals("trigger", pd.get("event_action").asText());
        assertEquals(AlertPayload.dedupKey(event), pd.get("dedup_key").asText());

        // Delivery-attempt log: the two direct channels delivered, the disabled one absent, and Slack
        // recorded as a failure naming the missing adapter. A delivery that cannot be made must leave a
        // trace, since this log is the only visibility surface the fan-out has.
        List<DeliveryAttemptRow> log = attempts.listByProject(pid, 100);
        long delivered = log.stream()
                .filter(a -> a.status().equals(DeliveryAttemptRow.Status.DELIVERED))
                .count();
        assertEquals(2, delivered, "the 2 directly-delivered channels succeeded");
        assertEquals(3, log.size(), "all 3 enabled channels were attempted");
        assertTrue(
                log.stream()
                        .anyMatch(a -> a.status().equals(DeliveryAttemptRow.Status.FAILED)
                                && String.valueOf(a.error()).contains("slack-service")),
                "the slack attempt failed naming the undeployed adapter");

        // Re-firing the same event is at-most-once: the (event, channel) claim de-dupes, no new sends.
        client.bodies.clear();
        publisher.publishEvent(new AlertFiredEvent(event));
        // The re-fire is a negative assertion on an async path: give the dispatcher time to run (and find
        // every slot already claimed) before asserting nothing was re-sent.
        Thread.sleep(500);
        assertTrue(client.bodies.isEmpty(), "a re-fired event does not re-send (at-most-once)");
    }

    /** Polls the delivery-attempt log until {@code expected} attempts have resolved (not pending). */
    private void awaitDeliveries(String pid, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            long resolved = attempts.listByProject(pid, 100).stream()
                    .filter(a -> !a.status().equals(DeliveryAttemptRow.Status.PENDING))
                    .count();
            if (resolved >= expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + expected + " resolved delivery attempts");
    }

    /**
     * Persist a digest firing. alert_event.alert_rule_id is a REAL FK to alert_rule, so
     * a matching roll-up rule must exist first.
     */
    private AlertEventRow persistFiredEvent(String pid) {
        String now = Instant.now().toString();
        String ruleId = Ids.ulid();
        rules.insert(new AlertRuleRow(
                ruleId,
                pid,
                AlertRuleRow.RuleType.DIGEST,
                "daily",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "* * * * * *",
                null,
                true,
                null,
                null,
                null,
                null,
                "{}",
                now,
                now));
        AlertEventRow event = new AlertEventRow(
                Ids.ulid(),
                pid,
                ruleId,
                null,
                AlertRuleRow.RuleType.DIGEST,
                null,
                AlertEventRow.State.FIRING,
                now,
                now,
                42,
                null,
                "{\"classifiers\":[{\"classifier_key\":\"frustration\",\"event_count\":42}]}",
                null,
                now,
                now);
        assertTrue(alertEvents.insertIfAbsent(event), "fired event persisted");
        return event;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String s) {
        try {
            return mapper.readValue(s, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An {@link HttpClient} that records each request's path, headers, and body and returns a synthetic
     * 202, no socket is opened. Keyed by URI path so the test can assert per-channel payloads.
     */
    private static final class RecordingClient extends HttpClient {
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final Map<String, HttpHeaders> headers = new ConcurrentHashMap<>();

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            String path = request.uri().getPath();
            bodies.put(path, BodyPublisherReader.read(request));
            headers.put(path, request.headers());
            @SuppressWarnings("unchecked")
            HttpResponse<T> typed = (HttpResponse<T>) new FakeResponse(request.uri());
            return typed;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }
    }

    /** Drains a request's {@link HttpRequest.BodyPublisher} into a UTF-8 string. */
    private static final class BodyPublisherReader implements Flow.Subscriber<java.nio.ByteBuffer> {
        private final StringBuilder sb = new StringBuilder();

        static String read(HttpRequest request) {
            Optional<HttpRequest.BodyPublisher> bp = request.bodyPublisher();
            if (bp.isEmpty()) return "";
            BodyPublisherReader r = new BodyPublisherReader();
            bp.get().subscribe(r);
            return r.sb.toString();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer item) {
            byte[] b = new byte[item.remaining()];
            item.get(b);
            sb.append(new String(b, StandardCharsets.UTF_8));
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }

    /** A minimal 202 response; only {@code statusCode} is read by the channels. */
    private record FakeResponse(URI uri) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 202;
        }

        @Override
        public String body() {
            return "";
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
