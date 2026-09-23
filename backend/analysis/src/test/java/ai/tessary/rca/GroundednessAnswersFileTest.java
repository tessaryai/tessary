// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedSentenceView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.RetrievedDocumentView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GroundednessAnswersFile}: each answer reaches the agent with its flagged sentences cut from the answer
 * by their offsets and the documents it was checked against, and the file declares how many of the finding's
 * answers it shows when the budget stops it short.
 */
class GroundednessAnswersFileTest {

    private static final String FIRST = "The refund was issued on March 3.";
    private static final String SECOND = "It arrives within two business days by bank transfer.";
    private static final String ANSWER = FIRST + " " + SECOND;
    private static final String DOCUMENT = "Card refunds reach the customer within five to ten business days.";

    @Test
    void aStoredAnswerCarriesItsFlaggedSentencesQuestionAndDocuments() {
        String file = GroundednessAnswersFile.render(new FlaggedAnswerPage(List.of(stored("tr_1", DOCUMENT)), 1, null));

        assertTrue(file.contains("trace `tr_1` span `sp_tr_1`"), file);
        assertTrue(file.contains("score 0.992"), file);
        assertTrue(file.contains("score 0.992: \"" + SECOND + "\""), "the sentence is cut by its offsets");
        assertTrue(file.contains("score 0.981: \"" + FIRST + "\""), file);
        assertTrue(file.contains("### Question\n\n```text\nWhen will my refund arrive?\n```"), file);
        assertTrue(file.contains("```text\n" + ANSWER + "\n```"), "the answer as it was scored");
        assertTrue(file.contains("### Documents (1)"), file);
        assertTrue(file.contains("#### Document 1\n\n```text\n" + DOCUMENT + "\n```"), file);
        assertTrue(file.contains("All 1 flagged answer(s) are shown."), file);
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

        assertTrue(file.contains("no longer stored"), file);
        assertTrue(file.contains("since cleared as a false alarm"), file);
        assertTrue(file.contains("[0, 20)"), "offsets stand in for the text that is gone");
        assertFalse(file.contains("### Answer"), file);
    }

    @Test
    void theBudgetKeepsWholeAnswersAndDeclaresTheRest() {
        String longDocument = "x".repeat(GroundednessAnswersFile.CHAR_BUDGET / 3);
        List<FlaggedAnswerView> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(stored("tr_" + i, longDocument));

        String file = GroundednessAnswersFile.render(new FlaggedAnswerPage(rows, 80, "5"));

        assertTrue(file.length() <= GroundednessAnswersFile.CHAR_BUDGET, "inside the budget");
        assertTrue(file.contains("The newest 2 of 80 flagged answer(s) are shown."), file);
        assertTrue(file.contains("`get_span`"), "the rest are one read away");
        assertFalse(file.contains("tr_2"), "a third whole answer does not fit");
    }

    @Test
    void aFenceInsideTheTextCannotCloseTheBlock() {
        String file = GroundednessAnswersFile.render(
                new FlaggedAnswerPage(List.of(stored("tr_1", "run ```rm``` first")), 1, null));

        assertTrue(file.contains("````text\nrun ```rm``` first\n````"), file);
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
