// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The jsonb bind guard: only a complete JSON object/array, and nothing trailing it, may be bound. */
class JsonbTest {

    @Test
    @DisplayName("a complete object or array is bound verbatim")
    void completeJson_isBound() {
        assertEquals("{\"a\":1}", Jsonb.orNull("{\"a\":1}"));
        assertEquals("[1,2]", Jsonb.orNull("[1,2]"));
        assertEquals("  {\"a\":1}  ", Jsonb.orNull("  {\"a\":1}  "), "surrounding whitespace is legal JSON");
    }

    @Test
    @DisplayName("a JSON value followed by trailing text is rejected, not silently bound")
    void trailingTokens_rejected() {
        // The real shape that took down whole ingest batches: a tool prints JSON, then keeps talking.
        // Postgres — not Jackson — parses the bind, so letting this through fails the INSERT.
        assertNull(Jsonb.orNull("{\"id\":\"x\"}\nShell cwd was reset to /tmp"));
        assertNull(Jsonb.orNull("[1,2]\n---- next ----\n[3]"));
        assertNull(Jsonb.orNull("{\"a\":1} {\"b\":2}"), "two values are not one value");
    }

    @Test
    @DisplayName("anything that is not an object/array degrades to null (fail-open, never a throw)")
    void nonObjectOrArray_isNull() {
        assertNull(Jsonb.orNull(null));
        assertNull(Jsonb.orNull(""));
        assertNull(Jsonb.orNull("   "));
        assertNull(Jsonb.orNull("plain text"));
        assertNull(Jsonb.orNull("-rw-r--r-- 1 root"), "leading '-' is not a JSON container");
        assertNull(Jsonb.orNull("\"a string\""), "a bare scalar is valid JSON but not a container");
        assertNull(Jsonb.orNull("42"));
        assertNull(Jsonb.orNull("{\"unterminated\": "));
    }
}
