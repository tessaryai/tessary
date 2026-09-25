// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The PagerDuty connector's refusals. The happy path (a v2 trigger with the routing and dedup keys) is
 * proven end to end in {@code AlertChannelDeliveryTest}; the bugs here are an enqueue with no routing key,
 * and a rejected or unreachable enqueue recorded as an incident opened.
 */
class PagerDutyChannelTest {

    private static final String URL = "https://203.0.113.40/v2/enqueue";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PagerDutyChannel channel = new PagerDutyChannel(mapper);

    @AfterEach
    void resetSeam() {
        ChannelHttp.setClientForTest(null);
        Thread.interrupted();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"routing_key\":null}", "{\"routing_key\":\"  \"}"})
    void aConfigWithoutARoutingKeyIsRefusedBeforeAnySend(String config) {
        ScriptedHttpClient client = ScriptedHttpClient.answering(202, "");
        ChannelHttp.setClientForTest(client);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));

        assertEquals(AlertError.INVALID_CHANNEL_CONFIG, e.error());
        assertNull(client.lastRequest);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void anythingButAnAcceptedEnqueueIsACategoricalFailure(
            String url, @Nullable Exception fault, int status, DeliveryResult expected, boolean interrupted) {
        ChannelHttp.setClientForTest(
                fault == null ? ScriptedHttpClient.answering(status, "") : ScriptedHttpClient.failingWith(fault));
        String config = "{\"routing_key\":\"R1\",\"url\":\"" + url + "\"}";

        assertEquals(expected, channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));
        assertEquals(interrupted, Thread.currentThread().isInterrupted());
    }

    static Stream<Arguments> failures() {
        DeliveryResult network = DeliveryResult.failure(null, "pagerduty delivery failed (network/guard)");
        return Stream.of(
                Arguments.of(URL, null, 400, DeliveryResult.failure(400, "pagerduty returned HTTP 400"), false),
                Arguments.of(URL, new IOException("reset"), 0, network, false),
                Arguments.of("http://10.0.0.5/v2/enqueue", null, 202, network, false),
                Arguments.of(URL, new InterruptedException(), 0, DeliveryResult.failure(null, "interrupted"), true));
    }
}
