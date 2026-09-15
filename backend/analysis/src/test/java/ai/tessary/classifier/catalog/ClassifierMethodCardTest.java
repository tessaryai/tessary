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
        String card = cardOf(BuiltInDetector.Kind.FRUSTRATION);
        assertTrue(card.contains(BuiltInDetector.Kind.FRUSTRATION), "the shared card is rendered with its own key");
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
        assertTrue(cardOf(BuiltInDetector.Kind.FRUSTRATION).contains("### Cause: `armed_window`"));

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
                BuiltInDetector.Kind.MALFORMED_OUTPUT)) {
            String card = cardOf(key);
            assertFalse(card.contains("state.json"), key + "'s card still names the retired dossier file");
            assertFalse(card.contains("recompute"), key + "'s card still asks the agent to recompute a number");
        }
    }

    /** {@link ClassifierMethodCard#forClassifier} for a key this test knows carries a card. */
    private static String cardOf(String classifierKey) {
        String card = ClassifierMethodCard.forClassifier(classifierKey);
        assertNotNull(card, classifierKey + " is a built-in classifier and must carry a card");
        return card;
    }
}
