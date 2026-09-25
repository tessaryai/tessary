// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Amazon Nova 2 Lite (the {@code NOVA} constant below) was removed from {@link
 * BedrockModelProfile#PROFILES} — Amazon is not one of the six supported makers. The constant
 * survives here as a known-removed-model probe: {@code isAgentic(NOVA)} must fail closed for a model
 * with no profile.
 */
class BedrockModelProfileTest {

    private static final String HAIKU = "anthropic.claude-haiku-4-5";
    private static final String NOVA = "amazon.nova-2-lite";

    @Test
    void onlyAnthropicModelsCanDriveTheSandboxLanes() {
        // The sandbox lanes need a model that can hold a long tool loop. Nova is a fine grading model and
        // an unrunnable sandbox, so this is a lane pairing rule, not a quality judgement.
        assertTrue(BedrockModelProfile.isAgentic(HAIKU));
        assertFalse(BedrockModelProfile.isAgentic(NOVA));
        assertFalse(BedrockModelProfile.isAgentic("some.unknown-model"), "unknown fails closed");
    }
}
