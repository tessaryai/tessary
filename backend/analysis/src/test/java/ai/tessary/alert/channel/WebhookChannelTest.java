// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The generic webhook. The signed path is proven end to end in {@code AlertChannelDeliveryTest}; the bugs
 * here are a signature header sent for a blank secret (a receiver would reject every delivery), a
 * config with no URL reaching the send, a non-2xx recorded as delivered, and a signing failure that
 * returns an unsigned body instead of refusing.
 */
class WebhookChannelTest {

    private static final String URL = "https://203.0.113.50/hook";

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebhookChannel channel = new WebhookChannel(mapper);

    @AfterEach
    void resetSeam() {
        ChannelHttp.setClientForTest(null);
        Thread.interrupted();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"url\":null}", "{\"url\":\" \"}"})
    void aConfigWithoutAUrlIsRefusedBeforeAnySend(String config) {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, "");
        ChannelHttp.setClientForTest(client);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));

        assertEquals(AlertError.INVALID_CHANNEL_CONFIG, e.error());
        assertNull(client.lastRequest);
    }

    /** Without a secret the body goes out unsigned but still names its event kind and delivery id. */
    @ParameterizedTest(name = "secret {0}")
    @ValueSource(strings = {"", ",\"secret\":null", ",\"secret\":\"  \""})
    void anUnsignedDeliveryCarriesNoSignatureHeader(String secret) throws Exception {
        ScriptedHttpClient client = ScriptedHttpClient.answering(204, "");
        ChannelHttp.setClientForTest(client);
        AlertEventRow event = FiredEvents.caseOpened();

        DeliveryResult result =
                channel.deliver(event, FiredEvents.config(mapper, "{\"url\":\"" + URL + "\"" + secret + "}"));

        assertEquals(DeliveryResult.success(204), result);
        HttpHeaders headers = requireNonNull(client.lastRequest).headers();
        assertEquals(Optional.empty(), headers.firstValue(WebhookChannel.SIGNATURE_HEADER));
        assertEquals(Optional.of("case_opened"), headers.firstValue("X-Evals-Event"));
        assertEquals(Optional.of("evt_case"), headers.firstValue("X-Evals-Delivery"));
        assertEquals(AlertPayload.envelope(event, mapper), mapper.readTree(client.lastBody));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void anythingButA2xxIsACategoricalFailure(
            String url, @Nullable Exception fault, int status, DeliveryResult expected, boolean interrupted) {
        ChannelHttp.setClientForTest(
                fault == null ? ScriptedHttpClient.answering(status, "") : ScriptedHttpClient.failingWith(fault));

        assertEquals(
                expected,
                channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, "{\"url\":\"" + url + "\"}")));
        assertEquals(interrupted, Thread.currentThread().isInterrupted());
    }

    static Stream<Arguments> failures() {
        DeliveryResult network = DeliveryResult.failure(null, "webhook delivery failed (network/guard)");
        return Stream.of(
                Arguments.of(URL, null, 500, DeliveryResult.failure(500, "webhook returned HTTP 500"), false),
                Arguments.of(URL, new IOException("reset by 10.1.2.3"), 0, network, false),
                Arguments.of("http://192.168.1.10/hook", null, 200, network, false),
                Arguments.of(URL, new InterruptedException(), 0, DeliveryResult.failure(null, "interrupted"), true));
    }

    /** A key the MAC cannot be initialised with is a config error, never an unsigned or empty signature. */
    @Test
    void aSecretThatCannotSignIsAConfigError() {
        TessaryException e = assertThrows(TessaryException.class, () -> WebhookChannel.hmacSha256("", "{}"));

        assertEquals(AlertError.INVALID_CHANNEL_CONFIG, e.error());
    }
}
