// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.config.SlackProperties;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Slack alert channel. The bugs: a case posted as its one-line title when Slack has room for the
 * whole triage message, a roll-up posted as nothing, and a config with no webhook reaching the adapter.
 */
class SlackChannelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingDelivery delivery = new RecordingDelivery(mapper);
    private final SlackChannel channel = new SlackChannel(mapper, delivery);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"url\":null}", "{\"url\":\"\"}"})
    void aConfigWithoutAWebhookIsRefusedBeforeAnySend(String config) {
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));

        assertEquals(AlertError.INVALID_CHANNEL_CONFIG, e.error());
        assertNull(delivery.text);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("messages")
    void aCasePostsItsWholeMessageAndARollupItsSummary(AlertEventRow event, String text) {
        DeliveryResult result =
                channel.deliver(event, FiredEvents.config(mapper, "{\"url\":\"https://hooks.slack.com/services/x\"}"));

        assertEquals(DeliveryResult.success(200), result);
        assertEquals("https://hooks.slack.com/services/x", delivery.url);
        assertEquals(text, delivery.text);
    }

    static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of(
                        FiredEvents.caseOpened(),
                        "*C-3* · Latency up 2x\n_duration drift_\np95 above band\nConfirmed by a person."),
                Arguments.of(
                        FiredEvents.digest(7),
                        "Daily digest: 7 events in 2026-01-15T09:00:00Z … 2026-01-15T10:00:00Z"));
    }

    /** Stands in for the adapter hop, which {@link SlackDeliveryTest} covers against a real server. */
    private static final class RecordingDelivery extends SlackDelivery {
        @Nullable
        String url;

        @Nullable
        String text;

        RecordingDelivery(ObjectMapper mapper) {
            super(new SlackProperties(), mapper);
        }

        @Override
        public DeliveryResult sendToWebhook(String url, String text) {
            this.url = url;
            this.text = text;
            return DeliveryResult.success(200);
        }
    }
}
