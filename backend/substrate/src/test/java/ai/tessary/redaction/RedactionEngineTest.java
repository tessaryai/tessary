// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.redaction.RedactionEngine.CompiledRule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactionEngineTest {

    private static CompiledRule rule(String name, String regex, String replacement) {
        CompiledRule r = RedactionEngine.compile(name, regex, replacement);
        assertNotNull(r, "regex should compile: " + regex);
        return r;
    }

    @Test
    void apply_redactsEmailWithBuiltInPattern() {
        CompiledRule email = builtInEmail();
        String out = RedactionEngine.apply("contact me at jane.doe@example.com please", List.of(email));
        assertEquals("contact me at [REDACTED_EMAIL] please", out, "the email must be replaced verbatim");
    }

    @Test
    void apply_redactsEveryMatchNotJustTheFirst() {
        CompiledRule digits = rule("digits", "\\d+", "#");
        assertEquals("#-#-#", RedactionEngine.apply("12-345-6", List.of(digits)), "replaceAll, not replaceFirst");
    }

    @Test
    void apply_appliesRulesInOrder() {
        CompiledRule a = rule("a", "foo", "bar");
        CompiledRule b = rule("b", "bar", "baz");
        assertEquals("baz", RedactionEngine.apply("foo", List.of(a, b)), "second rule sees the first's output");
    }

    @Test
    void apply_returnsSameReferenceWhenNoRuleMatches() {
        String text = "nothing sensitive here";
        CompiledRule email = rule("email", "\\d{3}-\\d{2}-\\d{4}", "[SSN]");
        assertSame(text, RedactionEngine.apply(text, List.of(email)), "no-match must not allocate a new string");
    }

    @Test
    void apply_nullAndEmptyArePassedThrough() {
        CompiledRule any = rule("any", ".", "x");
        assertNull(RedactionEngine.apply(null, List.of(any)));
        assertEquals("", RedactionEngine.apply("", List.of(any)));
    }

    @Test
    void apply_replacementIsLiteralNotRegexReplacement() {
        CompiledRule r = rule("r", "secret", "$1\\n[X]");
        assertEquals(
                "$1\\n[X]",
                RedactionEngine.apply("secret", List.of(r)),
                "a $ or backslash in the replacement is substituted literally, never interpreted");
    }

    @Test
    void compile_returnsNullForInvalidRegex() {
        assertNull(RedactionEngine.compile("bad", "(", "x"), "an uncompilable regex yields no rule");
    }

    @Test
    void isValidRegex_distinguishesGoodAndBad() {
        assertTrue(RedactionEngine.isValidRegex("\\d{3}"));
        assertFalse(RedactionEngine.isValidRegex("[unterminated"));
    }

    @Test
    void countMatches_countsOccurrences() {
        CompiledRule r = rule("r", "a", "x");
        assertEquals(3, RedactionEngine.countMatches("banana", r));
        assertEquals(0, RedactionEngine.countMatches("xyz", r));
        assertEquals(0, RedactionEngine.countMatches(null, r));
    }

    @Test
    void compileAll_dropsUncompilableRows() {
        RedactionRuleRow good = new RedactionRuleRow("1", "p", "g", "\\d+", "#", true, false, 10, "t", "t");
        RedactionRuleRow bad = new RedactionRuleRow("2", "p", "b", "(", "#", true, false, 20, "t", "t");
        List<CompiledRule> compiled = RedactionEngine.compileAll(List.of(good, bad));
        assertEquals(1, compiled.size(), "the malformed rule is silently skipped, never fatal");
        assertEquals("g", compiled.get(0).name());
    }

    @Test
    void applyToTextParts_leavesInlineBinaryUntouchedButStillRedactsTheTextAroundIt() {
        CompiledRule email = builtInEmail();
        String blob = "A".repeat(400) + "9".repeat(400); // 800 chars of base64 alphabet, no separators
        String body = "contact jane@example.com about this data:image/png;base64," + blob + " thanks bob@x.io";

        String out = RedactionEngine.applyToTextParts(body, List.of(email));

        assertTrue(out.contains(blob), "the binary payload must survive byte-identical");
        assertFalse(out.contains("jane@example.com"), "text BEFORE the blob must still be redacted");
        assertFalse(out.contains("bob@x.io"), "text AFTER the blob must still be redacted");
        assertEquals(2, out.split("\\[REDACTED_EMAIL]", -1).length - 1, "both addresses replaced");
    }

    /**
     * The cost guard for the 2026-07-31 saturation: redaction over a ~1 MB inline image measured 314 ms
     * for the five built-ins (281 ms of it the email rule), because base64's alphabet is exactly what
     * the pattern's character class walks. Skipping binary must make that effectively free.
     */
    @Test
    void applyToTextParts_isCheapOnAMegabyteOfInlineMedia() {
        CompiledRule email = builtInEmail();
        String media = "data:image/png;base64," + "QUJDRA".repeat(170_000); // ~1 MB
        // assertTrue on an equals(), not assertEquals: a failure here would otherwise dump a megabyte
        // of base64 into the build log.
        // 150 ms, NOT seconds: the unskipped cost of this exact field is ~281 ms, so a generous bound
        // would pass with the skip deleted and guard nothing. This fails if BINARY_RUN stops matching.
        assertTimeoutPreemptively(
                Duration.ofMillis(150),
                () -> assertTrue(
                        media.equals(RedactionEngine.applyToTextParts(media, List.of(email))),
                        "the media field must come back byte-identical"),
                "redaction is scanning binary again — check BINARY_RUN still matches base64");

        // A field that is PURELY payload (no `data:` prefix) takes the zero-copy path.
        String bare = "QUJDRA".repeat(170_000);
        assertSame(
                bare,
                RedactionEngine.applyToTextParts(bare, List.of(email)),
                "an all-binary field must return the same reference, not a megabyte copy");
    }

    @Test
    void applyToTextParts_redactsPiiAdjacentToABlobAndBetweenTwoBlobs() {
        CompiledRule email = builtInEmail();
        String blob = "QUJDRA".repeat(200); // 1200 chars, well over BINARY_RUN's 512 floor
        // `com` is base64-alphabet, so a greedy run swallows the TLD unless the edge margin pulls it
        // back — this is the exact leak crew found on the first revision.
        String body = "mail jane@example.com" + blob + " mid bob@x.io " + blob + "tail eve@y.co";

        String out = RedactionEngine.applyToTextParts(body, List.of(email));

        assertFalse(out.contains("jane@example.com"), "address abutting the START of a blob must redact");
        assertFalse(out.contains("bob@x.io"), "address BETWEEN two blobs must redact");
        assertFalse(out.contains("eve@y.co"), "address abutting the END of a blob must redact");
    }

    @Test
    void applyToTextParts_neverTreatsProseAsPayloadEvenWhenLong() {
        CompiledRule email = builtInEmail();
        // Newlines count as run-internal (so MIME-wrapped base64 qualifies); spaces must NOT, or
        // ordinary English would be classified binary and skipped wholesale. This pins that.
        String prose = "the quick brown fox jumps over the lazy dog\n".repeat(4_000);
        String body = prose + " reach me at jane@example.com " + prose;

        String out = RedactionEngine.applyToTextParts(body, List.of(email));

        assertFalse(out.contains("jane@example.com"), "PII buried in long prose must still be redacted");
    }

    @Test
    void applyToTextParts_skipsMimeWrappedPayload() {
        CompiledRule email = builtInEmail();
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < 200; i++) wrapped.append("QUJDRA".repeat(13)).append('\n'); // 78-col lines
        String body = "attached: " + wrapped + " from bob@x.io";

        String out = RedactionEngine.applyToTextParts(body, List.of(email));

        assertTrue(out.contains(wrapped.substring(1000, 2000)), "wrapped payload interior must survive");
        assertFalse(out.contains("bob@x.io"), "text after a wrapped payload must still be redacted");
    }

    /** The email pattern exactly as it ships, so these tests cannot drift from the seeded default. */
    // ---- applyToJson: the structural path ----

    /**
     * The bug this method exists for. A Chrome {@code tabId} is a ten-digit integer and the built-in phone
     * rule matches ten digits, so the text-level redactor rewrote {@code "tabId": 1234567890} to
     * {@code "tabId": [REDACTED_PHONE]} — a bare token in a number position, which is not JSON. The typed
     * {@code tool_call.arguments} column then went null on 48.9% of MCP tool calls.
     */
    @Test
    void applyToJson_leavesNumbersAlone_soTheDocumentStaysParseable() {
        CompiledRule phone = builtInPhone();
        String json = "{\"action\":\"screenshot\",\"tabId\":1234567890}";

        String out = RedactionEngine.applyToJson(json, List.of(phone));

        assertSame(json, out, "no string leaf matched, so the original reference must come back");
        assertNotNull(JsonNodeAssert.parse(out), "the document must still be JSON");
    }

    /** A phone number in a STRING is still PII and must still be redacted — the rule is unchanged. */
    @Test
    void applyToJson_stillRedactsStringLeaves() {
        CompiledRule phone = builtInPhone();
        String out = RedactionEngine.applyToJson("{\"contact\":\"call 555-123-4567 now\"}", List.of(phone));

        assertNotNull(out);
        assertFalse(out.contains("555-123-4567"), "a phone number in a string leaf must be redacted");
        assertTrue(out.contains("[REDACTED_PHONE]"), "the replacement must be present");
        assertNotNull(JsonNodeAssert.parse(out), "the redacted document must still be JSON");
    }

    /**
     * The OpenAI-native tool shape: {@code arguments} is a JSON <em>string containing JSON</em>. A walk
     * that treated it as an ordinary text leaf would regex the nested document and reproduce the very bug
     * this method fixes, one level down.
     */
    @Test
    void applyToJson_recursesIntoAStringLeafThatIsItselfJson() {
        CompiledRule phone = builtInPhone();
        String json = "{\"name\":\"navigate\",\"arguments\":\"{\\\"tabId\\\": 1234567890}\"}";

        String out = RedactionEngine.applyToJson(json, List.of(phone));

        assertSame(json, out, "the nested number must be left alone, so nothing changed at any depth");
    }

    /** Nested JSON-in-a-string with a real match: redacted, and both levels still parse. */
    @Test
    void applyToJson_redactsInsideNestedJson_andReserializesBothLevels() {
        CompiledRule email = builtInEmail();
        String json = "{\"arguments\":\"{\\\"to\\\": \\\"jane@example.com\\\"}\"}";

        String out = RedactionEngine.applyToJson(json, List.of(email));

        assertNotNull(out);
        assertFalse(out.contains("jane@example.com"), "PII inside the nested document must be redacted");
        var outer = JsonNodeAssert.parse(out);
        assertNotNull(outer, "the outer document must still be JSON");
        assertNotNull(
                JsonNodeAssert.parse(outer.get("arguments").textValue()),
                "the nested document must still be JSON after re-serialization");
    }

    /** Anything that is not a JSON container falls through to the text path, byte for byte as before. */
    @Test
    void applyToJson_fallsBackToTheTextPathForProse() {
        CompiledRule email = builtInEmail();
        String prose = "contact me at jane.doe@example.com please";

        assertEquals(
                RedactionEngine.applyToTextParts(prose, List.of(email)),
                RedactionEngine.applyToJson(prose, List.of(email)),
                "non-JSON input must be redacted exactly as the text path would");
    }

    /**
     * A tool that prints an object and then keeps talking has not sent JSON. Treating its first object as
     * the whole payload would redact the object and leave the prose beside it untouched.
     */
    @Test
    void applyToJson_treatsTrailingProseAsText_notAsJson() {
        CompiledRule email = builtInEmail();
        String mixed = "{\"ok\":true}\nthen jane@example.com said so";

        String out = RedactionEngine.applyToJson(mixed, List.of(email));

        assertNotNull(out);
        assertFalse(out.contains("jane@example.com"), "the prose after the object must still be redacted");
    }

    /**
     * Unchanged input must come back as the SAME reference, not a re-serialized equivalent.
     * {@code SpanBatchWriter} strips an attribute only when its value is byte-for-byte identical to the
     * promoted column, and a reformat on one side of that comparison would silently stop the strip working.
     */
    @Test
    void applyToJson_returnsTheSameReferenceWhenNothingMatched() {
        CompiledRule email = builtInEmail();
        String json = "{ \"a\" : [ 1, 2, 3 ],  \"b\" : \"nothing here\" }";

        assertSame(json, RedactionEngine.applyToJson(json, List.of(email)), "no match must not reformat");
    }

    /** Media segmenting must survive the structural path: a base64 run inside a string leaf stays whole. */
    @Test
    void applyToJson_keepsInlineBinaryByteIdentical() {
        CompiledRule email = builtInEmail();
        String blob = "A".repeat(400) + "9".repeat(400);
        String json = "{\"content\":\"jane@example.com data:image/png;base64," + blob + " end\"}";

        String out = RedactionEngine.applyToJson(json, List.of(email));

        assertNotNull(out);
        assertTrue(out.contains(blob), "the binary payload must survive byte-identical inside a string leaf");
        assertFalse(out.contains("jane@example.com"), "text beside the blob must still be redacted");
    }

    /** Small helper so the assertions above can state "this is still JSON" without a field of their own. */
    private static final class JsonNodeAssert {
        static com.fasterxml.jackson.databind.@org.jspecify.annotations.Nullable JsonNode parse(String s) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return null;
            }
        }
    }

    private static CompiledRule builtInPhone() {
        String pattern = BuiltInRedactionRules.TEMPLATES.stream()
                .filter(t -> t.name().equals("Phone number"))
                .findFirst()
                .orElseThrow()
                .pattern();
        return rule("phone", pattern, "[REDACTED_PHONE]");
    }

    private static CompiledRule builtInEmail() {
        String pattern = BuiltInRedactionRules.TEMPLATES.stream()
                .filter(t -> t.name().equals("Email address"))
                .findFirst()
                .orElseThrow()
                .pattern();
        return rule("email", pattern, "[REDACTED_EMAIL]");
    }

    @Test
    void builtInEmail_matchesRealAddresses() {
        CompiledRule email = builtInEmail();
        assertEquals(
                "contact [REDACTED_EMAIL] please",
                RedactionEngine.apply("contact jane.doe+tag@example.co.uk please", List.of(email)));
        assertEquals("[REDACTED_EMAIL]", RedactionEngine.apply("a@b.io", List.of(email)));
        assertEquals(
                "[REDACTED_EMAIL]", RedactionEngine.apply("user_name%test@sub.domain.example.com", List.of(email)));
        assertEquals("no address here", RedactionEngine.apply("no address here", List.of(email)), "no false positive");
    }

    /**
     * Regression guard for the 2026-07-31 production incident: the built-in email pattern must stay
     * LINEAR in the size of non-matching text.
     *
     * <p>The original unbounded form — {@code [A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}} — was
     * O(n^2) here, because the domain class consumes the remainder before failing to find the TLD
     * and {@code find()} then retries from every subsequent start position. It measured 11.5 s on a
     * 64 KB body and 46 s on 128 KB, which pinned both production vCPUs. The bounded form does the
     * same work in ~44 ms.
     *
     * <p>The input is the pathological shape specifically: a long run of local-part-legal characters,
     * an {@code @}, then a domain-legal run with NO dot, so the match can never complete and the
     * engine does maximum work. The 5 s bound is ~100x the fixed pattern's real cost — loose enough
     * for a slow CI box, still ~10x under the quadratic version's time at this size.
     */
    @Test
    void builtInEmail_isLinearOnLargeNonMatchingText() {
        CompiledRule email = builtInEmail();
        String body = "a".repeat(64_000) + "@" + "b".repeat(64_000);
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertSame(
                        body,
                        RedactionEngine.apply(body, List.of(email)),
                        "nothing matches, so the input must come back unchanged"),
                "the built-in email pattern went superlinear again — check its quantifiers are bounded");
    }

    @Test
    void builtInChainKeepsTheCredentialTokenUnderAKeyName() {
        // Rule 90 (secret assignment) runs after rule 80 (provider API key) over the already-substituted
        // string; it must not re-wrap the token, or the secret_leak detector reads LOW instead of HIGH.
        List<CompiledRule> chain = BuiltInRedactionRules.TEMPLATES.stream()
                .map(t -> rule(t.name(), t.pattern(), t.replacement()))
                .toList();
        assertEquals(
                "api_key: [REDACTED_API_KEY] set", RedactionEngine.apply("api_key: AKIAIOSFODNN7EXAMPLE set", chain));
        assertEquals(
                "client_secret=[REDACTED_API_KEY]",
                RedactionEngine.apply("client_secret=ghp_abcdefghijklmnopqrstuvwxyz0123456789", chain));
        assertEquals(
                "access_token=[REDACTED_JWT]",
                RedactionEngine.apply("access_token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghij", chain));
        // The secret-assignment rule replaces the key name together with the value, by design.
        assertEquals(
                "[REDACTED_SECRET]",
                RedactionEngine.apply("password: hunter22", chain),
                "an ordinary value still redacts");
    }
}
