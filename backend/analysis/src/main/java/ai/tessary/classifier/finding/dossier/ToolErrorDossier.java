// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * The {@code tool_error} shape — {@code ToolErrorEvidence.toJson}'s blob already groups
 * failure instances by error SIGNATURE with a per-signature count (the {@code patterns[]} array), so
 * this is a faithful re-rendering of that structure into prose, not a fresh aggregation. What it adds
 * over shipping the raw JSON: population totals stated in words, and an explicit, honest statement of
 * what {@code failing_traces} actually is — a flat, NOT per-signature, sample — rather than leaving an
 * agent to assume a mapping the data does not carry.
 */
final class ToolErrorDossier {

    private ToolErrorDossier() {}

    static String build(JsonNode root) {
        StringBuilder sb = new StringBuilder("# Tool error evidence\n\n");
        JsonNode bucket = root.path("bucket");
        sb.append("- tool: `").append(bucket.path("key").asText("?")).append("`\n");
        sb.append("- direction: ").append(root.path("direction").asText("?")).append('\n');
        sb.append(String.format(
                Locale.ROOT,
                "- rate: %.2f%% → %.2f%% (CUSUM %.2f past a %.2f decision interval)%n",
                root.path("rate").path("ref").asDouble(0) * 100,
                root.path("rate").path("cur").asDouble(0) * 100,
                root.path("statistic").asDouble(0),
                root.path("threshold").asDouble(0)));
        sb.append("- population since onset: n=")
                .append(root.path("n_cur").asLong(0))
                .append(", failures=")
                .append(root.path("failures").path("cur").asLong(0))
                .append('\n');
        if (root.hasNonNull("onset_at")) {
            sb.append("- onset: ").append(root.path("onset_at").asText()).append('\n');
        }

        JsonNode patterns = root.path("patterns");
        sb.append("\n## Failure patterns, grouped by error signature\n\n");
        if (patterns.isArray() && !patterns.isEmpty()) {
            sb.append("Every pattern the detector tracked for this tool, ranked in the detector's own order"
                    + " (patterns.json's own array order — not re-sorted here). `cur` is the count"
                    + " since onset; `ref` is the same signature's count in the prior window, so a"
                    + " signature with ref=0 is new.\n\n");
            for (JsonNode p : patterns) {
                sb.append("- `")
                        .append(p.path("signature").asText("?"))
                        .append("` (")
                        .append(p.path("source").asText("?"))
                        .append("): ")
                        .append(p.path("cur").asLong(0))
                        .append(" now, ")
                        .append(p.path("ref").asLong(0))
                        .append(" before\n");
            }
            if (root.path("patterns_truncated").asBoolean(false)) {
                sb.append("\n(patterns_truncated=true — the detector's own pattern list was cut; the counts"
                        + " above cover only the patterns it kept, not the tool's whole failure"
                        + " population. `failures.cur` above is still the true total.)\n");
            }
        } else {
            sb.append("No per-signature breakdown in this payload — only the aggregate rate above. State"
                    + " that plainly rather than inventing signatures; get_finding_evidence still"
                    + " pages the raw failing calls.\n");
        }

        JsonNode traces = root.path("failing_traces");
        if (traces.isArray() && !traces.isEmpty()) {
            sb.append("\n## Sample failing traces (declared: FLAT sample, not mapped to a signature)\n\n");
            sb.append("The detector recorded ")
                    .append(traces.size())
                    .append(" trace id(s) below as failing calls it happened to keep, pooled across every"
                            + " signature above — there is no per-signature mapping in this payload, so do"
                            + " not assume trace N belongs to pattern N. Treat this as a starting point, not"
                            + " the population: use get_finding_evidence for the enumerated evidence table"
                            + " below.\n\n");
            for (JsonNode t : traces) {
                sb.append("- `").append(t.asText()).append("`\n");
            }
        }

        return sb.toString();
    }
}
