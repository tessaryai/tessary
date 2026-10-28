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
 * Codec for {@code metric_baseline.pinned_refs_json} and {@code current_refs_json}: the rows a window was fitted over.
 * A sketch cannot be un-summarized and the window may be weeks past, so the refs are stored beside it. Two-letter
 * members because the array is read on every fold; {@code s} is present only for span-grain measures, as in
 * {@code finding_evidence}. {@code a} (event time) and {@code v} (log value, unrounded) locate the change point; blobs
 * written before they existed read them as null.
 */
final class MetricEvidenceRefs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricEvidenceRefs() {}

    record Timed(Ref ref, @Nullable String at, @Nullable Double value) {

        static Timed of(Ref ref) {
            return new Timed(ref, null, null);
        }

        /**
         * Clamped to the grid as {@link MetricHistogram} clamps it, so the change point and the reference mean are in
         * the same units. A zero reading is -Infinity, which no CUSUM term or JSON number survives.
         */
        static Timed measured(Ref ref, String at, double logValue, MetricHistogram.Grid grid) {
            return new Timed(ref, at, Math.clamp(logValue, grid.logLo(), grid.logHi()));
        }

        boolean valued() {
            return at != null && value != null;
        }
    }

    static List<Ref> refs(List<Timed> timed) {
        return timed.stream().map(Timed::ref).toList();
    }

    static @Nullable String toJson(List<Timed> refs) {
        if (refs.isEmpty()) return null;
        ArrayNode array = JSON.createArrayNode();
        for (Timed timed : refs) {
            ObjectNode node = array.addObject();
            node.put("t", timed.ref().traceId());
            if (timed.ref().spanId() != null) node.put("s", timed.ref().spanId());
            if (timed.at() != null) node.put("a", timed.at());
            if (timed.value() != null) node.put("v", timed.value());
        }
        return array.toString();
    }

    /** An absent or unreadable blob reads as NONE: a sweep that threw here would stop watching the bucket. */
    static List<Timed> fromJson(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode array = JSON.readTree(json);
            if (!array.isArray()) return List.of();
            List<Timed> refs = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                String traceId = node.path("t").asText(null);
                if (traceId == null || traceId.isBlank()) continue;
                String spanId = node.path("s").asText(null);
                Ref ref = spanId == null || spanId.isBlank() ? Ref.trace(traceId) : Ref.span(traceId, spanId);
                String at = node.path("a").asText(null);
                JsonNode value = node.path("v");
                refs.add(new Timed(
                        ref, at == null || at.isBlank() ? null : at, value.isNumber() ? value.asDouble() : null));
            }
            return List.copyOf(refs);
        } catch (Exception e) {
            return List.of();
        }
    }
}
