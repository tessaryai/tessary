// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Stable byte form for a JSON value stored in a {@code text} column and compared as text — sorted
 * object keys, no incidental whitespace.
 *
 * <p>Exists because {@code call_site.output_schema} is compared across imports: the same schema
 * declared by two bundles (re-serialized from YAML, keys in whatever order the producer emitted them)
 * must read as unchanged, or a no-op import would rewind the Malformed Output built-in's entire swept
 * history. Key order carries no meaning in JSON Schema, so sorting is safe; arrays (including {@code
 * required}) keep their order.
 */
final class CanonicalJson {

    private CanonicalJson() {}

    @ExcludeFromJacocoGeneratedReport(
            "a JsonNode converted to plain maps, lists and scalars always serialises, so the catch cannot fire")
    static String of(ObjectMapper mapper, JsonNode json) {
        try {
            return mapper.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(mapper.convertValue(json, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CanonicalJson: failed to serialise " + json, e);
        }
    }
}
