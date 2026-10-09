// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The lane table has to agree with the two model tables it names: {@link BedrockModelProfile}'s offer
 * list and {@link ModelCatalog}'s entries. Each disagreement is a settings page that lies: a lane with no
 * order has no automatic answer, a default outside its own list is a model the picker cannot show, a
 * model a lane names but its group does not permit renders and then 400s on save, and one the group
 * permits but the lane does not name is missing from the picker.
 */
class LanePriorityTest {

    @ParameterizedTest
    @EnumSource(ModelLane.class)
    void everyLaneHasAProviderOrderWhoseDefaultsAreAmongItsOwnModels(ModelLane lane) {
        var options = LanePriority.of(lane);

        assertFalse(options.isEmpty(), "no provider order declared for lane " + lane);
        for (LanePriority.ProviderOption o : options) {
            assertTrue(
                    o.modelKeys().contains(o.defaultModelKey()),
                    lane + " defaults " + o.provider() + " to " + o.defaultModelKey() + ", outside its own list");
        }
    }

    @ParameterizedTest
    @EnumSource(ModelLane.class)
    void everyLaneNamesExactlyTheModelsItsGroupPermits(ModelLane lane) {
        Set<String> permitted = new LinkedHashSet<>(BedrockModelProfile.offeredFor(lane.group()));
        for (ModelCatalog.CatalogEntry e : ModelCatalog.entries()) {
            boolean fits = lane.group() == LaneGroup.AGENT_VM ? e.agentic() : e.decision();
            if (fits) permitted.add(ModelCatalog.key(e));
        }

        assertEquals(permitted, new LinkedHashSet<>(LanePriority.modelKeys(lane)));
    }
}
