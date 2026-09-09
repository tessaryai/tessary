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
 * Slack channel delivered via an incoming webhook. Maps the stored config blob to a delivery
 * request; {@link SlackDelivery} makes the actual POST.
 *
 * <p>Config JSON shape: {@code {"url": "https://hooks.slack.com/services/…"}}.
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
