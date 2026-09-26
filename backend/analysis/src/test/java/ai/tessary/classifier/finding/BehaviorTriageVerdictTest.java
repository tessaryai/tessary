// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parse contract for a triage run: a verdict is recorded only for a run that produced one. Anything but a cited
 * {@code positive} or {@code negative} parses to null, so the job retries and eventually dead-letters rather than
 * closing a finding on a broken run.
 */
class BehaviorTriageVerdictTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unwrapsTheStructuredOutputEnvelope() {
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(mapper, """
                {"result": "", "structured_output":
                  {"verdict": "negative", "summary": "the reference window is 40 turns",
                   "citations": [{"path": "checks/window_sizes.py", "reason": "printed both counts"}]}}
                """);

        assertNotNull(v);
        assertEquals(FindingRow.TriageVerdict.NEGATIVE, v.verdict());
        assertEquals(FindingRow.TriageAction.CLOSED, v.action());
        assertEquals("the reference window is 40 turns", v.summary());
    }

    /**
     * The fabrication guard [R6]: an uncited ruling is discarded, and the job retries as {@code
     * TRIAGE_RUN_INCOMPLETE}.
     */
    @Test
    void anUncitedRulingReturnsNull() {
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(mapper, """
                {"verdict": "positive", "summary": "seems real", "citations": []}
                """);

        assertNull(v, "an uncited ruling must not become a verdict — it is a failed run, not a low-confidence one");
    }

    /** `unclear` is gone [decision 4]: positive and negative only. */
    @Test
    void rejectsUnclear() {
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(mapper, """
                {"verdict": "unclear", "summary": "the evidence does not settle it",
                 "citations": [{"path": "window.n_cur", "reason": "too thin to tell"}]}
                """);

        assertNull(v, "unclear is not a recognised verdict word any more");
    }

    @Test
    void aRunThatSaidNothingIsNotARuling() {
        for (String bad :
                new String[] {"", "   ", "not json at all", "{}", "{\"verdict\": \"probably_fine\", \"citations\": []}"
                }) {
            assertNull(BehaviorTriageVerdict.parse(mapper, bad), "input: " + bad);
        }
    }

    /**
     * The {@code --output-format json} envelope's {@code result} carries the ruling, as prose-wrapped JSON text or an
     * object.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"result\": \"My ruling: {\\\"verdict\\\": \\\"positive\\\", \\\"summary\\\": \\\"s\\\","
                        + " \\\"citations\\\": [{\\\"path\\\": \\\"a\\\", \\\"reason\\\": \\\"b\\\"}]} done.\"}",
                "{\"result\": {\"verdict\": \"positive\", \"summary\": \"s\","
                        + " \"citations\": [{\"path\": \"a\", \"reason\": \"b\"}]}}",
            })
    void unwrapsARulingCarriedInTheResultField(String envelope) {
        assertEquals(
                new BehaviorTriageVerdict(
                        FindingRow.TriageVerdict.POSITIVE,
                        "s",
                        List.of(new BehaviorTriageVerdict.Citation("a", "b", null))),
                BehaviorTriageVerdict.parse(mapper, envelope));
    }

    /** A {@code result} whose text holds no ruling is a run that said nothing, not a verdict. */
    @Test
    void aResultWithNoRulingInItIsNotARuling() {
        assertNull(BehaviorTriageVerdict.parse(mapper, "{\"result\": \"I ran out of turns.\"}"));
    }

    /**
     * A script's stdout is the receipt, kept to 4,000 characters with a cut marker. Empty or non-text stdout is no
     * receipt.
     */
    @Test
    void aScriptsStdoutIsKeptUpToTheCapAndMarkedWhenCut() {
        String atCap = "x".repeat(4_000);
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(
                mapper,
                "{\"verdict\": \"negative\", \"summary\": \"s\", \"citations\": ["
                        + "{\"path\": \"checks/a.py\", \"reason\": \"r\", \"stdout\": \"" + atCap + "\"},"
                        + "{\"path\": \"checks/b.py\", \"reason\": \"r\", \"stdout\": \"" + atCap + "y\"},"
                        + "{\"path\": \"checks/c.py\", \"reason\": \"r\", \"stdout\": \"\"},"
                        + "{\"path\": \"checks/d.py\", \"reason\": \"r\", \"stdout\": 42}]}");

        assertEquals(
                List.of(
                        new BehaviorTriageVerdict.Citation("checks/a.py", "r", atCap),
                        new BehaviorTriageVerdict.Citation("checks/b.py", "r", atCap + "\n…truncated"),
                        new BehaviorTriageVerdict.Citation("checks/c.py", "r", null),
                        new BehaviorTriageVerdict.Citation("checks/d.py", "r", null)),
                java.util.Objects.requireNonNull(v).citations());
    }
}
