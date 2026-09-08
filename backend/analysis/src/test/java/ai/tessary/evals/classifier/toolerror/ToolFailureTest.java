// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.toolerror.ToolFailure.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The definition's tests. {@code classifiers/tool_error/PROGRAM.md} §1 and §2 say what a tool failure is
 * and when two of them are the same kind; this is where those sentences are enforceable.
 */
class ToolFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Recognize, and fail the test rather than the null check if nothing matched. {@code recognize}
     * returns null for a clean call, which is a real answer the exclusion tests below assert on — so the
     * inclusion tests need somewhere to say "this must have matched" that is not a bare dereference.
     */
    private static ToolFailure.Recognized recognized(
            @Nullable String errorType,
            boolean isError,
            @Nullable String errorTypeAttr,
            @Nullable String exceptionAttr,
            @Nullable JsonNode result) {
        ToolFailure.Recognized r = ToolFailure.recognize(errorType, isError, errorTypeAttr, exceptionAttr, result);
        assertNotNull(r, "expected this to be recognized as a failure");
        return r;
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // §1 — the four rules
    // ---------------------------------------------------------------------------------------------

    @Test
    void spanStatusIsRuleOneAndKeepsItsMessage() {
        var r = recognized("HTTP 500 upstream", false, null, null, null);
        assertEquals(Source.SPAN_STATUS, r.source());
        assertEquals("http <num> upstream", r.signature());
    }

    /**
     * The rule the old definition was missing, and the reason this whole class exists: a framework
     * catches the exception, hands an error object back to the model, and closes the span cleanly. Span
     * status is OK, {@code error_type} is null, and before this the platform could not see it at all.
     */
    @Test
    void aCleanSpanWhoseResultDeclaresAnErrorIsAFailure() {
        var r = recognized(null, false, null, null, json("{\"error\": \"no such customer\"}"));
        assertEquals(Source.RESULT_ERROR, r.source());
        assertEquals("no such customer", r.signature());
    }

    @Test
    void mcpIsErrorEnvelopeIsAFailure() {
        var r = recognized(null, false, null, null, json("{\"isError\": true, \"content\": \"rate limited\"}"));
        assertEquals(Source.RESULT_ERROR, r.source());
        assertEquals("rate limited", r.signature());
    }

    @Test
    void mcpIsErrorWithoutContentStillGroups() {
        var r = recognized(null, false, null, null, json("{\"isError\": true}"));
        assertEquals(Source.RESULT_ERROR, r.source());
        assertEquals("tool result reported iserror", r.signature());
    }

    @Test
    void otelErrorTypeAttributeIsAFailureEvenWithNoSpanStatus() {
        var r = recognized(null, false, "OrderServiceTimeout", null, null);
        assertEquals(Source.ERROR_TYPE_ATTR, r.source());
        assertEquals("orderservicetimeout", r.signature());
    }

    @Test
    void recordedExceptionTypeIsAFailure() {
        var r = recognized(null, false, null, "java.net.SocketTimeoutException", null);
        assertEquals(Source.EXCEPTION_ATTR, r.source());
        assertEquals("java.net.sockettimeoutexception", r.signature());
    }

    @Test
    void spanStatusOutranksAReadConvention() {
        var r = recognized("HTTP 500", true, "Timeout", null, json("{\"isError\": true}"));
        assertEquals(Source.SPAN_STATUS, r.source(), "the sender stating an error outright wins over a convention");
    }

    @Test
    void isErrorWithNoMessageAnywhereStillCounts() {
        var r = recognized(null, true, null, null, null);
        assertEquals(Source.SPAN_STATUS, r.source());
        assertEquals(ToolFailure.UNDESCRIBED, r.signature());
    }

    /**
     * The exclusions, which matter as much as the inclusions — each of these is a way a broader rule
     * would have started reporting successes as failures.
     */
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

    // ---------------------------------------------------------------------------------------------
    // §2 — signatures group, and what they erase
    // ---------------------------------------------------------------------------------------------

    /**
     * The whole point of a signature. These three messages are one failure mode and would be three
     * singletons under {@code GROUP BY error_type}, which is what makes a raw breakdown useless.
     */
    @Test
    void oneFailureModeGroupsAcrossItsVaryingParts() {
        String a = ToolFailure.signature("HTTP 500 upstream from https://api.stripe.com/v1/charges/ch_3Ox9aB");
        String b = ToolFailure.signature("HTTP 500 upstream from https://api.stripe.com/v1/charges/ch_9Zk1qW");
        String c = ToolFailure.signature("HTTP 503 upstream from https://api.stripe.com/v1/refunds/re_44");
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals("http <num> upstream from <url>", a);
    }

    @Test
    void distinctFailureModesDoNotGroup() {
        assertNotEquals(ToolFailure.signature("timeout after 30014ms"), ToolFailure.signature("connection refused"));
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
    }

    /**
     * Order dependence, stated as a test because it is the way this normalizer breaks silently: a URL
     * contains digits and a UUID contains hex, so a pass that ran {@code <num>} first would shred both
     * into unrecognizable fragments that no longer group.
     */
    @Test
    void broaderPatternsRunBeforeNarrowerOnes() {
        assertEquals("get <url> failed", ToolFailure.signature("GET https://api.x.com/v2/items/91821 failed"));
        assertEquals("<uuid>", ToolFailure.signature("3f2504e0-4f89-11d3-9a0c-0305e82c3301"));
    }

    @Test
    void blankAndNullCollapseToOneGroupRatherThanVanishing() {
        // UNDESCRIBED means "there was no message", and nothing else. A message made entirely of variance
        // still normalizes to its placeholder form and still groups — two failures reported as bare
        // numbers are the same kind of unhelpful, and saying so is more accurate than pretending the
        // sender said nothing at all.
        assertEquals("<num>", ToolFailure.signature("12345"), "a message that is only variance still groups");
        assertEquals(ToolFailure.UNDESCRIBED, ToolFailure.signature(null));
        assertEquals(ToolFailure.UNDESCRIBED, ToolFailure.signature("   "));
    }

    /**
     * Signatures are keys in a persisted evidence blob, so an unbounded one is a stack trace pasted into
     * a status message becoming a single map key.
     */
    @Test
    void signaturesAreBounded() {
        String sig = ToolFailure.signature("failure: " + "a".repeat(500));
        assertTrue(sig.length() <= ToolFailure.MAX_SIGNATURE_LENGTH + 1, sig.length() + " chars");
    }

    @Test
    void signatureIsStableAcrossCaseAndWhitespace() {
        assertEquals(ToolFailure.signature("Connection   Refused"), ToolFailure.signature("connection refused"));
    }

    // ---------------------------------------------------------------------------------------------
    // The SQL predicate's shape — it is interpolated, so its aliases are a contract
    // ---------------------------------------------------------------------------------------------

    @Test
    void predicateNamesTheAliasesItsCallersMustUse() {
        String sql = ToolFailure.SQL_PREDICATE;
        assertTrue(sql.contains("tc.error_type"), sql);
        // The span's own typed error column. A tool span whose STATUS was error but whose tool_call row
        // carried no error_type used to be reachable only through the jsonb blob; it is a column now.
        assertTrue(sql.contains("o.error_type IS NOT NULL"), sql);
        // The two producer attributes moved OFF the span row and onto span_payload, so they are read
        // through `pl`. That is the alias change with teeth: a caller that interpolates this constant
        // without joining span_payload does not under-report, it fails outright at
        // `column o.attributes does not exist` — which is the failure mode worth having.
        assertTrue(sql.contains("pl.attributes ->> 'error.type'"), sql);
        assertTrue(sql.contains("pl.attributes ->> 'exception.type'"), sql);
        assertFalse(sql.contains("o.attributes"), "the span row has no attributes column in v2: " + sql);
        assertTrue(sql.contains("tc.result @> '{\"isError\": true}'::jsonb"), sql);
        // Balanced, because it is spliced into a larger WHERE and an unbalanced one would take the rest
        // of that clause with it.
        assertEquals(
                sql.chars().filter(c -> c == '(').count(),
                sql.chars().filter(c -> c == ')').count(),
                "unbalanced parentheses in a predicate that gets interpolated: " + sql);
    }
}
