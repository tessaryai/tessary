// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Slack channel via a Slack <em>incoming webhook</em>. The channel row still lives here — it is one of a
 * project's alert destinations like any other — but the POST itself is made by {@code tessary-paid/slack-service/},
 * reached through {@link SlackDelivery}.
 *
 * <p><b>Why the send moved out.</b> Everything Slack-specific about this transport (the payload shape,
 * the SDK, the error vocabulary) is protocol, and protocol is the adapter's job. What stays is the
 * platform's: which project holds this destination, whether its organization may use Slack at all
 * ({@code Capability.SLACK}, off at launch and enforced in {@link AlertDeliveryDispatcher}), and what the
 * message says. That leaves this class a thin mapping from a stored config blob to a delivery request,
 * which is all a channel should be.
 *
 * <p>Config JSON shape is unchanged: {@code {"url": "https://hooks.slack.com/services/…"}}. SSRF
 * re-validation moved with the send, to the point where the request is actually made.
 */
@Component
public class SlackChannel implements AlertChannel {

    private final ObjectMapper mapper;
    private final SlackDelivery delivery;

    public SlackChannel(ObjectMapper mapper, SlackDelivery delivery) {
        this.mapper = mapper;
        this.delivery = delivery;
    }

    @Override
    public AlertChannelKind kind() {
        return AlertChannelKind.SLACK;
    }

    @Override
    public DeliveryResult deliver(AlertEventRow event, JsonNode config) {
        String url = text(config, "url");
        if (url == null || url.isBlank()) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, "slack requires an incoming-webhook 'url'");
        }
        String caseMessage = AlertPayload.caseMessage(event, mapper);
        return delivery.sendToWebhook(url, caseMessage != null ? caseMessage : AlertPayload.summary(event, mapper));
    }

    private static String text(JsonNode config, String field) {
        JsonNode n = config.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
