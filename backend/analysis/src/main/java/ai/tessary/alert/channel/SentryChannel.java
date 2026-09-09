// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertEventRow;
import ai.tessary.alert.AlertRuleRow;
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
 * Sentry connector: creates a Sentry issue by POSTing an event to a project's Store API
 * ({@code POST {storeUrl}} with header {@code X-Sentry-Auth}). The store URL and public key come from
 * the project's DSN; we take them as explicit config to avoid DSN-string parsing. A stable
 * {@code fingerprint} ({@link AlertPayload#dedupKey}) groups re-deliveries of the same alert into one
 * Sentry issue rather than spawning duplicates.
 *
 * <p>Config JSON shape: {@code {"store_url": "https://oXXX.ingest.sentry.io/api/<projectId>/store/",
 * "public_key": "<dsn public key>"}}. The store URL is re-validated against SSRF on every send by
 * {@link ChannelHttp}.
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
        // Sentry's Store API requires event_id to be a 32-char hex string. event.id() is a 26-char
        // Crockford-base32 ULID (no dashes, non-hex), which Sentry rejects/drops — so generate a fresh
        // 32-hex id per send (Sentry's documented format). Re-delivery grouping is handled by the stable
        // `fingerprint` (AlertPayload.dedupKey) below, not by event_id.
        body.put("event_id", java.util.UUID.randomUUID().toString().replace("-", ""));
        body.put("timestamp", event.occurredAt());
        body.put("platform", "other");
        body.put("logger", "tessary.alert");
        body.put("level", AlertRuleRow.RuleType.THRESHOLD.equals(event.ruleType()) ? "warning" : "info");
        body.put("message", AlertPayload.summary(event, mapper));
        ArrayNode fp = body.putArray("fingerprint");
        fp.add(AlertPayload.dedupKey(event));
        body.set("extra", AlertPayload.envelope(event, mapper));

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return DeliveryResult.failure(null, "could not serialize sentry event");
        }

        // Sentry classic auth header. The secret key is optional for event ingestion.
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
