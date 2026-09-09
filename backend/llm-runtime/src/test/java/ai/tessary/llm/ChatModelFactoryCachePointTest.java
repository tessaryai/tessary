// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.ServiceTier;
import dev.langchain4j.model.bedrock.BedrockCachePointPlacement;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

/**
 * Whether a Bedrock judge request carries a prompt-cache breakpoint, and in which structured-output
 * encoding.
 *
 * <p>The amortization: a cache write costs 1.25x the input rate and a read 0.1x, so N graders on a
 * unit cost {@code 1.15 + 0.1N} cached versus {@code N} uncached — break-even at N≈1.28. From two
 * graders up caching wins big, but a unit with a SINGLE grader pays a 25% surcharge to populate a
 * cache nothing ever reads, so those requests must omit the breakpoint.
 */
class ChatModelFactoryCachePointTest {

    private static final StructuredOutput.Spec<Object> SPEC = new StructuredOutput.Spec<>(
            "submit_verdict",
            "Submit your verdict.",
            JsonObjectSchema.builder().addStringProperty("rationale").build(),
            Object.class);

    @Test
    void sharedPrefixEmitsTheCachePointAtTheConfiguredTtl() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(SPEC, true, CacheTTL.VALUE_5_M);
        assertEquals(BedrockCachePointPlacement.AFTER_USER_MESSAGE, p.cachePointPlacement());
        assertEquals(CacheTTL.VALUE_5_M, p.cacheTtl());
    }

    @Test
    void singleGraderUnitOmitsTheCachePoint() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(SPEC, false, CacheTTL.VALUE_5_M);
        assertNull(p.cachePointPlacement(), "one grader never re-reads the prefix; writing it costs 25% extra");
    }

    @Test
    void responseFormatSurvivesOnBothBranches() {
        // Load-bearing: the JSON schema rides in `parameters` because the request builder rejects a
        // separate responseFormat(...) alongside parameters(...). Dropping it on the no-cache branch
        // would silently turn structured verdicts into free text.
        assertNotNull(
                ChatModelFactory.bedrockParams(SPEC, true, CacheTTL.VALUE_5_M).responseFormat());
        assertNotNull(
                ChatModelFactory.bedrockParams(SPEC, false, CacheTTL.VALUE_5_M).responseFormat());
    }

    @Test
    void theOneHourTtlIsCarriedThrough() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(SPEC, true, CacheTTL.VALUE_1_H);
        assertEquals(CacheTTL.VALUE_1_H, p.cacheTtl());
    }

    /**
     * The regression that took Nova 2 Lite to a 100% error rate. A null TTL must still place the cache
     * point but leave {@code cacheTtl} unset, because langchain4j's {@code buildCachePoint} branches
     * ONLY on null to reach its ttl-less {@code DEFAULT_CACHE_POINT} — and Bedrock treats the mere
     * presence of {@code cachePoint.ttl} as the Anthropic-only extended-TTL feature. Asserting the
     * placement alone (as this file used to) passes while every request 400s.
     */
    @Test
    void aNullTtlCachesWithNoTtlFieldAtAll() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(SPEC, true, null);
        assertEquals(
                BedrockCachePointPlacement.AFTER_USER_MESSAGE,
                p.cachePointPlacement(),
                "a model without explicit TTLs still caches — it just cannot be told a duration");
        assertNull(p.cacheTtl(), "sending any ttl to a non-Anthropic Bedrock model is a 400, even its own window");
    }

    @Test
    void toolModeSendsOneForcedToolAndNoResponseFormat() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.TOOL_CALL, true, true, null, ServiceTier.FLEX);

        assertNull(p.responseFormat(), "outputConfig is exactly what a tool-mode model rejects");
        assertEquals(1, p.toolSpecifications().size(), "one tool, so `any` forces this one");
        assertEquals("submit_verdict", p.toolSpecifications().getFirst().name());
        assertEquals(ToolChoice.REQUIRED, p.toolChoice());
        // Tool mode must not disturb the other two decisions riding in the same parameters.
        assertEquals(BedrockCachePointPlacement.AFTER_USER_MESSAGE, p.cachePointPlacement());
        assertNull(p.cacheTtl());
    }

    @Test
    void aModelThatRefusesForcedChoiceFallsBackToAuto() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.TOOL_CALL, false, true, null, ServiceTier.STANDARD);
        assertEquals(ToolChoice.AUTO, p.toolChoice());
        assertTrue(
                p.toolSpecifications().getFirst().description().contains("Submit"),
                "under AUTO the description is the only thing prompting the call, so it must survive");
    }

    @Test
    void nativeModeSendsNoToolConfig() {
        BedrockChatRequestParameters p = ChatModelFactory.bedrockParams(
                SPEC, StructuredOutput.Mode.NATIVE, true, true, CacheTTL.VALUE_5_M, ServiceTier.STANDARD);
        assertNotNull(p.responseFormat());
        assertTrue(
                p.toolSpecifications() == null || p.toolSpecifications().isEmpty(),
                "a native model must not be handed a tool it was never asked to call");
    }
}
