// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/**
 * D (#994): the generic "window finding" shape — any classifier whose payload publishes a {@code
 * window} block but matches neither {@code tool_error}'s {@code patterns[]} shape nor {@code
 * metric_drift}'s {@code bucket}+{@code ratio} shape (today: {@code tool_error} and {@code
 * metric_drift} themselves already have dedicated, more specific assemblers ahead of this one in
 * {@code ClassifierDossierAssembler}'s dispatch order — this is the fallback for a THIRD kind of
 * classifier that compares a before-window to an after-window without either of those two families'
 * particular internal structure).
 *
 * <p>Pairs before/after values over the FULL window rather than a truncated sample of it: every
 * top-level field shaped like {@code {"ref": x, "cur": y}} (the convention both known families use for
 * a scalar comparison) is rendered as one paired row, and every 2-element array under a key not already
 * consumed is read as a {@code [then, now]} pair on the same convention {@code MetricFindingEvidence}
 * uses for its quantiles. Nothing here samples: whatever pairs the payload states, all of them appear.
 */
final class WindowFindingDossier {

    private WindowFindingDossier() {}

    /** Keys already covered by name — not re-rendered as generic pairs. */
    private static final Map<String, Boolean> SKIP = Map.of("window", true, "measure", true, "bucket", true);

    static String build(JsonNode root) {
        StringBuilder sb = new StringBuilder("# Window finding evidence\n\n");
        JsonNode window = root.path("window");
        sb.append("- window: ")
                .append(window.path("opened_at").asText("?"))
                .append(" → ")
                .append(window.path("closed_at").asText("?"));
        if (window.hasNonNull("kind"))
            sb.append(" (").append(window.path("kind").asText()).append(')');
        sb.append('\n');
        if (root.hasNonNull("direction"))
            sb.append("- direction: ").append(root.path("direction").asText()).append('\n');

        sb.append("\n## Before / after, over the full window\n\n");
        boolean any = false;
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (SKIP.containsKey(e.getKey())) continue;
            JsonNode v = e.getValue();
            if (v.has("ref") && v.has("cur")) {
                sb.append("- ")
                        .append(e.getKey())
                        .append(": ")
                        .append(text(v.get("ref")))
                        .append(" → ")
                        .append(text(v.get("cur")))
                        .append('\n');
                any = true;
            } else if (v.isArray()
                    && v.size() == 2
                    && (v.get(0).isValueNode() || v.get(0).isNull())) {
                sb.append("- ")
                        .append(e.getKey())
                        .append(": ")
                        .append(text(v.get(0)))
                        .append(" → ")
                        .append(text(v.get(1)))
                        .append('\n');
                any = true;
            }
        }
        if (!any) {
            sb.append("No paired before/after fields found beyond the window bounds above — this"
                            + " classifier's payload states the window but not a ref/cur pair at the top level;"
                            + " the raw payload is included verbatim below rather than a fabricated comparison.\n\n")
                    .append("```json\n")
                    .append(root)
                    .append("\n```\n");
        }
        return sb.toString();
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? "—" : n.asText();
    }
}
