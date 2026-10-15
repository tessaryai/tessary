// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Creates a Sentry issue via the Store API. Config is {@code {"store_url", "public_key"}} rather than a DSN, to avoid
 * parsing it. A stable {@code fingerprint} ({@link AlertPayload#dedupKey}) groups re-deliveries into one issue.
 */
@Component
public class SentryChannel implements AlertChannel {

    private final ObjectMapper mapper;

    public SentryChannel(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AlertChannelKind kind() {
        return AlertChannelKind.SENTRY;
    }

    @Override
    public DeliveryResult deliver(AlertEventRow event, JsonNode config) {
        String storeUrl = text(config, "store_url");
        String publicKey = text(config, "public_key");
        if (storeUrl == null || storeUrl.isBlank() || publicKey == null || publicKey.isBlank()) {
            throw new TessaryException(
                    AlertError.INVALID_CHANNEL_CONFIG, "sentry requires 'store_url' and 'public_key'");
        }

        ObjectNode body = mapper.createObjectNode();
        // Sentry requires a 32-hex event_id and drops a ULID, so a fresh one is generated per send; grouping comes from
        // the fingerprint.
        body.put("event_id", java.util.UUID.randomUUID().toString().replace("-", ""));
        body.put("timestamp", event.occurredAt());
        body.put("platform", "other");
        body.put("logger", "tessary.alert");
        body.put("level", "info");
        body.put("message", AlertPayload.summary(event, mapper));
        ArrayNode fp = body.putArray("fingerprint");
        fp.add(AlertPayload.dedupKey(event));
        body.set("extra", AlertPayload.envelope(event, mapper));

        String json = body.toString();

        // The secret key is optional for event ingestion.
        String auth = "Sentry sentry_version=7, sentry_client=tessary/1.0, sentry_key=" + publicKey;
        try {
            HttpResponse<String> res = ChannelHttp.post(storeUrl, Map.of("X-Sentry-Auth", auth), json);
            int code = res.statusCode();
            return code / 100 == 2
                    ? DeliveryResult.success(code)
                    : DeliveryResult.failure(code, "sentry returned HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "interrupted");
        } catch (Exception e) {
            return DeliveryResult.failure(null, "sentry delivery failed (network/guard)");
        }
    }

    private static String text(JsonNode config, String field) {
        JsonNode n = config.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
