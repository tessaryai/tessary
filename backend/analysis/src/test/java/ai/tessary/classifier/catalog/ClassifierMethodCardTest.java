// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

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

    /** The armed-signal family shares one card, rendered with the key of the classifier that fired. */
    @Test
    void anArmedSignalCardNamesItsOwnClassifier() {
        String card = ClassifierMethodCard.forClassifier(BuiltInDetector.Kind.SECRET_LEAK);
        assertNotNull(card);
        assertTrue(card.contains(BuiltInDetector.Kind.SECRET_LEAK), "the shared card is rendered with its own key");
    }
}
