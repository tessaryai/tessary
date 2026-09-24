// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedSentenceView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.RetrievedDocumentView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GroundednessAnswersFile}: each answer reaches the agent with its flagged sentences cut from the answer
 * by their offsets and the documents (or the prompt) it was checked against, and the file declares how many of
 * the finding's answers it shows when the budget stops it short. Each case asserts the whole file.
 */
class GroundednessAnswersFileTest {

    private static final String FIRST = "The refund was issued on March 3.";
    private static final String SECOND = "It arrives within two business days by bank transfer.";
    private static final String ANSWER = FIRST + " " + SECOND;
    private static final String DOCUMENT = "Card refunds reach the customer within five to ten business days.";

    private static final String HEADER = "# Flagged answers\n\n"
            + "The answers this finding cites, newest first. For each: the trace and span ids (the"
            + " `get_trace` / `get_span` arguments), the score (P(unsupported) of the strongest"
            + " sentence), each flagged sentence with its score, the question, the answer as it was"
            + " scored, and the documents it was checked against. A flagged sentence is where the model"
            + " saw no support in the documents, not proof that the sentence is wrong.\n";

    /** Everything {@link #stored} renders before its first document's text. */
    private static String storedAnswerHead(int n, String trace) {
        return "\n## " + n + ". trace `" + trace + "` span `sp_" + trace + "`\n\n"
                + "- flagged at 2026-05-04T10:00:00Z, score 0.992\n"
                + "- flagged sentence, score 0.981: \"" + FIRST + "\"\n"
                + "- flagged sentence, score 0.992: \"" + SECOND + "\"\n"
                + "\n### Question\n\n```text\nWhen will my refund arrive?\n```\n"
                + "\n### Answer\n\n```text\n" + ANSWER + "\n```\n"
                + "\n### Documents (1)\n"
                + "\n#### Document 1\n\n```text\n";
    }

    private static String storedAnswer(int n, String trace, String document) {
        return storedAnswerHead(n, trace) + document + "\n```\n";
    }

    @Test
    void aStoredAnswerCarriesItsFlaggedSentencesQuestionAndDocuments() {
        String file = GroundednessAnswersFile.render(new FlaggedAnswerPage(List.of(stored("tr_1", DOCUMENT)), 1, null));

        assertEquals(HEADER + storedAnswer(1, "tr_1", DOCUMENT) + "\nAll 1 flagged answer(s) are shown.\n", file);
    }

    @Test
    void anAnswerCheckedAgainstItsPromptSaysNoDocumentsWereRetrieved() {
        String prompt = "System: card refunds take five to ten business days.";
        FlaggedAnswerView promptChecked = new FlaggedAnswerView(
                "tr_3",
                "sp_3",
                "ses_1",
                "2026-05-04T10:00:00Z",
                0.981,
                null,
                ANSWER,
                List.of(new FlaggedSentenceView(0, FIRST.length(), 0.981)),
                List.of(new RetrievedDocumentView(null, prompt)),
                false,
                true,
                false);

        String file = GroundednessAnswersFile.render(new FlaggedAnswerPage(List.of(promptChecked), 1, null));

        assertEquals(
                HEADER
                        + "\n## 1. trace `tr_3` span `sp_3`\n\n"
                        + "- flagged at 2026-05-04T10:00:00Z, score 0.981\n"
                        + "- flagged sentence, score 0.981: \"" + FIRST + "\"\n"
                        + "\n### Answer\n\n```text\n" + ANSWER + "\n```\n"
                        + "\n### No documents were retrieved; the answer was checked against its prompt\n\n"
                        + "```text\n" + prompt + "\n```\n"
                        + "\nAll 1 flagged answer(s) are shown.\n",
                file);
    }

    @Test
    void anAnswerWhoseTraceAgedOutSaysSoAndKeepsItsScores() {
        FlaggedAnswerView gone = new FlaggedAnswerView(
                "tr_2",
                "sp_2",
                null,
                null,
                0.98,
                null,
                null,
                List.of(new FlaggedSentenceView(0, 20, 0.98)),
                null,
                false,
                false,
                true);

        String file = GroundednessAnswersFile.render(new FlaggedAnswerPage(List.of(gone), 1, null));

        assertEquals(
                HEADER
                        + "\n## 1. trace `tr_2` span `sp_2`\n\n"
                        + "- flagged at an unknown time, score 0.980, since cleared as a false alarm\n"
                        + "- flagged sentence, score 0.980: [0, 20)\n"
                        + "- no longer stored: its trace aged out, so only the ids and scores remain\n"
                        + "\nAll 1 flagged answer(s) are shown.\n",
                file);
    }

    @Test
    void theBudgetKeepsWholeAnswersAndDeclaresTheRest() {
        // A third of the budget each: two answers fit, a third whole one does not.
        String longDocument = "x".repeat(GroundednessAnswersFile.CHAR_BUDGET / 3);
        List<FlaggedAnswerView> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(stored("tr_" + i, longDocument));

        String file = GroundednessAnswersFile.render(new FlaggedAnswerPage(rows, 80, "5"));

        assertEquals(
                HEADER
                        + storedAnswer(1, "tr_0", longDocument)
                        + storedAnswer(2, "tr_1", longDocument)
                        + "\nThe newest 2 of 80 flagged answer(s) are shown. The rest are the `witness` span refs"
                        + " of `get_finding_evidence`; read them with `get_span`.\n",
                file);
    }

    @Test
    void aLoneAnswerOverTheBudgetIsCutAtTheBudgetAndSaysSo() {
        String hugeDocument = "y".repeat(GroundednessAnswersFile.CHAR_BUDGET);
        String beforeDocument = HEADER + storedAnswerHead(1, "tr_1");

        String file =
                GroundednessAnswersFile.render(new FlaggedAnswerPage(List.of(stored("tr_1", hugeDocument)), 1, null));

        assertEquals(
                beforeDocument
                        + "y".repeat(120_000 - beforeDocument.length())
                        + "\n\n[cut at 120000 characters: read the rest of this answer with `get_span`.]\n",
                file);
    }

    @Test
    void aFenceInsideTheTextCannotCloseTheBlock() {
        String file = GroundednessAnswersFile.render(
                new FlaggedAnswerPage(List.of(stored("tr_1", "run ```rm``` first")), 1, null));

        assertEquals(
                HEADER
                        + storedAnswerHead(1, "tr_1").replace("#### Document 1\n\n```text\n", "#### Document 1\n\n")
                        + "````text\nrun ```rm``` first\n````\n"
                        + "\nAll 1 flagged answer(s) are shown.\n",
                file);
    }

    private static FlaggedAnswerView stored(String trace, String document) {
        int second = ANSWER.indexOf(SECOND);
        return new FlaggedAnswerView(
                trace,
                "sp_" + trace,
                "ses_1",
                "2026-05-04T10:00:00Z",
                0.992,
                "When will my refund arrive?",
                ANSWER,
                List.of(
                        new FlaggedSentenceView(0, FIRST.length(), 0.981),
                        new FlaggedSentenceView(second, ANSWER.length(), 0.992)),
                List.of(new RetrievedDocumentView(null, document)),
                true,
                true,
                false);
    }
}
