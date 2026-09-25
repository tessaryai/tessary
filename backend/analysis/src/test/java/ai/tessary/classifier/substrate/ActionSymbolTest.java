// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The alphabet keeps a kind's name, normalized, and buckets a retrieval name to its corpus. */
class ActionSymbolTest {

    @Test
    void everyOtherKindKeepsItsName() {
        assertEquals("tool:verify_member", ActionSymbol.of("tool", "verify_member"));
        assertEquals("agent:policy_gpt", ActionSymbol.of("agent", "policy-gpt"));
        assertEquals("retrieval:policy_docs", ActionSymbol.of("retrieval", "policy_docs/2024/s4.pdf"));
    }
}
