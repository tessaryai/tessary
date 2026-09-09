// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;

/**
 * Stable byte form for a JSON value stored in a {@code text} column and compared as text — sorted
 * object keys, no incidental whitespace.
 *
 * <p>Exists because {@code call_site.output_schema} has <b>two</b> writers that must agree exactly:
 * the {@code .tessary/} bundle (a call-site shard declaring the schema, re-serialized from YAML) and
 * agentic synthesis (an agent re-reading the same code on every run and emitting the keys in whatever
 * order that run produced). Compared raw, the same schema written by the two paths — or by the same
 * path twice — reads as a change, and a change rewinds the Malformed Output built-in's entire swept
 * history. Key order carries no meaning in JSON Schema, so sorting is safe; arrays (including {@code
 * required}) keep their order.
 *
 * <p>Unparseable input passes through untouched rather than being rejected: persisting exactly what
 * the writer produced keeps the failure diagnosable, and the detector already treats a schema that
 * won't compile as "no schema".
 */
final class CanonicalJson {

    private CanonicalJson() {}

    static @Nullable String of(ObjectMapper mapper, @Nullable String json) {
        if (json == null || json.isBlank()) return json;
        ObjectWriter writer = mapper.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        try {
            return writer.writeValueAsString(mapper.readValue(json, Object.class));
        } catch (JacksonException notJson) {
            return json;
        }
    }
}
