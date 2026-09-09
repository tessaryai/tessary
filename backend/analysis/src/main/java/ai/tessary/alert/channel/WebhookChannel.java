// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Generic outbound-webhook channel: a signed POST of the stable {@link AlertPayload}
 * envelope to a user-configured URL. When a {@code secret} is configured, the body is signed with
 * HMAC-SHA256 and the digest sent in {@code X-Tessary-Signature-256: sha256=<hex>} (mirroring the
 * inbound {@code x-hub-signature-256} format GitHub webhooks use), so the receiver can verify
 * authenticity. The URL is re-validated against SSRF on every send by {@link ChannelHttp}.
 *
 * <p>Config JSON shape: {@code {"url": "https://…", "secret": "…"?}}.
 */
@Component
public class WebhookChannel implements AlertChannel {

    public static final String SIGNATURE_HEADER = "X-Tessary-Signature-256";

    private final ObjectMapper mapper;

    public WebhookChannel(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AlertChannelKind kind() {
        return AlertChannelKind.WEBHOOK;
    }

    @Override
    public DeliveryResult deliver(AlertEventRow event, JsonNode config) {
        String url = requireUrl(config);
        String body;
        try {
            body = mapper.writeValueAsString(AlertPayload.envelope(event, mapper));
        } catch (Exception e) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, e, "could not serialize webhook payload");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Tessary-Event", event.ruleType());
        headers.put("X-Tessary-Delivery", event.id());
        String secret = text(config, "secret");
        if (secret != null && !secret.isBlank()) {
            headers.put(SIGNATURE_HEADER, "sha256=" + hmacSha256(secret, body));
        }
        try {
            HttpResponse<String> res = ChannelHttp.post(url, headers, body);
            int code = res.statusCode();
            return code / 100 == 2
                    ? DeliveryResult.success(code)
                    : DeliveryResult.failure(code, "webhook returned HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "interrupted");
        } catch (Exception e) {
            // Never echo the exception message — it can carry the resolved internal IP/host.
            return DeliveryResult.failure(null, "webhook delivery failed (network/guard)");
        }
    }

    static String requireUrl(JsonNode config) {
        String url = text(config, "url");
        if (url == null || url.isBlank()) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, "webhook requires a 'url'");
        }
        return url;
    }

    public static String hmacSha256(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, e, "could not sign webhook payload");
        }
    }

    static String text(JsonNode config, String field) {
        JsonNode n = config.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
