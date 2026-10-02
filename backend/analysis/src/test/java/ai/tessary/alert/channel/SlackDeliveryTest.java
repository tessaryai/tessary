// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.SlackProperties;
import ai.tessary.testsupport.LoopbackHttpStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The backend's one route to Slack, against a real loopback stand-in for {@code slack-service}. The adapter
 * reports Slack's refusal in a 200 body, so the bugs are a 200 {@code ok:false} recorded as delivered, a
 * status or reason lost on the way into the delivery log, and a transport fault that throws out of the
 * fan-out instead of being returned as a failure.
 */
class SlackDeliveryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackProperties props = new SlackProperties();

    private LoopbackHttpStub adapter;

    @BeforeEach
    void startAdapter() throws IOException {
        adapter = LoopbackHttpStub.answering(0, "");
        props.setBaseUrl(adapter.baseUrl());
        props.setServiceKey("svc-key");
    }

    @AfterEach
    void stopAdapter() throws IOException {
        adapter.close();
    }

    @ParameterizedTest(name = "HTTP {0} {1}")
    @MethodSource("answers")
    void theAdaptersAnswerBecomesTheDeliveryOutcome(int status, String body, DeliveryResult expected) throws Exception {
        adapter.answer(status, body);

        DeliveryResult result = new SlackDelivery(props, mapper)
                .sendToWebhook("https://hooks.slack.com/services/T/B/x", "*C-3* · Latency up 2x");

        assertEquals(expected, result);
        LoopbackHttpStub.Request seen = adapter.lastRequest();
        assertEquals("Bearer svc-key", seen.headers().get("authorization"));
        assertEquals("/deliver", seen.path());
        assertEquals(
                mapper.readTree("{\"transport\":\"webhook\",\"url\":\"https://hooks.slack.com/services/T/B/x\","
                        + "\"text\":\"*C-3* · Latency up 2x\"}"),
                mapper.readTree(seen.body()));
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of(200, "{\"ok\":true,\"status\":201}", DeliveryResult.success(201)),
                Arguments.of(200, "{\"ok\":true}", DeliveryResult.success(200)),
                Arguments.of(200, "{\"ok\":true,\"status\":null}", DeliveryResult.success(200)),
                Arguments.of(
                        200,
                        "{\"ok\":false,\"error\":\"channel_not_found\",\"status\":404}",
                        DeliveryResult.failure(404, "channel_not_found")),
                Arguments.of(200, "{\"ok\":false}", DeliveryResult.failure(null, "slack refused the message")),
                Arguments.of(
                        200, "not json", DeliveryResult.failure(null, "slack-service returned an unreadable body")),
                Arguments.of(502, "", DeliveryResult.failure(502, "slack-service HTTP 502")));
    }

    @Test
    void anUnreachableAdapterIsAFailureNotAThrow() throws IOException {
        try (ServerSocket closed = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            props.setBaseUrl("http://127.0.0.1:" + closed.getLocalPort());
        }

        DeliveryResult result = new SlackDelivery(props, mapper).sendToWebhook("https://hooks.slack.com/x", "t");

        assertEquals(DeliveryResult.failure(null, "slack-service unreachable"), result);
    }

    @Test
    void anInterruptedSendIsReportedAndTheFlagRestored() {
        adapter.answer(200, "{\"ok\":true}");
        Thread.currentThread().interrupt();

        DeliveryResult result = new SlackDelivery(props, mapper).sendToWebhook("https://hooks.slack.com/x", "t");

        assertEquals(DeliveryResult.failure(null, "interrupted"), result);
        assertEquals(true, Thread.currentThread().isInterrupted());
    }
}
