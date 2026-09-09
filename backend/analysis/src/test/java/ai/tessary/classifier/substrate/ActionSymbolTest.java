// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The alphabet's two load-bearing rules: an LLM span's name is discarded (§2.1), and the symbols the
 * assembler synthesises are exempt from the rare-name floor (§2.2).
 */
class ActionSymbolTest {

    @Test
    void llmSpanNameIsDiscardedHoweverTheProducerNamedIt() {
        // Producers name the LLM span after the batch it requested. Those are descriptions of the
        // NEXT actions, not actions; carrying them recorded 37 real batches as 68 symbols and put a
        // concurrent batch's emission order inside the alphabet.
        assertEquals(ActionSymbol.LLM_ANSWER, ActionSymbol.of("llm", "llm → answer", false));
        assertEquals(ActionSymbol.LLM_ANSWER, ActionSymbol.of("llm", "llm → verify_member, get_policy", false));
        assertEquals(ActionSymbol.LLM_ANSWER, ActionSymbol.of("llm", "llm → get_policy, verify_member", false));
        assertEquals(ActionSymbol.LLM_ANSWER, ActionSymbol.of("llm", null, false));
    }

    @Test
    void reorderingABatchNoLongerMintsANewSymbol() {
        // The exact production pair: 31 sightings one way, 18 the other, two alphabet entries.
        assertEquals(
                ActionSymbol.of("llm", "llm → verify_member, get_policy, check_hospital", false),
                ActionSymbol.of("llm", "llm → verify_member, check_hospital, get_policy", false));
    }

    @Test
    void batchWidthNoLongerCollapsesThroughTheTrailingIdStrip() {
        // normalizeName strips a trailing "+1"/"+2", so four-, five- and six-tool batches merged into
        // one symbol while REORDERINGS split into many. Discarding the name removes both errors.
        assertEquals(
                ActionSymbol.of("llm", "llm → verify_member, get_exclusions, get_waiting_periods +1", false),
                ActionSymbol.of("llm", "llm → verify_member, get_exclusions, get_waiting_periods +3", false));
    }

    @Test
    void aFailedLlmCallKeepsItsErrorFlag() {
        assertEquals(ActionSymbol.LLM_ANSWER + ":err", ActionSymbol.of("llm", "llm → answer", true));
    }

    @Test
    void everyOtherKindKeepsItsName() {
        assertEquals("tool:verify_member", ActionSymbol.of("tool", "verify_member", false));
        assertEquals("tool:verify_member:err", ActionSymbol.of("tool", "verify_member", true));
        assertEquals("agent:policy_gpt", ActionSymbol.of("agent", "policy-gpt", false));
        assertEquals("retrieval:policy_docs", ActionSymbol.of("retrieval", "policy_docs/2024/s4.pdf", false));
    }

    @Test
    void forkAndJoinAreStructuralAndPaddingStillIs() {
        assertTrue(ActionSymbol.isStructural(ActionSymbol.START));
        assertTrue(ActionSymbol.isStructural(ActionSymbol.END));
        assertTrue(ActionSymbol.isStructural(ActionSymbol.JOIN));
        assertTrue(ActionSymbol.isStructural(ActionSymbol.fork(2)));
        assertTrue(ActionSymbol.isStructural(ActionSymbol.fork(17)));
        assertFalse(ActionSymbol.isStructural("tool:verify_member"));
        assertFalse(ActionSymbol.isStructural(ActionSymbol.LLM_ANSWER));
    }

    @Test
    void aWideBatchIsNeverCollapsedToRareBecauseWideBatchesAreUncommon() {
        // fork:6 is legitimately infrequent. Letting the floor collapse it to `__rare__` would erase
        // the width and merge fan-outs of different degree — the loss the floor exists to prevent.
        List<String> corpus = new java.util.ArrayList<>();
        for (int i = 0; i < 5_000; i++) corpus.add("tool:get_policy");
        corpus.add(ActionSymbol.fork(6));
        corpus.add(ActionSymbol.JOIN);
        corpus.add("tool:once_only");

        Set<String> rare = ActionSymbol.rareSymbols(corpus, 0.001);
        assertTrue(rare.contains("tool:once_only"), "a genuinely rare NAME still floors");
        assertFalse(rare.contains(ActionSymbol.fork(6)));
        assertFalse(rare.contains(ActionSymbol.JOIN));

        List<String> applied =
                ActionSymbol.applyRareFloor(List.of(ActionSymbol.fork(6), "tool:once_only", ActionSymbol.JOIN), rare);
        assertEquals(List.of(ActionSymbol.fork(6), "tool:__rare__", ActionSymbol.JOIN), applied);
    }
}
