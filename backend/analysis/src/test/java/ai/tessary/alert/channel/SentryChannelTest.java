// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Sentry connector. The bugs: an event Sentry drops (a non-hex {@code event_id}), re-deliveries that
 * open a new issue each time (a fingerprint that is not the stable dedup key), a missing auth header, and
 * a non-2xx recorded as delivered.
 */
class SentryChannelTest {

    private static final String STORE = "https://203.0.113.30/api/42/store/";
    private static final String CONFIG = "{\"store_url\":\"" + STORE + "\",\"public_key\":\"pk1\"}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SentryChannel channel = new SentryChannel(mapper);

    @AfterEach
    void resetSeam() {
        ChannelHttp.setClientForTest(null);
        Thread.interrupted();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{\"public_key\":\"pk1\"}",
                "{\"store_url\":null,\"public_key\":\"pk1\"}",
                "{\"store_url\":\" \",\"public_key\":\"pk1\"}",
                "{\"store_url\":\"https://203.0.113.30/\"}",
                "{\"store_url\":\"https://203.0.113.30/\",\"public_key\":\"\"}"
            })
    void aConfigWithoutStoreUrlAndKeyIsRefusedBeforeAnySend(String config) {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, "");
        ChannelHttp.setClientForTest(client);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));

        assertEquals(AlertError.INVALID_CHANNEL_CONFIG, e.error());
        assertNull(client.lastRequest);
    }

    @Test
    void anAcceptedEventIsGroupedByTheStableDedupKeyAndAuthenticated() throws Exception {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, "{\"id\":\"x\"}");
        ChannelHttp.setClientForTest(client);
        AlertEventRow event = FiredEvents.caseOpened();

        DeliveryResult result = channel.deliver(event, FiredEvents.config(mapper, CONFIG));

        assertEquals(DeliveryResult.success(200), result);
        assertEquals(URI.create(STORE), requireNonNull(client.lastRequest).uri());
        assertEquals(
                "Sentry sentry_version=7, sentry_client=tessary/1.0, sentry_key=pk1",
                client.lastRequest.headers().firstValue("X-Sentry-Auth").orElseThrow());
        ObjectNode sent = (ObjectNode) mapper.readTree(client.lastBody);
        JsonNode eventId = sent.remove("event_id");
        assertEquals(32, eventId.asText().length(), "Sentry's store API wants 32 hex characters");
        assertEquals(eventId.asText(), eventId.asText().replaceAll("[^0-9a-f]", ""), "and only hex");
        ObjectNode expected = mapper.createObjectNode();
        expected.put("timestamp", FiredEvents.FIRED_AT);
        expected.put("platform", "other");
        expected.put("logger", "tessary.alert");
        expected.put("level", "info");
        expected.put("message", "C-3 · Latency up 2x");
        expected.putArray("fingerprint").add("evals-alert-evt_case");
        expected.set("extra", AlertPayload.envelope(event, mapper));
        assertEquals(expected, sent);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void anythingButAnAcceptedEventIsACategoricalFailure(
            String url, @Nullable Exception fault, int status, DeliveryResult expected, boolean interrupted) {
        ChannelHttp.setClientForTest(
                fault == null ? ScriptedHttpClient.answering(status, "") : ScriptedHttpClient.failingWith(fault));
        String config = "{\"store_url\":\"" + url + "\",\"public_key\":\"pk1\"}";

        assertEquals(expected, channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));
        assertEquals(interrupted, Thread.currentThread().isInterrupted());
    }

    static Stream<Arguments> failures() {
        DeliveryResult network = DeliveryResult.failure(null, "sentry delivery failed (network/guard)");
        return Stream.of(
                Arguments.of(STORE, null, 429, DeliveryResult.failure(429, "sentry returned HTTP 429"), false),
                Arguments.of(STORE, new IOException("reset"), 0, network, false),
                Arguments.of("http://169.254.169.254/latest/", null, 200, network, false),
                Arguments.of(STORE, new InterruptedException(), 0, DeliveryResult.failure(null, "interrupted"), true));
    }
}
