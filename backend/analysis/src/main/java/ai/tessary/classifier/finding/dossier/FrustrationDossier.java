// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * The {@code frustration_rate} shape: a call site's share of frustrated conversations against the rate it
 * learned as its normal. Restates the rate in conversations and says what the two witness grains mean, since a
 * session row beside a trace row is not something the other shapes teach an agent to read.
 */
final class FrustrationDossier {

    /** The cause kind the finding writer records in the payload, which is what this shape dispatches on. */
    static final String CAUSE_KIND = "frustration_rate";

    private FrustrationDossier() {}

    static boolean matches(JsonNode root) {
        return CAUSE_KIND.equals(root.path("cause_kind").asText(""));
    }

    static String build(JsonNode root) {
        StringBuilder sb = new StringBuilder("# Frustration evidence\n\n");
        sb.append("- call site: `")
                .append(root.path("call_site_id").asText("?"))
                .append("`\n");
        sb.append(String.format(
                Locale.ROOT,
                "- frustrated conversations: %.2f%% learned → %.2f%% since onset (CUSUM %.2f past a %.2f decision"
                        + " interval)%n",
                root.path("baseline_rate").asDouble(0) * 100,
                root.path("current_rate").asDouble(0) * 100,
                root.path("statistic").asDouble(0),
                root.path("threshold").asDouble(0)));
        sb.append(String.format(
                Locale.ROOT,
                "- learned from %d conversations, %d frustrated%n",
                root.path("baseline_conversations").asLong(0),
                root.path("baseline_frustrated").asLong(0)));
        sb.append(String.format(
                Locale.ROOT,
                "- since onset: %d conversations, %d frustrated%n",
                root.path("conversations_since_onset").asLong(0),
                root.path("frustrated_since_onset").asLong(0)));
        if (root.hasNonNull("onset_at")) {
            sb.append("- onset: ").append(root.path("onset_at").asText()).append('\n');
        }
        if (root.hasNonNull("jev_threshold")) {
            sb.append(String.format(
                    Locale.ROOT,
                    "- a turn is flagged when its unhappy_with_assistant score exceeds %.2f%n",
                    root.path("jev_threshold").asDouble(0)));
        }

        sb.append("\n## How to read the evidence\n\n");
        sb.append("A conversation is one trial, counted on the call site of its first scored turn, and it is a"
                + " failure while it holds an uncleared frustration flag. Each `witness` session row is a"
                + " frustrated conversation; the `witness` trace row after it is the user turn that fired inside"
                + " it, which may sit on another call site. There is no baseline side: the learned rate is a count"
                + " on this finding, not an enumeration.\n");
        return sb.toString();
    }
}
