// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import ai.tessary.evals.alert.AlertChannelKind;
import ai.tessary.evals.alert.AlertEventRow;
import ai.tessary.evals.alert.AlertRuleRow;
import ai.tessary.evals.open.errors.AlertError;
import ai.tessary.evals.open.errors.EvalsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PagerDuty connector via the Events API v2 ({@code POST
 * https://events.pagerduty.com/v2/enqueue}). Triggers an incident with the configured
 * {@code routing_key}. The fired alert's stable {@link AlertPayload#dedupKey} is sent as PagerDuty's
 * {@code dedup_key} so a re-delivered event coalesces onto the same incident instead of opening a new
 * one — PagerDuty's own dedup mechanism layered on top of our at-most-once delivery-attempt guard.
 *
 * <p>Config JSON shape: {@code {"routing_key": "…"}}. The endpoint is a fixed public PagerDuty host
 * (still SSRF-guarded on every send via {@link ChannelHttp}, which it passes).
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
            throw new EvalsException(AlertError.INVALID_CHANNEL_CONFIG, "pagerduty requires a 'routing_key'");
        }
        String url = textOr(config, "url", ENQUEUE_URL);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("summary", AlertPayload.summary(event, mapper));
        payload.put("source", "tessary/" + event.projectId());
        payload.put("severity", AlertRuleRow.RuleType.THRESHOLD.equals(event.ruleType()) ? "warning" : "info");
        payload.set("custom_details", AlertPayload.envelope(event, mapper));

        ObjectNode body = mapper.createObjectNode();
        body.put("routing_key", routingKey);
        body.put("event_action", "trigger");
        body.put("dedup_key", AlertPayload.dedupKey(event));
        body.set("payload", payload);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return DeliveryResult.failure(null, "could not serialize pagerduty event");
        }
        try {
            HttpResponse<String> res = ChannelHttp.post(url, Map.of(), json);
            int code = res.statusCode();
            // PagerDuty returns 202 Accepted on a successful enqueue.
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
