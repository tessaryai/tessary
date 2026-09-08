// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** See the class's own javadoc for why this is a fixed fallback rather than a live discovery call. */
class BedrockMantleModelListerTest {

    @Test
    void returnsTheTwoKnownMantleModels_neverEmpty() {
        List<ProviderModel> models = new BedrockMantleModelLister()
                .list(new ResolvedCredential(null, null, "us-east-1", "key", "secret", false));

        assertFalse(models.isEmpty(), "must never regress the picker to empty");
        assertEquals(
                List.of(
                        new ProviderModel("openai.gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI"),
                        new ProviderModel("openai.gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI")),
                models);
    }

    @Test
    void bothKnownModelsCarryASupportedMakerVendor() {
        for (ProviderModel m : new BedrockMantleModelLister()
                .list(new ResolvedCredential(null, null, "us-east-1", null, null, true))) {
            assertEquals("OpenAI", m.vendor(), "both known mantle models are OpenAI's — a D6-supported maker");
        }
    }

    @Test
    void ignoresTheCredentialEntirely_sameFixedListRegardlessOfRegionOrAuthMode() {
        BedrockMantleModelLister lister = new BedrockMantleModelLister();
        List<ProviderModel> viaIamRole = lister.list(new ResolvedCredential(null, null, "eu-west-1", null, null, true));
        List<ProviderModel> viaApiKey = lister.list(new ResolvedCredential(null, null, "us-east-1", "k", "s", false));

        assertEquals(viaIamRole, viaApiKey);
        assertTrue(viaIamRole.size() == 2);
    }
}
