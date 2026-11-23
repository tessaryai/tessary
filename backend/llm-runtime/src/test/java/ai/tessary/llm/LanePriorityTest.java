// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The lane table has to agree with the two model tables it names: {@link BedrockModelProfile}'s profiles
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

    /** The fix lane permits frontier models instead of agentic ones, so it is checked against that rule. */
    @ParameterizedTest
    @EnumSource(ModelLane.class)
    void everyLaneNamesExactlyTheModelsItsGroupPermits(ModelLane lane) {
        Set<String> permitted = new LinkedHashSet<>();
        for (BedrockModelProfile.ModelDescriptor d : BedrockModelProfile.platformModels()) {
            boolean fits = lane.frontierOnly() ? d.frontier() : lane.group() == LaneGroup.AGENT_VM && d.agentic();
            if (fits) permitted.add(d.modelKey());
        }
        for (ModelCatalog.CatalogEntry e : ModelCatalog.entries()) {
            boolean fits = lane.frontierOnly()
                    ? e.frontier()
                    : lane.group() == LaneGroup.AGENT_VM ? e.agentic() : e.decision();
            if (fits) permitted.add(ModelCatalog.key(e));
        }

        assertEquals(permitted, new LinkedHashSet<>(LanePriority.modelKeys(lane)));
    }

    /**
     * The bug: a model joins the frontier set by accident (a copied row, a flag left on) and starts writing pull
     * requests on a customer's repository. The set is a product decision, so it is pinned here key by key.
     */
    @Test
    void theFrontierSetIsOpusAndSolOnTheirNamedRoutesOnly() {
        Set<String> frontier = new LinkedHashSet<>();
        BedrockModelProfile.platformModels().stream()
                .filter(BedrockModelProfile.ModelDescriptor::frontier)
                .forEach(d -> frontier.add(d.modelKey()));
        ModelCatalog.entries().stream()
                .filter(ModelCatalog.CatalogEntry::frontier)
                .forEach(e -> frontier.add(ModelCatalog.key(e)));

        assertEquals(
                Set.of(
                        "anthropic.claude-opus-5-5",
                        "ANTHROPIC:claude-opus-5-5",
                        "OPENROUTER:anthropic/claude-opus-5.5",
                        "OPENAI:gpt-6.1-sol",
                        "OPENROUTER:openai/gpt-6.1-sol"),
                frontier);
    }

    /** The bug: an org with only a Custom endpoint or the deployment's provider gets a fix model it never chose. */
    @Test
    void theFixLaneOffersNeitherCustomNorThePlatformProvider() {
        Set<ModelProvider> providers = new LinkedHashSet<>();
        LanePriority.of(ModelLane.FIX).forEach(o -> providers.add(o.provider()));

        assertFalse(providers.contains(ModelProvider.CUSTOM));
        assertFalse(providers.contains(ModelProvider.PLATFORM));
    }
}
