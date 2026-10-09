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
 * The backend's one route to Slack: {@code POST slack-service/deliver}.
 *
 * <p>Its one caller is the {@link SlackChannel} alert channel, which posts through a user's incoming
 * webhook.
 *
 * <p><b>The backend cannot reach Slack directly and that is deliberate.</b> No token, no signing secret,
 * no {@code slack.com} URL lives in this process any more; the adapter holds all of it. What this sends
 * is a composed message and a destination the platform already decided on.
 *
 * <p>Never throws for a delivery outcome. The adapter answers 200 with {@code ok:false} when Slack
 * refuses, and a transport fault here is returned as a failure too — the alert fan-out records outcomes
 * per channel and must not have one channel's outage abort the others.
 */
@Component
public class SlackDelivery {

    private static final Logger log = LoggerFactory.getLogger(SlackDelivery.class);

    /** A user-configured {@code hooks.slack.com} URL. No token; the URL is the credential. */
    public static final String TRANSPORT_WEBHOOK = "webhook";

    private final SlackProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SlackDelivery(SlackProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // HTTP/1.1 on purpose: `slack-service` is uvicorn, which speaks HTTP/1.1 only, and the JDK
        // client's HTTP_2 default attempts an h2c upgrade against a plaintext origin. uvicorn drops
        // the body and answers 400, so a POST fails blaming its payload rather than the protocol —
        // exactly what it did to `compile-service` before that client was pinned. This path is
        // unexercised today (Slack is off at launch), so the same bug was sitting here unseen.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Send through a user's incoming webhook. */
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
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/deliver"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .header("Authorization", "Bearer " + props.getServiceKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
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
            // Categorical, no throwable: the request body carries the message text, and an exception
            // message from the HTTP stack can echo the URL, which for a webhook IS the credential.
            log.warn("slack-service delivery failed (network)");
            return DeliveryResult.failure(null, "slack-service unreachable");
        }
    }

    /** The adapter reports a Slack-side refusal in the body, not the status — 200 with {@code ok:false}. */
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
