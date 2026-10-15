// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.config.SlackProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The backend's one route to Slack, via the adapter's {@code POST /deliver}; no Slack token or URL lives here. Never
 * throws for a delivery outcome, so one channel's outage cannot abort the fan-out.
 */
@Component
public class SlackDelivery {

    private static final Logger log = LoggerFactory.getLogger(SlackDelivery.class);

    /** No token; the URL is the credential. */
    public static final String TRANSPORT_WEBHOOK = "webhook";

    private final SlackProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SlackDelivery(SlackProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // HTTP/1.1: uvicorn drops an h2c upgrade's body and answers 400, which reads as a payload error.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public DeliveryResult sendToWebhook(String url, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("transport", TRANSPORT_WEBHOOK);
        body.put("url", url);
        body.put("text", text);
        return send(body);
    }

    private DeliveryResult send(ObjectNode body) {
        if (!props.isConfigured()) {
            return DeliveryResult.failure(null, "slack-service is not configured");
        }
        String payload = body.toString();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/deliver"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .header("Authorization", "Bearer " + props.serviceKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("slack-service returned HTTP {}", res.statusCode());
                return DeliveryResult.failure(res.statusCode(), "slack-service HTTP " + res.statusCode());
            }
            return outcome(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "interrupted");
        } catch (Exception e) {
            // No throwable: the HTTP stack's message can echo the URL, which for a webhook is the credential.
            log.warn("slack-service delivery failed (network)");
            return DeliveryResult.failure(null, "slack-service unreachable");
        }
    }

    /** 200 with {@code ok:false}. */
    private DeliveryResult outcome(String body) {
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed.path("ok").asBoolean(false)) {
                JsonNode status = parsed.get("status");
                return DeliveryResult.success(status == null || status.isNull() ? 200 : status.asInt());
            }
            String error = parsed.path("error").asText("slack refused the message");
            JsonNode status = parsed.get("status");
            return DeliveryResult.failure(status == null || status.isNull() ? null : status.asInt(), error);
        } catch (Exception e) {
            return DeliveryResult.failure(null, "slack-service returned an unreadable body");
        }
    }
}
