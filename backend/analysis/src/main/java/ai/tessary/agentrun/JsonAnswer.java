// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The JSON object a reply answers with, read as {@code agent-stream.js} reads a one-shot run's reply: a markdown fence
 * and prose on either side are tolerated, since "ONLY JSON" is an instruction, not a guarantee.
 */
final class JsonAnswer {

    /** A reply with five candidate objects is not a schema reply. */
    private static final int MAX_SPANS = 4;

    private JsonAnswer() {}

    /** The first balanced object that parses; a reply truncated mid-object yields nothing. */
    static Optional<ObjectNode> extract(ObjectMapper mapper, String text) {
        for (String span : balancedSpans(text)) {
            try {
                return Optional.of(mapper.readValue(span, ObjectNode.class));
            } catch (JsonProcessingException e) {
                // The next span may parse.
            }
        }
        return Optional.empty();
    }

    /** Top-level {@code required} keys the answer leaves out, in schema order; a key holding null is present. */
    static List<String> missingKeys(@Nullable JsonNode answer, JsonNode schema) {
        List<String> missing = new ArrayList<>();
        for (JsonNode key : schema.path("required")) {
            if (answer == null || !answer.has(key.asText())) missing.add(key.asText());
        }
        return missing;
    }

    /** String-aware, because markdown inside a JSON string is full of braces. */
    private static List<String> balancedSpans(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length() && out.size() < MAX_SPANS; i++) {
            if (text.charAt(i) != '{') continue;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int j = i; j < text.length(); j++) {
                char ch = text.charAt(j);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == '"') inString = false;
                    continue;
                }
                if (ch == '"') inString = true;
                else if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) {
                    out.add(text.substring(i, j + 1));
                    i = j;
                    break;
                }
            }
        }
        return out;
    }
}
