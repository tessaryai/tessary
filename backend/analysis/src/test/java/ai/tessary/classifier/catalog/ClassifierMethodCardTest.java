// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassifierMethodCard}'s enforcement. Cards do not live on {@code ClassifierModelModule}, so the compiler
 * cannot require one; add a module without a card and the first test fails, naming it. A missing card looks exactly
 * like an unneeded one.
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

    /** A user-authored classifier gets no fabricated card. */
    @Test
    void anUnknownClassifierGetsNoCard() {
        assertNull(ClassifierMethodCard.forClassifier("some-user-authored-thing"));
        assertNull(ClassifierMethodCard.forClassifier(null));
        assertNull(ClassifierMethodCard.forClassifier(""));
    }

    /**
     * A card never points at another card: one is delivered per run as {@code dossier/method.md}. Duration and cost
     * drift share one card that names both.
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
     * Every card says where the claim's numbers are, since a ruling must cite the {@code get_finding} fields it rests
     * on.
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
