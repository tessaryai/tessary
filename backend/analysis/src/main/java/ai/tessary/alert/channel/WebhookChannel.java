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
 * Signed POST of the {@link AlertPayload} envelope. With a {@code secret}, the body's HMAC-SHA256 goes in
 * {@code X-Evals-Signature-256: sha256=<hex>}, GitHub's format. Config: {@code {"url", "secret"?}}.
 */
@Component
public class WebhookChannel implements AlertChannel {

    // The X-Evals-* names are an outbound wire contract: receivers verify the HMAC by header name, so renaming needs
    // its own release with a transition window.
    public static final String SIGNATURE_HEADER = "X-Evals-Signature-256";

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
        String body = AlertPayload.envelope(event, mapper).toString();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Evals-Event", event.ruleType());
        headers.put("X-Evals-Delivery", event.id());
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
            // Never echo the exception message; it can carry the resolved internal IP.
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
