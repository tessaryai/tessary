// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link ClassifierMethodCard}, which is the enforcement its own javadoc promises.
 *
 * <p>The cards deliberately do NOT live on {@code ClassifierModelModule}, so the compiler cannot make a
 * new classifier supply one. This does instead: add a module without a card and the first test fails,
 * naming the key. Without it, a new detector would ship findings whose evidence roles no agent has been
 * told how to read — and the failure mode of that is silent, because a missing card looks exactly like a
 * classifier whose card simply was not needed.
 */
class ClassifierMethodCardTest {

    @Test
    void everyBuiltInClassifierHasACard() {
        for (ClassifierModelModule module : BuiltInClassifierCatalog.MODULES) {
            String card = ClassifierMethodCard.forClassifier(module.key());
            assertNotNull(
                    card,
                    "classifier '" + module.key() + "' has no method card, so an agent auditing its findings is"
                            + " left guessing what its evidence roles mean");
            assertTrue(
                    card.contains("**Absent roles**"),
                    "the card for '" + module.key() + "' never says what a MISSING role signifies, which is the"
                            + " one question the shared role vocabulary cannot answer on its own");
        }
    }

    /**
     * A user-authored classifier has no card and must not be given a fabricated one: its method is
     * whatever its author configured, and inventing a description of it would be worse than silence.
     */
    @Test
    void anUnknownClassifierGetsNoCard() {
        assertNull(ClassifierMethodCard.forClassifier("some-user-authored-thing"));
        assertNull(ClassifierMethodCard.forClassifier(null));
        assertNull(ClassifierMethodCard.forClassifier(""));
    }

    /** The armed-signal family shares one card, rendered with the key of the classifier that fired. */
    @Test
    void anArmedSignalCardNamesItsOwnClassifier() {
        String card = cardOf(BuiltInDetector.Kind.GROUNDEDNESS);
        assertTrue(card.contains(BuiltInDetector.Kind.GROUNDEDNESS), "the shared card is rendered with its own key");
    }

    /**
     * Every card carries a section per alarm kind the classifier can file — the text that used to be
     * {@code causeExplanation} in {@code finding.md}, now the one place a cause's meaning lives.
     */
    @Test
    void everyCardCarriesASectionForEachCauseItFiles() {
        assertTrue(cardOf(BuiltInDetector.Kind.TOOL_ERROR).contains("### Cause: `rate_shift`"));
        assertTrue(cardOf(BuiltInDetector.Kind.DURATION_DRIFT).contains("### Cause: `distribution_shift`"));
        assertTrue(cardOf(BuiltInDetector.Kind.COST_DRIFT).contains("### Cause: `distribution_shift`"));
        assertTrue(cardOf(BuiltInDetector.Kind.MALFORMED_OUTPUT).contains("### Cause: `malformed_rate`"));
        assertTrue(cardOf(BuiltInDetector.Kind.SECRET_LEAK).contains("### Cause: `armed_window`"));
        assertTrue(cardOf(BuiltInDetector.Kind.GROUNDEDNESS).contains("### Cause: `armed_window`"));
        assertTrue(cardOf(BuiltInDetector.Kind.FRUSTRATION).contains("### Cause: `frustration_rate`"));

        String behaviorDrift = cardOf(BuiltInDetector.Kind.BEHAVIOR_DRIFT);
        assertTrue(behaviorDrift.contains("### Cause: `omission`"));
        assertTrue(behaviorDrift.contains("### Cause: `novelty`"));
        assertTrue(behaviorDrift.contains("### Cause: `surprisal`"));

        String sop = cardOf(BuiltInDetector.Kind.SOP_CONFORMANCE);
        assertTrue(sop.contains("### Kind: `drift`"));
        assertTrue(sop.contains("### Kind: `baseline`"));
    }

    /** No card names the retired {@code state.json} or asks the agent to recompute the detector's numbers. */
    @Test
    void noCardNamesStateJsonOrAsksForRecomputation() {
        for (String key : List.of(
                BuiltInDetector.Kind.TOOL_ERROR,
                BuiltInDetector.Kind.DURATION_DRIFT,
                BuiltInDetector.Kind.COST_DRIFT,
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                BuiltInDetector.Kind.SOP_CONFORMANCE,
                BuiltInDetector.Kind.SECRET_LEAK,
                BuiltInDetector.Kind.MALFORMED_OUTPUT,
                BuiltInDetector.Kind.FRUSTRATION)) {
            String card = cardOf(key);
            assertFalse(card.contains("state.json"), key + "'s card still names the retired dossier file");
            assertFalse(card.contains("recompute"), key + "'s card still asks the agent to recompute a number");
        }
    }

    /**
     * A card never sends the reader to another classifier's card. Exactly one card is delivered per run,
     * as {@code dossier/method.md}, so "for the same reason as tool_error" points at prose the agent does
     * not have and cannot get. Duration and cost drift are the one legitimate pair: they share a card,
     * and it names both.
     */
    @Test
    void noCardPointsAtAnotherClassifiersCard() {
        for (ClassifierModelModule module : BuiltInClassifierCatalog.MODULES) {
            String card = cardOf(module.key());
            for (ClassifierModelModule other : BuiltInClassifierCatalog.MODULES) {
                if (other.key().equals(module.key())) continue;
                if (card.equals(cardOf(other.key()))) continue; // one card, two keys
                assertFalse(
                        card.contains(other.key()),
                        module.key() + "'s card names '" + other.key() + "', whose card the agent reading this"
                                + " one never receives");
            }
        }
    }

    /**
     * Every card says where the claim's numbers are, including the two whose answer is that there is no
     * block to read. The system prompt requires a ruling to cite the {@code get_finding} fields it rests
     * on, so a card that never names them asks for a citation it has not made possible.
     */
    @Test
    void everyCardSaysWhereTheClaimsNumbersAre() {
        for (ClassifierModelModule module : BuiltInClassifierCatalog.MODULES) {
            assertTrue(
                    cardOf(module.key()).contains("**The claim\'s numbers**"),
                    module.key() + "'s card never says where in get_finding its numbers sit");
        }
    }

    /**
     * The `:pinned` reference is set automatically, not by a person, except when a person moves it with
     * <em>Legitimate, absorb</em> — the card must not tell the agent the opposite.
     */
    @Test
    void thePinnedCardDoesNotClaimAPersonSetTheReferenceByDefault() {
        String card = cardOf(BuiltInDetector.Kind.DURATION_DRIFT);
        assertFalse(card.contains("a person pinned"), "the reference is set automatically by default, not by a person");
        assertTrue(
                card.contains("Legitimate, absorb"), "the card must name the one way a person DOES move the reference");
    }

    /** {@link ClassifierMethodCard#forClassifier} for a key this test knows carries a card. */
    private static String cardOf(String classifierKey) {
        String card = ClassifierMethodCard.forClassifier(classifierKey);
        assertNotNull(card, classifierKey + " is a built-in classifier and must carry a card");
        return card;
    }
}
