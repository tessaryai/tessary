// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Stable byte form for JSON stored in a {@code text} column and compared as text: sorted keys, no incidental
 * whitespace. Two bundles' {@code output_schema} must compare equal, or a no-op import would rewind Malformed Output's
 * swept history. Arrays, {@code required} included, keep their order.
 */
final class CanonicalJson {

    private CanonicalJson() {}

    @ExcludeFromJacocoGeneratedReport(
            "defensive: the mapper is the app's own, and a JsonNode converted to plain maps, lists and scalars"
                    + " always serialises")
    static String of(ObjectMapper mapper, JsonNode json) {
        try {
            return mapper.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(mapper.convertValue(json, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CanonicalJson: failed to serialise " + json, e);
        }
    }
}
