// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jspecify.annotations.Nullable;

/**
 * The single definition of "safe to bind into a {@code jsonb} column".
 *
 * <p>A value reaches a {@code jsonb} parameter as a raw string, so Postgres — not Java — is the one
 * that parses it. Anything this guard lets through and Postgres then rejects aborts the statement,
 * and (because the substrate writes with autocommit, not one transaction per batch) takes every
 * not-yet-written row of the batch with it.
 *
 * <p><b>Trailing tokens are the trap.</b> A plain {@code readTree} stops at the end of the first
 * JSON value and ignores whatever follows, so {@code {"ok":1}\nShell cwd was reset to /tmp} parses
 * as an object. Binding the whole string then fails in Postgres with
 * {@code invalid input syntax for type json}. Tool output that prints JSON and then keeps talking
 * hits this constantly. {@code FAIL_ON_TRAILING_TOKENS} is what makes the check mean what it says.
 */
public final class Jsonb {

    /** Validation-only: never used to (de)serialize domain types, so it needs no Spring config. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Jsonb() {}

    /**
     * {@code s} when it is one complete JSON object or array and nothing else, else null.
     *
     * <p>Null — rather than a throw — is the contract: a value that cannot be stored as structured
     * JSON degrades to its raw/blob column, and ingest stays fail-open.
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
