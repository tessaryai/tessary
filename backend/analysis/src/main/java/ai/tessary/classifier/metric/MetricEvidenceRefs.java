// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.finding.FindingEvidenceRepository.Ref;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The codec for {@code metric_baseline.pinned_refs_json} — the rows a pinned reference window was
 * fitted over, kept so a finding fired against that reference can enumerate its baseline side.
 *
 * <p>A sketch cannot be un-summarized and a reference window is HISTORY: by the time a window shifts,
 * the traffic its reference describes may be weeks past, and no query can re-derive which rows went
 * into it. So the refs are stored beside the sketch, written by the same {@code repin} that writes it.
 *
 * <p><b>Two-letter members, deliberately.</b> The array holds one entry per measured row and lives on a
 * row the sweep reads on every fold, so the field names are a real fraction of its size. {@code t} is
 * the trace, {@code s} the span for a measure scored at span grain and absent otherwise — the same
 * grain rule {@code finding_evidence} enforces, since these become rows in it verbatim.
 */
final class MetricEvidenceRefs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricEvidenceRefs() {}

    /** The window's rows as stored, or null when it had none to keep. */
    static @Nullable String toJson(List<Ref> refs) {
        if (refs.isEmpty()) return null;
        ArrayNode array = JSON.createArrayNode();
        for (Ref ref : refs) {
            if (ref.traceId() == null) continue;
            ObjectNode node = array.addObject();
            node.put("t", ref.traceId());
            if (ref.spanId() != null) node.put("s", ref.spanId());
        }
        return array.isEmpty() ? null : array.toString();
    }

    /**
     * The stored rows back as refs. An absent or unreadable blob reads as NONE rather than failing the
     * sweep: a finding with no baseline evidence is a weaker claim, and a sweep that threw here would
     * stop watching the bucket entirely over a blob nothing computes from.
     */
    static List<Ref> fromJson(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode array = JSON.readTree(json);
            if (!array.isArray()) return List.of();
            List<Ref> refs = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                String traceId = node.path("t").asText(null);
                if (traceId == null || traceId.isBlank()) continue;
                String spanId = node.path("s").asText(null);
                refs.add(spanId == null || spanId.isBlank() ? Ref.trace(traceId) : Ref.span(traceId, spanId));
            }
            return List.copyOf(refs);
        } catch (Exception e) {
            return List.of();
        }
    }
}
