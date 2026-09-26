// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.LaneGroup;
import org.junit.jupiter.api.Test;

/**
 * Amazon Nova 2 Lite (the {@code NOVA} constant below) was removed from {@link
 * BedrockModelProfile#PROFILES} — Amazon is not one of the six supported makers. The constant
 * survives here as a known-removed-model probe: {@code find(NOVA)} must find no profile, so nothing
 * can treat it as agentic.
 */
class BedrockModelProfileTest {
    private static final String NOVA = "amazon.nova-2-lite";

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
}
