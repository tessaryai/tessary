// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** {@link ClassifierMethodCard#forClassifier} for a key this test knows carries a card. */
    private static String cardOf(String classifierKey) {
        String card = ClassifierMethodCard.forClassifier(classifierKey);
        assertNotNull(card, classifierKey + " is a built-in classifier and must carry a card");
        return card;
    }
}
