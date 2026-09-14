// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flattens a call site's declared {@code output_schema} into the field tree "How outputs broke"
 * renders: one row per named field, in schema order, indented by nesting depth. An array's items are
 * folded into the array's own row rather than given a row of their own — {@code items[].sku} nests
 * under {@code items[]}, not under an anonymous "items" container — matching how {@link
 * ai.tessary.classifier.detector.MalformedOutputDetector} collapses a violating instance's array
 * indexes to the same {@code []} spelling, so a row's path is exactly the key a failure count is
 * filed under.
 */
final class MalformedOutputSchema {

    private MalformedOutputSchema() {}

    /**
     * @param path the field's collapsed path ({@code items[].sku}), the correlation key into a failure
     *     count map and the {@code field} query parameter of the failing-outputs endpoint
     * @param name the label a tree renders ({@code items[]} for an array field, otherwise the property
     *     name)
     */
    record Field(String path, String name, String type, boolean required, int depth, long failing) {}

    static List<Field> flatten(JsonNode schema, Map<String, Long> failingByField) {
        List<Field> out = new ArrayList<>();
        addFields(schema, "", 0, out, failingByField);
        return List.copyOf(out);
    }

    private static void addFields(
            JsonNode schema, String pathPrefix, int depth, List<Field> out, Map<String, Long> failing) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) return;
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode r : schema.path("required")) required.add(r.asText(""));
        var names = properties.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            JsonNode child = properties.get(key);
            String path = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            boolean isArray = isArrayType(child);
            String name = isArray ? key + "[]" : key;
            out.add(new Field(
                    path, name, typeOf(child), required.contains(key), depth, failing.getOrDefault(path, 0L)));
            if (isArray) {
                JsonNode items = child.path("items");
                if (items.isObject()) addFields(items, path + "[]", depth + 1, out, failing);
            } else {
                addFields(child, path, depth + 1, out, failing);
            }
        }
    }

    private static boolean isArrayType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) return "array".equals(type.asText());
        return schema.has("items");
    }

    private static String typeOf(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) return type.asText();
        if (type.isArray()) {
            List<String> types = new ArrayList<>();
            type.forEach(t -> types.add(t.asText()));
            return String.join("|", types);
        }
        if (schema.has("enum")) return "enum";
        if (schema.has("properties")) return "object";
        if (schema.has("items")) return "array";
        return "";
    }
}
