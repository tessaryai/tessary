// SPDX-License-Identifier: Apache-2.0
package ai.tessary.apidoc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one canonical form every checked-in OpenAPI document is pinned in: recursively key-sorted,
 * two-space indented, {@code \n} line endings, trailing newline. The open drift guard and the paid
 * assembly's spec test both pin through here, so the two documents can only ever differ in content.
 */
public final class OpenApiCanonicalizer {

    private final ObjectMapper mapper;

    public OpenApiCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String canonicalize(JsonNode json) {
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indenter);
        pp.indentArraysWith(indenter);
        try {
            return mapper.writer(pp).writeValueAsString(sort(json)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("an already-parsed document failed to serialise", e);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                out.set(name, sort(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode n : node) out.add(sort(n));
            return out;
        }
        return node;
    }
}
