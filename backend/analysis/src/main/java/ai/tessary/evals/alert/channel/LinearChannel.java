// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import ai.tessary.evals.alert.AlertChannelKind;
import ai.tessary.evals.alert.AlertEventRow;
import ai.tessary.evals.open.errors.AlertError;
import ai.tessary.evals.open.errors.EvalsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Linear connector: creates a Linear issue via the GraphQL API ({@code POST
 * https://api.linear.app/graphql}) using an {@code issueCreate} mutation, authenticated with a personal
 * API key in the {@code Authorization} header. The configured {@code team_id} scopes the new issue.
 *
 * <p>Linear has no server-side dedup key for issues, so duplicate-suppression relies solely on the
 * delivery-attempt log's at-most-once guard ({@code (alert_event_id, channel_id)}); a second issue
 * for the same fired alert can only happen if that record is lost. Acceptable at pre-prod scale.
 *
 * <p>Config JSON shape: {@code {"api_key": "lin_api_…", "team_id": "<uuid>"}}.
 */
@Component
public class LinearChannel implements AlertChannel {

    static final String GRAPHQL_URL = "https://api.linear.app/graphql";

    private static final String MUTATION =
            "mutation IssueCreate($title: String!, $description: String!, $teamId: String!) {"
                    + " issueCreate(input: {title: $title, description: $description, teamId: $teamId})"
                    + " { success issue { id identifier } } }";

    private final ObjectMapper mapper;

    public LinearChannel(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AlertChannelKind kind() {
        return AlertChannelKind.LINEAR;
    }

    @Override
    public DeliveryResult deliver(AlertEventRow event, JsonNode config) {
        String apiKey = text(config, "api_key");
        String teamId = text(config, "team_id");
        if (apiKey == null || apiKey.isBlank() || teamId == null || teamId.isBlank()) {
            throw new EvalsException(AlertError.INVALID_CHANNEL_CONFIG, "linear requires 'api_key' and 'team_id'");
        }
        String url = textOr(config, "url", GRAPHQL_URL);

        ObjectNode variables = mapper.createObjectNode();
        variables.put("title", AlertPayload.summary(event, mapper));
        variables.put("description", description(event));
        variables.put("teamId", teamId);

        ObjectNode body = mapper.createObjectNode();
        body.put("query", MUTATION);
        body.set("variables", variables);

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return DeliveryResult.failure(null, "could not serialize linear mutation");
        }
        try {
            HttpResponse<String> res = ChannelHttp.post(url, Map.of("Authorization", apiKey), json);
            int code = res.statusCode();
            if (code / 100 != 2) {
                return DeliveryResult.failure(code, "linear returned HTTP " + code);
            }
            // GraphQL returns 200 even for mutation errors — inspect the body shape.
            if (!createdSuccessfully(res.body())) {
                return DeliveryResult.failure(code, "linear issueCreate did not succeed");
            }
            return DeliveryResult.success(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "interrupted");
        } catch (Exception e) {
            return DeliveryResult.failure(null, "linear delivery failed (network/guard)");
        }
    }

    private boolean createdSuccessfully(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (root.has("errors")) return false;
            JsonNode success = root.path("data").path("issueCreate").path("success");
            return success.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String description(AlertEventRow e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fired alert from tessary.\n\n");
        sb.append("- **kind**: ").append(e.ruleType()).append('\n');
        sb.append("- **project**: ").append(e.projectId()).append('\n');
        if (e.classifierId() != null)
            sb.append("- **classifier**: ").append(e.classifierId()).append('\n');
        if (e.value() != null) sb.append("- **observed**: ").append(e.value()).append('\n');
        if (e.threshold() != null)
            sb.append("- **threshold**: ").append(e.threshold()).append('\n');
        sb.append("- **window**: ")
                .append(e.windowStart())
                .append(" … ")
                .append(e.windowEnd())
                .append('\n');
        sb.append("- **fired_at**: ").append(e.occurredAt()).append('\n');
        return sb.toString();
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
