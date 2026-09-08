// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The split that keeps {@code error_type} a type. The column is a facet key everywhere it is read, and it
 * used to be written from the producer's status message — which is how one came to hold 3,271 characters
 * of agent markdown, and why no two failures of the same kind ever grouped (#762).
 */
class SpanErrorsTest {

    @Test
    @DisplayName("the producer's own class wins, verbatim")
    void declaredTypeIsTakenAsIs() {
        assertEquals("OrderServiceTimeout", SpanErrors.errorClass("OrderServiceTimeout", "anything at all"));
    }

    @Test
    @DisplayName("with no declared class, the message is signatured rather than truncated")
    void fallsBackToASignature() {
        String type = SpanErrors.errorClass(
                null, "HTTP 500 upstream from https://api.example.com/v1/charges/ch_3Ox9aB after 30014ms");
        assertEquals("http <num> upstream from <url> after <num>ms", type);
    }

    @Test
    @DisplayName("a blank declared class is not a class")
    void blankDeclaredTypeIsIgnored() {
        assertEquals("timed out", SpanErrors.errorClass("   ", "timed out"));
        assertNull(SpanErrors.errorClass(" ", " "), "nothing said, nothing invented");
    }

    @Test
    @DisplayName("a declared class is capped too, not just the signature fallback")
    void declaredTypeIsCapped() {
        String huge = "x".repeat(500);
        String type = SpanErrors.errorClass(huge, null);
        assertTrue(type != null && type.length() <= 121, "declared type must be bounded like the fallback");
        assertTrue(type.endsWith("…"), "a truncated declared type is marked, same as the signature fallback");
    }

    @Test
    @DisplayName("the prose is capped, because the row is read beside every list and rollup")
    void messageIsCapped() {
        String huge = "x".repeat(SpanErrors.MAX_ERROR_MESSAGE_CHARS + 500);
        String capped = SpanErrors.cappedMessage(huge);
        assertTrue(capped != null && capped.length() == SpanErrors.MAX_ERROR_MESSAGE_CHARS);
        assertEquals("short enough", SpanErrors.cappedMessage("short enough"));
        assertNull(SpanErrors.cappedMessage(""), "a blank message is no message");
    }
}
