// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The abstain decision in front of the entailment head. Every "nothing to check" case here is a real
 * answer from the local corpus that the undecomposed detector fired on.
 */
class VerifiableClaimsTest {

    private static final String REFUND_CONFIRMED = "All set — return RMA-00000 is confirmed and I've processed your "
            + "refund of $999.00 back to your Visa ending 4242.";
    private static final String DENIAL_AFTER_SORRY =
            "Sorry, that item is non-refundable and your order shipped on 3 March.";
    private static final String SHIPPED_AFTER_OPENER =
            "I'm sorry, but your order was already shipped and cannot be cancelled.";

    static Stream<Arguments> answers() {
        return Stream.of(
                // First-person narration without an anchor asserts nothing.
                Arguments.of("I can help with that.", List.of()),
                Arguments.of("Let me take a look for you.", List.of()),
                // With an anchor it is a claim: this is where fabrication hides.
                Arguments.of(REFUND_CONFIRMED, List.of(REFUND_CONFIRMED)),
                Arguments.of(
                        "Happy to help! Refunds take 5–7 business days. Anything else?",
                        List.of("Refunds take 5–7 business days.")),
                Arguments.of("", List.of()),
                Arguments.of("   ", List.of()),
                Arguments.of(
                        "Refunds are issued within 5 business days\nStore credit is offered as an alternative",
                        List.of(
                                "Refunds are issued within 5 business days",
                                "Store credit is offered as an alternative")),
                // `sorry\b.*` under matches() once dropped the denial after the apology; a bare apology still drops.
                Arguments.of(DENIAL_AFTER_SORRY, List.of(DENIAL_AFTER_SORRY)),
                Arguments.of("Sorry!", List.of()),
                Arguments.of("I'm sorry.", List.of()),
                // A self-report about the agent's own actions: no document can entail it.
                Arguments.of("I've escalated this to our returns team.", List.of()),
                Arguments.of("I have cancelled your subscription.", List.of()),
                Arguments.of("I'm looking into that for you.", List.of()),
                Arguments.of("I'm not finding any orders on your account.", List.of()),
                // META is a prefix match, so an opener once swallowed the assertion after it...
                Arguments.of(SHIPPED_AFTER_OPENER, List.of(SHIPPED_AFTER_OPENER)),
                // ...but a hedge introducing another hedge still drops.
                Arguments.of("I'm looking into that, please hold.", List.of()),
                Arguments.of("I can check, one moment.", List.of()),
                // An honest refusal: `i can ` does not match `I can't`, which let the clause-break rule admit these.
                Arguments.of(
                        "I'm sorry, but I don't have information about that in the provided documents.", List.of()),
                Arguments.of("I'm sorry, but I can't help with that request.", List.of()),
                Arguments.of("I'm sorry, but I cannot process that refund.", List.of()),
                Arguments.of("I'm afraid I won't be able to do that.", List.of()),
                // KNOWN GAP: contradicts the policy outright, but has no digit or proper noun. Pinned so the gap
                // stays visible; it is the case that would justify a learned decomposer.
                Arguments.of("Damaged items are non-refundable.", List.of()));
    }

    @ParameterizedTest
    @MethodSource("answers")
    void keepsOnlyTheSentencesAnEntailmentCheckCanJudge(String answer, List<String> claims) {
        assertEquals(claims, VerifiableClaims.of(answer));
    }

    @Test
    void aNullAnswerYieldsNothingRatherThanThrowing() {
        assertEquals(List.of(), VerifiableClaims.of(null));
    }

    @Test
    @DisplayName("a hedge chain cannot exhaust the stack — model output is uncapped")
    void longHedgeChainIsBoundedAndStillDrops() {
        // 50,000 repetitions once raised StackOverflowError, and ClassifierWorker catches Exception, not Error,
        // so it killed the whole sweep.
        String chain = "I'm sorry, ".repeat(50_000) + "please hold.";

        assertEquals(List.of(), VerifiableClaims.of(chain), "a hedge chain still asserts nothing, however long");
    }
}
