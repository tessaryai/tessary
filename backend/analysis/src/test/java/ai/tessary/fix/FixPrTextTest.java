// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.fix.FixAgent.FixAnswer;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The words a reviewer reads on the pull request, pinned whole: what happened first, the evidence per cause, the
 * change, every check with its result, the link back to the case, and that Tessary never merges. The bugs: a body that
 * hides a check that did not run, a cause with no evidence count, a dead link where the deployment has no address, and
 * an update that forgets what the branch's earlier commits fix.
 */
class FixPrTextTest {

    private static FixableCause cause(String title, String path, List<String> traces, List<String> sessions) {
        return FixableCause.of(List.of(new Cause(
                        title,
                        Cause.HIGH,
                        "standing",
                        "code",
                        null,
                        "Every timeout became a failed answer.",
                        null,
                        new Attribution(null, path, null, null),
                        traces,
                        sessions,
                        traces.size(),
                        null)))
                .get(0);
    }

    private static final FixAnswer ANSWER = new FixAnswer(
            true,
            "Retry the search tool on a timeout",
            "The search call now retries twice on a timeout.",
            List.of("code:src/agent.py"),
            List.of(
                    new FixCheck("pytest tests/test_agent.py", FixCheck.PASSED, null),
                    new FixCheck("make e2e", FixCheck.NOT_RUN, "It needs a browser.")),
            null);

    @Test
    void aNewPullRequestLeadsWithTheCaseThenEvidenceChangeChecksAndLink() {
        FixPrText.Facts facts = new FixPrText.Facts(
                "C-7",
                "Search answers fail more often.",
                "https://app.example/orgs/acme/projects/agent/cases/case-1",
                "Search fails on timeouts because nothing retries it.",
                List.of(
                        cause("The search tool is never retried", "src/agent.py", List.of("t1", "t2"), List.of()),
                        cause("Sessions drop the retry flag", "src/session.py", List.of(), List.of("s1"))),
                List.of(),
                ANSWER);

        assertEquals("Retry the search tool on a timeout", FixPrText.title(facts));
        assertEquals("""
                Tessary opened this pull request to fix case C-7: Search answers fail more often.

                ## What happened

                Search fails on timeouts because nothing retries it.

                ## Causes

                - The search tool is never retried (`src/agent.py`). Every timeout became a failed answer. Seen in 2 flagged traces.
                - Sessions drop the retry flag (`src/session.py`). Every timeout became a failed answer. Seen in 1 flagged session.

                ## The change

                The search call now retries twice on a timeout.

                ## Checks

                - `pytest tests/test_agent.py`: passed
                - `make e2e`: not run. It needs a browser.

                [Open case C-7 in Tessary](https://app.example/orgs/acme/projects/agent/cases/case-1).

                Tessary does not merge this pull request. Review it like any other change.
                """, FixPrText.body(facts));
        assertEquals(
                "Retry the search tool on a timeout\n\nThe search call now retries twice on a timeout.\n\nCase: C-7\n"
                        + "Causes: code:src/agent.py, code:src/session.py\n",
                FixPrText.commitMessage(facts));
    }

    @Test
    void anUpdateWithNoLinkNoSummaryAndNoChecksSaysWhatEarlierCommitsFix() {
        FixAnswer bare = new FixAnswer(true, " ", "It retries.", List.of(), List.of(), null);
        FixPrText.Facts facts = new FixPrText.Facts(
                "C-7",
                "Search answers fail",
                null,
                null,
                List.of(cause("One trace", "src/agent.py", List.of("t1"), List.of())),
                List.of("prompt:prompts/a.md", "tool:tools/search.py"),
                bare);

        assertEquals("Fix C-7: One trace", FixPrText.title(facts));
        assertEquals("""
                Tessary opened this pull request to fix case C-7: Search answers fail.

                ## Causes

                - One trace (`src/agent.py`). Every timeout became a failed answer. Seen in 1 flagged trace.

                Earlier commits on this branch fix `prompt:prompts/a.md`, `tool:tools/search.py`.

                ## The change

                It retries.

                ## Checks

                The agent ran no checks. Your CI is the gate.

                Tessary does not merge this pull request. Review it like any other change.
                """, FixPrText.body(facts));
    }

    @Test
    void aCauseWithNoCitedRowsAndNoMechanismStillReadsAsACause() {
        FixableCause quiet = FixableCause.of(List.of(new Cause(
                        "Quiet",
                        Cause.HIGH,
                        null,
                        "data",
                        null,
                        null,
                        null,
                        new Attribution(null, "data/x.csv", null, null),
                        List.of(),
                        List.of(),
                        0,
                        null)))
                .get(0);
        FixPrText.Facts facts = new FixPrText.Facts("C-1", "T", null, " ", List.of(quiet), List.of(), ANSWER);

        assertEquals("""
                Tessary opened this pull request to fix case C-1: T.

                ## Causes

                - Quiet (`data/x.csv`). Seen in the case's flagged rows.

                ## The change

                The search call now retries twice on a timeout.

                ## Checks

                - `pytest tests/test_agent.py`: passed
                - `make e2e`: not run. It needs a browser.

                Tessary does not merge this pull request. Review it like any other change.
                """, FixPrText.body(facts));
    }

    /**
     * The words come from model output over production traces: an {@code @name} in them must not notify anyone on
     * GitHub, even after a slash or a dot, and a title must stay one line. An email address keeps its {@code @}.
     */
    @Test
    void modelTextNeverMentionsAnyoneAndTheTitleStaysOneLine() {
        FixAnswer loud = new FixAnswer(
                true,
                "Retry the search tool\nso @octocat sees it",
                "Ask @acme/security or mail ops@example.com; see.@octocat and x/@octocat.",
                List.of("code:src/agent.py"),
                List.of(),
                null);
        FixPrText.Facts facts = new FixPrText.Facts(
                "C-7",
                "Search answers fail more often.",
                null,
                "Ping @everyone now.",
                List.of(cause("The search tool is never retried", "src/agent.py", List.of("t1"), List.of())),
                List.of(),
                loud);

        assertEquals("Retry the search tool so @\u200Boctocat sees it", FixPrText.title(facts));
        String body = FixPrText.body(facts);
        assertTrue(body.contains("Ping @\u200Beveryone now."), body);
        assertTrue(
                body.contains(
                        "Ask @\u200Bacme/security or mail ops@example.com; see.@\u200Boctocat and x/@\u200Boctocat."),
                body);
        assertTrue(FixPrText.commitMessage(facts)
                .startsWith("Retry the search tool so @\u200Boctocat sees it\n\nAsk @\u200Bacme"));
        assertEquals(
                FixPrText.TITLE_MAX,
                FixPrText.title(new FixPrText.Facts(
                                "C-7",
                                "t",
                                null,
                                null,
                                facts.causes(),
                                List.of(),
                                new FixAnswer(true, "x".repeat(300), "w", List.of(), List.of(), null)))
                        .length());
    }
}
