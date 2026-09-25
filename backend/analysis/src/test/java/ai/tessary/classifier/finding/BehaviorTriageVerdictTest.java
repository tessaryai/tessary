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
 * The parse contract for a triage run. One load-bearing property: a verdict is recorded only for a run
 * that actually produced one. Everything that is NOT a cited {@code positive} or {@code negative} —
 * gibberish, an unrecognised verdict word, an uncited ruling — parses to null, never to a fabricated
 * verdict. Null makes the job retry and eventually dead-letter; recording anything for it would close a
 * finding on the strength of a broken or unsupported run.
 */
class BehaviorTriageVerdictTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesACitedRuling() {
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(mapper, """
                {"verdict": "positive", "summary": "both windows measure the same population",
                 "citations": [{"path": "window.n_cur", "reason": "1,204 turns, not a thin window"}]}
                """);

        assertNotNull(v);
        assertEquals(FindingRow.TriageVerdict.POSITIVE, v.verdict());
        assertEquals(FindingRow.TriageAction.OPENED_CASE, v.action());
        assertEquals(1, v.citations().size());
        assertEquals("window.n_cur", v.citations().get(0).path());
    }

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
     * The fabrication guard [R6]. An agent that rules without pointing at anything has not done the
     * audit, so the answer is discarded rather than trusted with a verdict of its own: no ruling is
     * recorded, and the job retries as {@code TRIAGE_RUN_INCOMPLETE}.
     */
    @Test
    void anUncitedRulingReturnsNull() {
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(mapper, """
                {"verdict": "positive", "summary": "seems real", "citations": []}
                """);

        assertNull(v, "an uncited ruling must not become a verdict — it is a failed run, not a low-confidence one");
    }

    /** `unclear` is gone from the vocabulary [decision 4]: verdicts are positive and negative only. */
    @Test
    void rejectsUnclear() {
        BehaviorTriageVerdict v = BehaviorTriageVerdict.parse(mapper, """
                {"verdict": "unclear", "summary": "the evidence does not settle it",
                 "citations": [{"path": "window.n_cur", "reason": "too thin to tell"}]}
                """);

        assertNull(v, "unclear is not a recognised verdict word any more");
    }

    /**
     * A prerequisite failure is a failed run, not a verdict — the distinction the whole preflight rests
     * on. {@code parse} must refuse it exactly as it refuses gibberish, so nothing can reach
     * {@code finding.triage_verdict}, while {@code blockedReason} recovers what the agent actually said
     * so the job's error names the missing prerequisite instead of "no parseable ruling".
     */
    @Test
    void blockedIsAFailedRunAndNotAVerdict() {
        String answer = """
                {"verdict": "blocked", "summary": "the MCP surface refused every call", "citations": []}
                """;

        assertNull(
                BehaviorTriageVerdict.parse(mapper, answer),
                "blocked must never become a ruling — it closes no finding and moves no baseline");
        assertEquals("the MCP surface refused every call", BehaviorTriageVerdict.blockedReason(mapper, answer));
    }

    @Test
    void aRealRulingIsNotBlocked() {
        String answer = """
                {"verdict": "negative", "summary": "the reference window is 40 turns",
                 "citations": [{"path": "window.n_cur", "reason": "1,204 turns"}]}
                """;
        assertNull(BehaviorTriageVerdict.blockedReason(mapper, answer));
        assertNull(BehaviorTriageVerdict.blockedReason(mapper, "not json at all"));
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
     * The {@code --output-format json} envelope whose {@code result} carries the ruling, as JSON text wrapped
     * in the agent's prose or as an object. Missing it records nothing for a run that did rule.
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
     * A script's stdout is the receipt a person re-checks the ruling by, kept up to 4,000 characters and cut
     * with a marker past that. An empty or non-text stdout is no receipt at all.
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
