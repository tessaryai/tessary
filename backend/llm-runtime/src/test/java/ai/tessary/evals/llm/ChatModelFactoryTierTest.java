// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.evals.llmspi.ServiceTier;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockServiceTier;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

/**
 * What the Bedrock {@code serviceTier} field carries. Complements
 * {@link ChatModelFactoryCachePointTest}, which covers the cache breakpoint on the same builder.
 */
class ChatModelFactoryTierTest {

    private static final StructuredOutput.Spec<Object> SPEC = new StructuredOutput.Spec<>(
            "submit_verdict",
            "Submit your verdict.",
            JsonObjectSchema.builder().addStringProperty("rationale").build(),
            Object.class);

    @Test
    void flexIsEmittedOnTheRequest() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.NATIVE, true, true, CacheTTL.VALUE_5_M, ServiceTier.FLEX);
        assertEquals(BedrockServiceTier.FLEX, p.serviceTier());
    }

    @Test
    void standardMapsToBedrocksDefaultTier() {
        // STANDARD is Bedrock's DEFAULT — the same thing it assumes when the field is absent — so
        // sending it is a no-op that keeps the request self-describing.
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.NATIVE, true, true, CacheTTL.VALUE_5_M, ServiceTier.STANDARD);
        assertEquals(BedrockServiceTier.DEFAULT, p.serviceTier());
    }

    @Test
    void batchIsNeverPutOnTheWire() {
        // Batch is a separate API with no serviceTier value. If one ever slipped past the settings
        // validator, omitting it is the only safe outcome — inventing a value would 400 the call.
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.NATIVE, true, true, CacheTTL.VALUE_5_M, ServiceTier.BATCH);
        assertNull(p.serviceTier());
    }

    @Test
    void theTierlessOverloadIsStandard() {
        assertEquals(
                BedrockServiceTier.DEFAULT,
                ChatModelFactory.bedrockParams(SPEC, true, CacheTTL.VALUE_5_M).serviceTier());
    }

    @Test
    void tierAndCachePointAreIndependent() {
        // A single-grader unit on Flex must still skip the cache point, and a Flex request must still
        // carry the JSON schema — neither concern may swallow the other.
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.NATIVE, true, false, CacheTTL.VALUE_5_M, ServiceTier.FLEX);
        assertNull(p.cachePointPlacement());
        assertEquals(BedrockServiceTier.FLEX, p.serviceTier());
        assertNotNull(p.responseFormat());
    }

    @Test
    void tierSurvivesToolModeToo() {
        // Flex is only reachable on Nova, which is also the only tool-mode model — so if the
        // structured-output branch ever swallowed the tier, the ONE model that can use Flex would
        // silently bill at Standard and no other test would notice.
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.TOOL_CALL, true, true, null, ServiceTier.FLEX);
        assertEquals(BedrockServiceTier.FLEX, p.serviceTier());
        assertNull(p.responseFormat());
        assertEquals(1, p.toolSpecifications().size());
    }
}
