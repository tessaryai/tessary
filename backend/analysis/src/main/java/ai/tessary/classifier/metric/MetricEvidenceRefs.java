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
 * Codec for {@code metric_baseline.pinned_refs_json}: the rows a pinned reference window was fitted over. A sketch
 * cannot be un-summarized and the window may be weeks past, so the refs are stored beside it by {@code repin}.
 * Two-letter members because the array is read on every fold; {@code s} is present only for span-grain measures, as in
 * {@code finding_evidence}.
 */
final class MetricEvidenceRefs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricEvidenceRefs() {}

    static @Nullable String toJson(List<Ref> refs) {
        if (refs.isEmpty()) return null;
        ArrayNode array = JSON.createArrayNode();
        for (Ref ref : refs) {
            ObjectNode node = array.addObject();
            node.put("t", ref.traceId());
            if (ref.spanId() != null) node.put("s", ref.spanId());
        }
        return array.toString();
    }

    /** An absent or unreadable blob reads as NONE: a sweep that threw here would stop watching the bucket. */
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
