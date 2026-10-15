// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jspecify.annotations.Nullable;

/**
 * The single definition of "safe to bind into a {@code jsonb} column". Postgres rejects what this lets through by
 * aborting the statement, which with autocommit drops the rest of the batch. {@code FAIL_ON_TRAILING_TOKENS} catches
 * JSON followed by more text, which a plain {@code readTree} accepts.
 */
public final class Jsonb {

    /** Validation-only, so it needs no Spring config. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Jsonb() {}

    /**
     * One complete JSON object or array, else null rather than a throw, so ingest stays fail-open and the value
     * degrades to its raw column.
     */
    public static @Nullable String orNull(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.stripLeading();
        if (t.charAt(0) != '{' && t.charAt(0) != '[') return null; // cheap pre-check
        try {
            JsonNode n = STRICT.readTree(s);
            return n.isObject() || n.isArray() ? s : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
