// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.LaneGroup;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Amazon Nova 2 Lite (the {@code NOVA} constant below) was removed from {@link
 * BedrockModelProfile#PROFILES} — Amazon is not one of the six supported makers. The constant
 * survives here as a known-removed-model probe: {@code find(NOVA)} must find no profile, so nothing
 * can treat it as agentic.
 */
class BedrockModelProfileTest {

    private static final String HAIKU = "anthropic.claude-haiku-4-5";
    private static final String NOVA = "amazon.nova-2-lite";

    @Test
    void onlyAnthropicModelsCanDriveTheSandboxLanes() {
        // The sandbox lanes need a model that can hold a long tool loop. Nova is a fine grading model and
        // an unrunnable sandbox, so this is a lane pairing rule, not a quality judgement.
        assertTrue(BedrockModelProfile.find(HAIKU).orElseThrow().agentic());
        assertEquals(Optional.empty(), BedrockModelProfile.find(NOVA));
        assertEquals(Optional.empty(), BedrockModelProfile.find("some.unknown-model"), "unknown fails closed");
    }

    /**
     * What a lane group offers must be a model this table describes, and the sandbox group may offer
     * only models that can hold the agent's tool loop: an offered key with no profile renders a blank
     * option, and a non-agentic one saves fine and fails every sandbox run.
     */
    @Test
    void everyOfferedModelIsAProfileAndTheSandboxGroupOffersOnlyAgenticOnes() {
        for (LaneGroup group : LaneGroup.values()) {
            for (String key : BedrockModelProfile.offeredFor(group)) {
                var profile = BedrockModelProfile.find(key);
                assertTrue(profile.isPresent(), group + " offers " + key + ", which is not a profile");
                if (group == LaneGroup.AGENT_VM) {
                    assertTrue(profile.get().agentic(), key + " is offered for the sandbox but is not agentic");
                }
            }
        }
    }

    @Test
    void noKeyFindsNoProfile() {
        // A lane with no stored choice asks with a null key; that is "no model", not a crash.
        assertEquals(Optional.empty(), BedrockModelProfile.find(null));
    }
}
