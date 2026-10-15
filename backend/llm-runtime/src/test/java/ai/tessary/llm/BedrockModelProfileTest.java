// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
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
     * A Bedrock model a lane offers must be a model this table describes, and a sandbox lane may offer
     * only models that can hold the agent's tool loop: an offered key with no profile renders a blank
     * option, and a non-agentic one saves fine and fails every sandbox run. Catalog keys carry a colon;
     * Bedrock keys never do.
     */
    @Test
    void everyOfferedBedrockModelIsAProfileAndTheSandboxLanesOfferOnlyAgenticOnes() {
        for (ModelLane lane : ModelLane.values()) {
            for (String key : LanePriority.modelKeys(lane)) {
                if (key.contains(":")) continue;
                var profile = BedrockModelProfile.find(key);
                assertTrue(profile.isPresent(), lane + " offers " + key + ", which is not a profile");
                if (lane.group() == LaneGroup.AGENT_VM) {
                    assertTrue(profile.get().agentic(), key + " is offered for the sandbox but is not agentic");
                }
            }
        }
    }
}
