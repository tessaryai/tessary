// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * D (#994): the {@code metric_drift} shape — {@code MetricFindingEvidence.toJson}'s blob.
 *
 * <p><b>The contribution/concentration table is the {@code explains[]} array, ranked by
 * {@code covered}.</b> That field already answers "how much of the parent bucket's shift does this
 * sibling bucket's own shift account for" (a fraction in [0,1]) — it is a real, detector-computed
 * concentration measure, not a fresh SQL aggregation done here; this assembler's job is to rank and
 * render it, not to invent it. A bucket near the top with high `covered` is where the drift
 * concentrates; a long tail of low-`covered` entries says the shift is diffuse across many small
 * segments rather than owned by a few.
 *
 * <p><b>"Unmoved baseline exemplars" are reported as the reference-side AGGREGATE</b> ({@code
 * quantiles}/{@code workload}'s "then" halves) rather than fabricated instance ids — this payload
 * shape carries no per-instance baseline rows (the pinned arm's do, via {@code baseline} evidence refs,
 * which the shared evidence-enumeration section below already surfaces; the rolling arm has no rows at
 * all, only the {@code control} ring composition). Stating the real aggregate is the honest form of
 * "what did the reference side look like" when no exemplar rows exist to name.
 */
final class MetricDriftDossier {

    private MetricDriftDossier() {}

    static String build(JsonNode root) {
        StringBuilder sb = new StringBuilder("# Metric drift evidence\n\n");
        JsonNode bucket = root.path("bucket");
        sb.append("- bucket: `")
                .append(bucket.path("key").asText("?"))
                .append("` (")
                .append(bucket.path("kind").asText("?"))
                .append(")\n");
        sb.append("- reference: ").append(root.path("reference").asText("?")).append('\n');
        sb.append("- direction: ").append(root.path("direction").asText("?")).append('\n');
        sb.append(String.format(
                Locale.ROOT,
                "- effect: w1_log=%.4f ratio=%.4fx (floor %.4f)%n",
                root.path("w1_log").asDouble(0),
                root.path("ratio").asDouble(0),
                root.path("floor").asDouble(0)));
        sb.append("- population: n_ref=")
                .append(root.path("n_ref").asLong(0))
                .append(", n_cur=")
                .append(root.path("n_cur").asLong(0))
                .append('\n');

        JsonNode q = root.path("quantiles");
        sb.append("\n## Reference vs. current (the paired before/after aggregate)\n\n");
        appendPair(sb, "p50", q.path("p50"));
        appendPair(sb, "p95", q.path("p95"));
        JsonNode workload = root.path("workload");
        if (!workload.isMissingNode()) {
            appendPair(sb, "input_tokens_p50", workload.path("input_tokens_p50"));
            appendPair(sb, "user_msg_chars_p50", workload.path("user_msg_chars_p50"));
            appendPair(sb, "prior_turns_p50", workload.path("prior_turns_p50"));
        }
        JsonNode tokens = root.path("tokens");
        if (tokens.isObject()) {
            var it = tokens.fields();
            while (it.hasNext()) {
                var e = it.next();
                appendPair(sb, e.getKey(), e.getValue());
            }
        }
        if (root.hasNonNull("control")) {
            JsonNode c = root.path("control");
            sb.append("\nReference composition (rolling arm — no per-instance baseline rows exist for"
                            + " this arm, only this ring): ")
                    .append(c.path("days_used").asInt(0))
                    .append(" day(s) used, ")
                    .append(c.path("days_excluded_as_confirmed").asInt(0))
                    .append(" excluded as already-confirmed, oldest day ")
                    .append(c.path("oldest_day").asText("?"))
                    .append(".\n");
        }

        JsonNode explains = root.path("explains");
        sb.append("\n## Concentration: which sibling buckets this drift explains\n\n");
        if (explains.isArray() && !explains.isEmpty()) {
            List<JsonNode> ranked = new ArrayList<>();
            explains.forEach(ranked::add);
            ranked.sort(
                    Comparator.comparingDouble((JsonNode n) -> n.path("covered").asDouble(0))
                            .reversed());
            sb.append("Sibling buckets this same shift also moved, ranked by `covered` — the fraction of"
                    + " THIS bucket's own shift the parent drift accounts for. A high `covered` near"
                    + " the top says the drift concentrates there; a long tail of low `covered`"
                    + " values says it is diffuse.\n\n");
            for (JsonNode e : ranked) {
                sb.append("- `")
                        .append(e.path("bucket").path("key").asText("?"))
                        .append("`: covered=")
                        .append(String.format(
                                Locale.ROOT, "%.3f", e.path("covered").asDouble(0)))
                        .append(", n_cur=")
                        .append(e.path("n_cur").asLong(0))
                        .append(", direction=")
                        .append(e.path("direction").asText("?"))
                        .append('\n');
            }
        } else {
            sb.append("This shift explains no sibling buckets (explains=[] or absent) — it did not"
                    + " suppress any other finding, so there is nothing to concentrate over.\n");
        }

        return sb.toString();
    }

    private static void appendPair(StringBuilder sb, String label, JsonNode pair) {
        if (!pair.isArray() || pair.size() != 2) return;
        JsonNode then = pair.get(0);
        JsonNode now = pair.get(1);
        if (then.isNull() && now.isNull()) return;
        sb.append("- ")
                .append(label)
                .append(": ")
                .append(then.isNull() ? "—" : then.asText())
                .append(" → ")
                .append(now.isNull() ? "—" : now.asText())
                .append('\n');
    }
}
