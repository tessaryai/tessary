// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The abstain decision in front of the entailment head. Every "nothing to check" case here is a real
 * answer from the local corpus that the undecomposed detector fired on.
 */
class VerifiableClaimsTest {

    @Test
    @DisplayName("a pleasantry asserts nothing and yields no claim")
    void pleasantryYieldsNothing() {
        assertEquals(List.of(), VerifiableClaims.of("You're welcome — anything else I can help with?"));
        assertEquals(List.of(), VerifiableClaims.of("k."));
        assertEquals(List.of(), VerifiableClaims.of("Thanks!"));
    }

    @Test
    @DisplayName("a question back to the user is not a claim, however long")
    void questionsYieldNothing() {
        assertEquals(
                List.of(),
                VerifiableClaims.of("Which order are you referring to? Please provide an order number "
                        + "and describe the item."));
    }

    @Test
    @DisplayName("first-person narration without an anchor is not a claim")
    void unanchoredMetaYieldsNothing() {
        assertEquals(List.of(), VerifiableClaims.of("I can help with that."));
        assertEquals(List.of(), VerifiableClaims.of("Let me take a look for you."));
    }

    @Test
    @DisplayName("first-person narration WITH an anchor is a claim — this is where fabrication hides")
    void anchoredMetaIsAClaim() {
        List<String> claims =
                VerifiableClaims.of("All set — return RMA-00000 is confirmed and I've processed your refund of $999.00 "
                        + "back to your Visa ending 4242.");
        assertEquals(1, claims.size());
        assertTrue(claims.get(0).contains("RMA-00000"));
    }

    @Test
    @DisplayName("a factual answer splits into its sentences")
    void factualAnswerSplits() {
        List<String> claims =
                VerifiableClaims.of("Refunds land on your original payment method within 5–7 business days. "
                        + "Store credit is available instantly as an alternative.");
        assertEquals(2, claims.size());
        assertTrue(claims.get(0).startsWith("Refunds land"));
        assertTrue(claims.get(1).startsWith("Store credit"));
    }

    @Test
    @DisplayName("a mixed answer keeps only the checkable sentence")
    void mixedAnswerKeepsOnlyTheAssertion() {
        List<String> claims = VerifiableClaims.of("Happy to help! Refunds take 5–7 business days. Anything else?");
        assertEquals(List.of("Refunds take 5–7 business days."), claims);
    }

    @Test
    @DisplayName("blank and null answers yield nothing rather than throwing")
    void blankIsEmpty() {
        assertEquals(List.of(), VerifiableClaims.of(""));
        assertEquals(List.of(), VerifiableClaims.of("   "));
        assertEquals(List.of(), VerifiableClaims.of(null));
    }

    @Test
    @DisplayName("newlines separate sentences even without terminating punctuation")
    void newlinesSplit() {
        List<String> claims = VerifiableClaims.of(
                "Refunds are issued within 5 business days\nStore credit is offered as an alternative");
        assertEquals(2, claims.size());
    }

    @Test
    @DisplayName("an apology is a common PREFIX to a factual denial, and the denial survives it")
    void apologyPrefixDoesNotSwallowTheAssertion() {
        // `sorry\b.*` under matches() dropped the whole sentence after the word, silencing exactly the
        // assertions worth checking — a denial is usually delivered with an apology in front of it.
        List<String> claims =
                VerifiableClaims.of("Sorry, that item is non-refundable and your order shipped on 3 March.");
        assertEquals(1, claims.size());
        assertTrue(claims.get(0).contains("non-refundable"));
        // a bare apology still asserts nothing
        assertEquals(List.of(), VerifiableClaims.of("Sorry!"));
        assertEquals(List.of(), VerifiableClaims.of("I'm sorry."));
    }

    @Test
    @DisplayName("a self-report about the agent's own actions is out of scope, not a claim")
    void selfReportedActionIsNotAClaim() {
        // No retrieved document can entail "I've escalated this" — the sentence is about the agent, not
        // about the source material. Admitting it does not catch fabricated actions; it fires
        // groundedness on every honest one. Same argument that put tool results out of scope.
        assertEquals(List.of(), VerifiableClaims.of("I've escalated this to our returns team."));
        assertEquals(List.of(), VerifiableClaims.of("I have cancelled your subscription."));
        assertEquals(List.of(), VerifiableClaims.of("I'm looking into that for you."));
        assertEquals(List.of(), VerifiableClaims.of("I can help with that."));
        assertEquals(List.of(), VerifiableClaims.of("I'm not finding any orders on your account."));
    }

    @Test
    @DisplayName("a first-person opener does not swallow the assertion that follows it")
    void firstPersonOpenerDoesNotSwallowTheClause() {
        // The sibling of the `sorry\b.*` swallow, one pattern over: META is a prefix match, so an
        // apology or hedge in front of a real assertion took the whole sentence with it.
        List<String> claims =
                VerifiableClaims.of("I'm sorry, but your order was already shipped and cannot be cancelled.");
        assertEquals(1, claims.size(), "the assertion about the order survives the apology in front of it");

        // ...but only when what follows stands on its own. A hedge introducing another hedge still drops.
        assertEquals(List.of(), VerifiableClaims.of("I'm looking into that, please hold."));
        assertEquals(List.of(), VerifiableClaims.of("I can check, one moment."));
    }

    @Test
    @DisplayName("an honest refusal is not a claim, contracted or not")
    void refusalsAreNotClaims() {
        // The canonical grounded abstention. No retrieved passage can entail it, so scoring it is the
        // exact false positive this class exists to remove — and `i can ` does not match `I can't`,
        // which let the clause-break rule admit these.
        assertEquals(
                List.of(),
                VerifiableClaims.of("I'm sorry, but I don't have information about that in the provided documents."));
        assertEquals(List.of(), VerifiableClaims.of("I'm sorry, but I can't help with that request."));
        assertEquals(List.of(), VerifiableClaims.of("I'm sorry, but I cannot process that refund."));
        assertEquals(List.of(), VerifiableClaims.of("I'm afraid I won't be able to do that."));
        // and the assertion-bearing sibling still survives its apology
        assertEquals(
                1,
                VerifiableClaims.of("I'm sorry, but your order was already shipped and cannot be cancelled.")
                        .size());
    }

    @Test
    @DisplayName("a hedge chain cannot exhaust the stack — model output is uncapped")
    void longHedgeChainIsBoundedAndStillDrops() {
        // The clause recursion terminated but its depth was the sentence's clause count, and the
        // sentence is model output: 5,000 repetitions took ~2s and 50,000 raised StackOverflowError.
        // ClassifierWorker catches Exception, not Error, so that killed the whole sweep.
        String chain = "I'm sorry, ".repeat(50_000) + "please hold.";
        long start = System.nanoTime();
        List<String> claims = VerifiableClaims.of(chain);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(List.of(), claims, "a hedge chain still asserts nothing, however long");
        assertTrue(elapsedMs < 2_000, "bounded depth keeps this linear, not quadratic; took " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("KNOWN GAP: a short flat assertion with no anchor is dropped")
    void shortFlatAssertionIsDropped() {
        // "Damaged items are non-refundable." contradicts the retrieved policy outright, and this
        // deterministic tier cannot see it: no digit, no proper noun, three long words. It is caught
        // today only because the answer's NEXT sentence survives. Pinned so the gap is visible rather
        // than discovered again from a missed finding — this is the case that justifies a learned
        // decomposer if one is ever built.
        assertEquals(List.of(), VerifiableClaims.of("Damaged items are non-refundable."));
    }
}
