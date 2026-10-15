// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.alert.AlertRuleRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Renders a fired alert for channels: the versioned webhook envelope, a one-line summary, and the case-opened message.
 * The envelope inlines {@code payloadJson} as an object so receivers parse once.
 */
public final class AlertPayload {

    /** External receivers key off this; bump on any breaking change. */
    public static final String SCHEMA_VERSION = "1";

    private AlertPayload() {}

    public static ObjectNode envelope(AlertEventRow e, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("event_id", e.id());
        root.put("project_id", e.projectId());
        root.put("alert_id", e.alertRuleId());
        root.put("kind", e.ruleType());
        root.put("fired_at", e.occurredAt());
        root.put("window_start", e.windowStart());
        root.put("window_end", e.windowEnd());
        putNullable(root, "case_id", e.caseId());
        if (e.value() != null) root.put("observed_value", e.value());
        String payloadJson = e.payloadJson();
        if (payloadJson != null && !payloadJson.isBlank()) {
            root.set("payload", parse(payloadJson, mapper));
        }
        return root;
    }

    /** PagerDuty dedup_key, Sentry fingerprint. */
    public static String dedupKey(AlertEventRow e) {
        // Keeps the old spelling: changing it would open a second incident for a re-delivered pre-upgrade event.
        return "evals-alert-" + e.id();
    }

    /**
     * Several lines of Slack mrkdwn, so a person can triage without opening the product: title, the detector's
     * {@code basis}, who ruled it real, and a link. Null for every other rule type.
     */
    public static @Nullable String caseMessage(AlertEventRow e, ObjectMapper mapper) {
        if (!AlertRuleRow.RuleType.CASE_OPENED.equals(e.ruleType())) return null;
        JsonNode p = parse(e.payloadJson(), mapper);

        StringBuilder out = new StringBuilder();
        out.append('*')
                .append(text(p, "case_reference", "A case"))
                .append("* · ")
                .append(text(p, "title", "a case opened"));

        String callSite = text(p, "call_site_id", null);
        String detector = Objects.requireNonNullElse(text(p, "detector", null), "");
        out.append("\n_").append(detector.replace('_', ' '));
        if (callSite != null) out.append(" · ").append('`').append(callSite).append('`');
        out.append('_');

        out.append('\n').append(text(p, "basis", null));
        out.append("\nConfirmed by ").append(text(p, "ruled_by", null)).append('.');
        String ruling = text(p, "ruling_summary", null);
        if (ruling != null) out.append(' ').append(ruling);

        String url = text(p, "url", null);
        if (url != null) out.append('\n').append(url);
        return out.toString();
    }

    private static JsonNode parse(@Nullable String json, ObjectMapper mapper) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static @Nullable String text(JsonNode node, String field, @Nullable String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        String s = value.asText("");
        return s.isBlank() ? fallback : s;
    }

    /** For a case-opened firing this is the title; channels with room should prefer {@link #caseMessage}. */
    public static String summary(AlertEventRow e, ObjectMapper mapper) {
        if (AlertRuleRow.RuleType.CASE_OPENED.equals(e.ruleType())) {
            JsonNode p = parse(e.payloadJson(), mapper);
            String title = text(p, "title", null);
            if (title == null) return "A case opened";
            return text(p, "case_reference", null) + " · " + title;
        }
        String label = AlertRuleRow.RuleType.DIGEST.equals(e.ruleType()) ? "Daily digest" : "Scheduled brief";
        return String.format(Locale.ROOT, "%s: %s events in %s … %s", label, e.value(), e.windowStart(), e.windowEnd());
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }
}
