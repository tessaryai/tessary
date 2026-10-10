// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.ModelsDevProperties;
import ai.tessary.config.ObserverProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every model a sandbox run can be pointed at is priced in the bundled models.dev copy. A sandbox run is
 * billed at the rates the launcher declares to OpenCode, so a model this check misses books every run
 * unpriced. A failure here means a catalog row names the wrong models.dev id, or models.dev dropped the
 * model: fix the row or remove it, and never relax the check.
 */
class ModelsDevCatalogTest {

    /** A customer's own endpoint and the deployment-supplied provider: no public catalog can know either. */
    private static final Set<ModelProvider> UNKNOWABLE = EnumSet.of(ModelProvider.CUSTOM, ModelProvider.PLATFORM);

    private final ModelsDevRates rates = bundled();

    private static ModelsDevRates bundled() {
        ModelsDevProperties props = new ModelsDevProperties();
        props.setUrl("");
        return new ModelsDevRates(new ObjectMapper(), props);
    }

    @Test
    void everySandboxModelHasAnInputAndAnOutputRateInModelsDev() {
        List<String> unpriced = new ArrayList<>();
        for (ModelCatalog.CatalogEntry e : ModelCatalog.entries()) {
            if (!e.agentic() || UNKNOWABLE.contains(e.provider())) continue;
            check(e.provider() + ":" + e.modelName(), e.provider(), e.modelName(), unpriced);
        }
        for (BedrockModelProfile.ModelDescriptor d : BedrockModelProfile.platformModels()) {
            if (!d.agentic()) continue;
            ModelProvider provider = d.endpoint() == BedrockModelProfile.Endpoint.MANTLE
                    ? ModelProvider.BEDROCK_MANTLE
                    : ModelProvider.BEDROCK;
            check(d.modelKey(), provider, d.inferenceProfileId(), unpriced);
        }
        String deploymentDefault = new ObserverProperties().getAgentic().getModel();
        check("deployment default", ModelProvider.BEDROCK, deploymentDefault, unpriced);

        assertEquals(List.of(), unpriced, "sandbox models the bundled models.dev copy does not price");
    }

    private void check(String label, ModelProvider provider, String modelName, List<String> unpriced) {
        String id = ModelCatalog.modelsDevId(provider, modelName).orElse(null);
        boolean priced = id != null
                && rates.bundledCost(id)
                        .filter(c -> c.input() != null && c.output() != null)
                        .isPresent();
        if (!priced) unpriced.add(label + " (" + id + ")");
    }
}
