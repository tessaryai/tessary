// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Triggers a PagerDuty incident via Events API v2. {@link AlertPayload#dedupKey} goes out as {@code dedup_key}, so a
 * re-delivery coalesces onto one incident. Config: {@code {"routing_key"}}.
 */
@Component
public class PagerDutyChannel implements AlertChannel {

    static final String ENQUEUE_URL = "https://events.pagerduty.com/v2/enqueue";

    private final ObjectMapper mapper;

    public PagerDutyChannel(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AlertChannelKind kind() {
        return AlertChannelKind.PAGERDUTY;
    }

    @Override
    public DeliveryResult deliver(AlertEventRow event, JsonNode config) {
        String routingKey = text(config, "routing_key");
        if (routingKey == null || routingKey.isBlank()) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, "pagerduty requires a 'routing_key'");
        }
        String url = textOr(config, "url", ENQUEUE_URL);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("summary", AlertPayload.summary(event, mapper));
        payload.put("source", "tessary/" + event.projectId());
        payload.put("severity", "info");
        payload.set("custom_details", AlertPayload.envelope(event, mapper));

        ObjectNode body = mapper.createObjectNode();
        body.put("routing_key", routingKey);
        body.put("event_action", "trigger");
        body.put("dedup_key", AlertPayload.dedupKey(event));
        body.set("payload", payload);

        String json = body.toString();
        try {
            HttpResponse<String> res = ChannelHttp.post(url, Map.of(), json);
            int code = res.statusCode();
            // 202 Accepted on success.
            return code / 100 == 2
                    ? DeliveryResult.success(code)
                    : DeliveryResult.failure(code, "pagerduty returned HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "interrupted");
        } catch (Exception e) {
            return DeliveryResult.failure(null, "pagerduty delivery failed (network/guard)");
        }
    }

    private static String text(JsonNode config, String field) {
        JsonNode n = config.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String textOr(JsonNode config, String field, String fallback) {
        String v = text(config, field);
        return v == null || v.isBlank() ? fallback : v;
    }
}
