// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** The {@code frustration_rate} shape. Explains the trial and why each witness is also a member. */
final class FrustrationDossier {

    /** Dispatched on from the payload. */
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
                "- frustrated sessions: %.2f%% learned → %.2f%% since onset (CUSUM %.2f past a %.2f decision"
                        + " interval)%n",
                root.path("baseline_rate").asDouble(0) * 100,
                root.path("current_rate").asDouble(0) * 100,
                root.path("statistic").asDouble(0),
                root.path("threshold").asDouble(0)));
        sb.append(String.format(
                Locale.ROOT,
                "- learned from %d session-hours, %d frustrated%n",
                root.path("baseline_conversations").asLong(0),
                root.path("baseline_frustrated").asLong(0)));
        sb.append(String.format(
                Locale.ROOT,
                "- since onset: %d session-hours, %d frustrated%n",
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
        sb.append("The trial is a session in one UTC hour on this finding's call site; it fails when a turn in that"
                + " hour was flagged and the flag stands. Each evidence row is a turn standing for one session-hour"
                + " and names its session: a `witness` row is the first flagged turn of a frustrated session-hour"
                + " and is also a `member`; a `member` row with no witness is the last scored turn of a calm one."
                + " There is no baseline side: the learned rate is a count on this finding, not an enumeration.\n");
        return sb.toString();
    }
}
