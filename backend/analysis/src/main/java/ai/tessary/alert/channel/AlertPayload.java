// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.alert.AlertRuleRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Renders a fired {@link AlertEventRow} into the shapes channels need: a stable, versioned
 * machine-readable JSON envelope (the generic-webhook contract), a one-line summary (connector titles),
 * and — for a case-opened firing — the several-line message a person is meant to triage from without
 * opening the product. Centralised so the webhook contract and the human text stay consistent across all
 * channels.
 *
 * <p>The envelope inlines {@code payloadJson} as a first-class object (a roll-up's per-classifier counts)
 * rather than a double-encoded JSON-in-a-string, so external receivers parse once.
 */
public final class AlertPayload {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AlertPayload.class);

    /** Bump on any breaking change to the webhook envelope. External receivers key off this. */
    public static final String SCHEMA_VERSION = "1";

    private AlertPayload() {}

    /** The stable webhook envelope. */
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
        putNullable(root, "classifier_id", e.classifierId());
        putNullable(root, "case_id", e.caseId());
        putNullable(root, "basis", e.basis());
        if (e.value() != null) root.put("observed_value", e.value());
        if (e.threshold() != null) root.put("threshold", e.threshold());
        // Inline the roll-up body as a first-class object, not a JSON-in-a-string.
        String payloadJson = e.payloadJson();
        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                JsonNode parsed = mapper.readTree(e.payloadJson());
                root.set("payload", parsed);
            } catch (Exception parseFailure) {
                // payloadJson is platform-produced and bounded, so this should not happen — a hit
                // points to a producer bug. Fall back to embedding the raw string (no credentials: it is
                // the alert roll-up body) and debug-log categorically (local-only) to aid diagnosis.
                log.debug("alert payload not valid JSON, embedding raw eventId={}", e.id());
                root.put("payload", e.payloadJson());
            }
        }
        return root;
    }

    /** Stable dedup key for connectors that support one (PagerDuty dedup_key, Sentry fingerprint). */
    public static String dedupKey(AlertEventRow e) {
        // Stays on the old spelling through the tessary rename: this is the PagerDuty dedup_key /
        // Sentry fingerprint, so changing it makes a re-delivered pre-upgrade event open a second
        // incident. The token is opaque and never displayed, so the old name costs nothing.
        return "evals-alert-" + e.id();
    }

    /**
     * <b>The whole message, for a case-opened firing</b> — several lines of Slack mrkdwn rather than one
     * summary line, because launch requirement I2 asks for a message someone can act on <em>without
     * opening the product</em>, and a title cannot carry that.
     *
     * <p>What it carries and why each earns its line: the title (what moved, and by how much), the
     * detector's own {@code basis} sentence (why it crossed <em>that</em> detector's bar, which is the one
     * thing that makes cases from different detectors comparable to a reader), who ruled it real and on
     * what authority (a repo-grounded ruling and a trace-only one are different claims — segment B7), and
     * a link for when they do want the page.
     *
     * <p>Returns null for every other rule type, so {@link #summary} stays their one renderer.
     */
    public static @Nullable String caseMessage(AlertEventRow e, ObjectMapper mapper) {
        if (!AlertRuleRow.RuleType.CASE_OPENED.equals(e.ruleType())) return null;
        JsonNode p = parsePayload(e, mapper);
        if (p == null) return null;

        StringBuilder out = new StringBuilder();
        out.append('*')
                .append(text(p, "case_reference", "A case"))
                .append("* · ")
                .append(text(p, "title", "a case opened"));

        String callSite = text(p, "call_site_id", null);
        String detector = text(p, "detector", null);
        if (detector != null || callSite != null) {
            out.append("\n_").append(detector == null ? "" : detector.replace('_', ' '));
            if (callSite != null)
                out.append(detector == null ? "" : " · ")
                        .append('`')
                        .append(callSite)
                        .append('`');
            out.append('_');
        }

        String basis = text(p, "basis", null);
        if (basis != null) out.append('\n').append(basis);

        String ruledBy = text(p, "ruled_by", null);
        if (ruledBy != null) {
            out.append("\nConfirmed by ").append(ruledBy);
            JsonNode confidence = p.get("confidence");
            if (confidence != null && confidence.isNumber()) {
                out.append(String.format(Locale.ROOT, " (confidence %.2f)", confidence.asDouble()));
            }
            out.append('.');
        }
        String ruling = text(p, "ruling_summary", null);
        if (ruling != null) out.append(' ').append(ruling);

        String url = text(p, "url", null);
        if (url != null) out.append('\n').append(url);
        return out.toString();
    }

    private static @Nullable JsonNode parsePayload(AlertEventRow e, ObjectMapper mapper) {
        String json = e.payloadJson();
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception parseFailure) {
            log.debug("case alert payload not valid JSON eventId={}", e.id());
            return null;
        }
    }

    private static @Nullable String text(JsonNode node, String field, @Nullable String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        String s = value.asText("");
        return s.isBlank() ? fallback : s;
    }

    /**
     * A short human-readable summary line, suitable for a connector title or a one-line channel.
     *
     * <p>A case-opened firing has a fuller form in {@link #caseMessage}; this is its title, which is what
     * PagerDuty, Sentry and Linear want. A channel with room for the whole thing should prefer
     * {@code caseMessage}.
     */
    public static String summary(AlertEventRow e, ObjectMapper mapper) {
        if (AlertRuleRow.RuleType.CASE_OPENED.equals(e.ruleType())) {
            JsonNode p = parsePayload(e, mapper);
            String reference = p == null ? null : text(p, "case_reference", null);
            String title = p == null ? null : text(p, "title", null);
            if (reference != null && title != null) return reference + " · " + title;
            if (title != null) return title;
            return "A case opened";
        }
        return switch (e.ruleType()) {
            case AlertRuleRow.RuleType.THRESHOLD ->
                String.format(
                        Locale.ROOT,
                        "Alert threshold breached: %s = %s (threshold %s) in %s … %s",
                        e.basis() == null ? "count" : e.basis(),
                        e.value() == null ? "?" : e.value(),
                        e.threshold() == null ? "?" : e.threshold(),
                        e.windowStart(),
                        e.windowEnd());
            case AlertRuleRow.RuleType.DIGEST ->
                String.format(
                        Locale.ROOT,
                        "Daily digest: %s events in %s … %s",
                        e.value() == null ? "?" : e.value(),
                        e.windowStart(),
                        e.windowEnd());
            case AlertRuleRow.RuleType.BRIEF ->
                String.format(
                        Locale.ROOT,
                        "Scheduled brief: %s events in %s … %s",
                        e.value() == null ? "?" : e.value(),
                        e.windowStart(),
                        e.windowEnd());
            default -> String.format(Locale.ROOT, "Alert fired (%s) for project %s", e.ruleType(), e.projectId());
        };
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }
}
