// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.toolerror.ToolFailure.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The definition's tests. {@code devdocs/concepts/tool-error.md} §1 and §2 say what a tool failure is
 * and when two of them are the same kind; this is where those sentences are enforceable.
 */
class ToolFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                // Rule 1: span status, keeping its message.
                Arguments.of("HTTP 500 upstream", false, null, null, null, Source.SPAN_STATUS, "http <num> upstream"),
                // The rule this class exists for: a framework catches the exception, returns an error object to the
                // model, and closes the span cleanly. Status OK, error_type null, and once invisible.
                Arguments.of(
                        null,
                        false,
                        null,
                        null,
                        "{\"error\": \"no such customer\"}",
                        Source.RESULT_ERROR,
                        "no such customer"),
                Arguments.of(
                        null,
                        false,
                        null,
                        null,
                        "{\"isError\": true, \"content\": \"rate limited\"}",
                        Source.RESULT_ERROR,
                        "rate limited"),
                Arguments.of(
                        null,
                        false,
                        null,
                        null,
                        "{\"isError\": true}",
                        Source.RESULT_ERROR,
                        "tool result reported iserror"),
                Arguments.of(
                        null, false, "OrderServiceTimeout", null, null, Source.ERROR_TYPE_ATTR, "orderservicetimeout"),
                Arguments.of(
                        null,
                        false,
                        null,
                        "java.net.SocketTimeoutException",
                        null,
                        Source.EXCEPTION_ATTR,
                        "java.net.sockettimeoutexception"),
                // The sender stating an error outright wins over a read convention.
                Arguments.of(
                        "HTTP 500", true, "Timeout", null, "{\"isError\": true}", Source.SPAN_STATUS, "http <num>"),
                Arguments.of(null, true, null, null, null, Source.SPAN_STATUS, ToolFailure.UNDESCRIBED));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void eachRuleRecognizesAFailureWithItsSourceAndSignature(
            @Nullable String errorType,
            boolean isError,
            @Nullable String errorTypeAttr,
            @Nullable String exceptionAttr,
            @Nullable String result,
            Source source,
            String signature) {
        ToolFailure.Recognized r = ToolFailure.recognize(
                errorType, isError, errorTypeAttr, exceptionAttr, result == null ? null : json(result));
        assertNotNull(r, "expected this to be recognized as a failure");
        assertEquals(source, r.source());
        assertEquals(signature, r.signature());
    }

    /** Each exclusion is a way a broader rule would report successes as failures. */
    @Test
    void whatIsNotAFailure() {
        assertNull(ToolFailure.recognize(null, false, null, null, null), "a clean call");
        assertNull(ToolFailure.recognize("", false, null, null, null), "a blank status message");
        assertNull(ToolFailure.recognize(null, false, null, null, json("{}")), "an empty result");
        assertNull(ToolFailure.recognize(null, false, null, null, json("{\"error\": null}")), "an explicit null error");
        assertNull(
                ToolFailure.recognize(null, false, null, null, json("{\"isError\": false}")),
                "MCP saying outright that it did NOT fail");
        assertNull(
                ToolFailure.recognize(null, false, null, null, json("{\"result\": \"no errors were found\"}")),
                "output text that merely mentions the word — the definition never reads prose");
        assertNull(
                ToolFailure.recognize(null, false, null, null, json("[{\"error\": \"x\"}]")),
                "a top-level array: the convention is a top-level object key, not a search of the payload");
    }

    @Test
    void placeholdersCoverEachVaryingKind() {
        assertEquals(
                "connection reset by peer at <ip>", ToolFailure.signature("connection reset by peer at 10.2.3.4:5432"));
        assertEquals("job <uuid> failed", ToolFailure.signature("job 3f2504e0-4f89-11d3-9a0c-0305e82c3301 failed"));
        assertEquals("bad digest <hex>", ToolFailure.signature("bad digest deadbeefcafe1234"));
        assertEquals("tool <str> not found", ToolFailure.signature("tool 'search_docs' not found"));
        assertEquals(
                "tool <str> failed after <num>ms (attempt <num>/<num>)",
                ToolFailure.signature("Tool 'search_docs' failed after 30014ms (attempt 3/3)"));
        assertEquals(ToolFailure.signature("Connection   Refused"), ToolFailure.signature("connection refused"));
    }

    @Test
    void blankAndNullCollapseToOneGroupRatherThanVanishing() {
        // UNDESCRIBED means only "there was no message": a message of pure variance still groups.
        assertEquals("<num>", ToolFailure.signature("12345"), "a message that is only variance still groups");
        assertEquals(ToolFailure.UNDESCRIBED, ToolFailure.signature(null));
        assertEquals(ToolFailure.UNDESCRIBED, ToolFailure.signature("   "));
    }

    /**
     * What a result payload declares about itself. An error object is read for its message, then its code,
     * then its type; one with none of them, or an error that is neither text nor an object, is still a
     * declared error rather than a clean call. A flag that is not a boolean true, or a null error, declares
     * nothing: reading those as failures would count a healthy tool as failing.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NONE",
            value = {
                "{\"error\":{\"message\":\"card declined\",\"code\":\"E1\"}} | card declined",
                "{\"error\":{\"message\":\" \",\"code\":\"E42\"}}           | E42",
                "{\"error\":{\"code\":7,\"type\":\"Timeout\"}}              | Timeout",
                "{\"error\":{}}                                            | result declared an error",
                "{\"error\":[\"x\"]}                                         | result declared an error",
                "{\"isError\":true,\"content\":\" \"}                          | tool result reported isError",
                "{\"isError\":\"true\"}                                      | NONE",
                "{\"isError\":false}                                       | NONE",
                "{\"error\":null}                                          | NONE",
                "[\"error\"]                                               | NONE"
            })
    void resultErrorReadsTheDeclaredErrorAndNothingElse(String result, @Nullable String declared) {
        assertEquals(declared, ToolFailure.resultError(json(result)));
    }
}
