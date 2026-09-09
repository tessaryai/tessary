// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.ServiceTier;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

/**
 * The capability matrix is transcribed from AWS model cards, and getting it wrong is a hard Bedrock
 * error rather than a degradation — so these tests pin the specific facts that differ BETWEEN two
 * platform models. A test that only checked "Sonnet has a 1,024-token cache floor" would pass even if
 * the matrix accidentally said Haiku's floor was the same.
 *
 * <p>Amazon Nova 2 Lite (the {@code NOVA} constant below) was removed from {@link
 * BedrockModelProfile#PROFILES} — Amazon is not one of the six supported makers. The
 * constant survives here only because two tests use it as a known-removed-model probe: {@code
 * onlyAnthropicModelsCanDriveTheSandboxLanes} asserts {@code isAgentic(NOVA) == false} (fails closed
 * for a model with no profile), and {@code normalizeKeyStripsRegionScopeAndVersionSuffixAndNothingElse}
 * checks pure string-normalization logic unrelated to {@code PROFILES} membership. Every other Nova
 * test — the ones that pinned a capability FACT about Nova — is gone below, either deleted (where no
 * surviving model shares the trait) or rewritten against Haiku/Sonnet/Luna (where a real contrast
 * still exists).
 */
class BedrockModelProfileTest {

    private static final String HAIKU = "anthropic.claude-haiku-4-5";
    private static final String SONNET = "anthropic.claude-sonnet-5";
    private static final String LUNA = "openai.gpt-5.6-luna";
    private static final String NOVA = "amazon.nova-2-lite";

    @Test
    void haikuIsStandardOnlyOnBedrock() {
        // Claude Haiku 4.5's Bedrock model card lists Standard + Reserved only. Sending
        // serviceTier: flex against it is a 400, which is the whole reason for this matrix.
        assertTrue(BedrockModelProfile.supports(HAIKU, ServiceTier.STANDARD));
        assertFalse(BedrockModelProfile.supports(HAIKU, ServiceTier.FLEX), "Haiku 4.5 has no Flex tier on Bedrock");
        assertFalse(BedrockModelProfile.supports(HAIKU, ServiceTier.PRIORITY));
    }

    // novaSupportsFlex lived here until Nova was removed, the only platform model that ever
    // offered Flex/Priority. All four surviving profiles are Set.of(STANDARD) only (see
    // haikuIsStandardOnlyOnBedrock and batchIsNeverSelectableEvenWhereTheModelIsCapable's loop), so
    // there is no surviving contrast to pin — deleted rather than weakened into a tautology.

    @Test
    void unknownModelSupportsNothing() {
        // Fails closed: a dangling setting must not reach Bedrock with an unvalidated pair.
        assertFalse(BedrockModelProfile.supports("anthropic.claude-opus-4-7", ServiceTier.STANDARD));
        assertFalse(BedrockModelProfile.supports(null, ServiceTier.STANDARD));
    }

    @Test
    void batchIsNeverSelectableEvenWhereTheModelIsCapable() {
        // Batch is a separate Bedrock API, not a serviceTier value, so it must never appear as an
        // option however the matrix evolves. Looped over every surviving profile rather than
        // hardcoded to two models (Nova used to be the FLEX-capable contrast; removing it left no
        // model that selects FLEX at all) — a matrix-size-independent version of the original
        // intent, per this file's own stated design philosophy.
        for (BedrockModelProfile.ModelDescriptor p : BedrockModelProfile.platformModels()) {
            assertFalse(
                    BedrockModelProfile.selectableTiers(p.modelKey()).contains(ServiceTier.BATCH),
                    p.modelKey() + " must never offer BATCH as a selectable tier");
        }
        assertEquals(java.util.Set.of(), BedrockModelProfile.selectableTiers("nope"));
    }

    @Test
    void clampTtlDowngradesOnlyWhereTheModelCannotServeIt() {
        // tessary.grader.cache.ttl is a single global but the TTL menu is per model. Haiku takes both,
        // so neither value is ever rewritten for it.
        assertEquals(CacheTTL.VALUE_1_H, BedrockModelProfile.clampTtl(HAIKU, CacheTTL.VALUE_1_H));
        assertEquals(CacheTTL.VALUE_5_M, BedrockModelProfile.clampTtl(HAIKU, CacheTTL.VALUE_5_M));
    }

    /**
     * The regression. Luna (bedrock-mantle) accepts NO explicit ttl — mantle caches implicitly, so
     * {@code 5m} is as fatal as {@code 1h} for a model on {@link BedrockModelProfile.Endpoint#MANTLE}.
     * Clamping to "the model's shortest supported TTL" looked right and put the fatal field on the
     * wire anyway; null is the only value langchain4j turns into a ttl-less cache point. (This used to
     * be pinned against Nova, whose ttl field was Anthropic-only on Bedrock Converse for a different
     * reason — removing Nova left Luna carrying the same "empty explicitCacheTtls means implicit
     * caching, not no caching" contrast against Haiku/Sonnet's {@code {5m,1h}}.)
     */
    @Test
    void clampTtlOmitsTheFieldEntirelyForAModelThatTakesNoExplicitTtl() {
        assertNull(BedrockModelProfile.clampTtl(LUNA, CacheTTL.VALUE_1_H));
        assertNull(
                BedrockModelProfile.clampTtl(LUNA, CacheTTL.VALUE_5_M),
                "5m is a valid Converse ttl value, but naming it is still the Converse-only feature — mantle takes none");
    }

    @Test
    void mantleCachesEvenThoughItTakesNoExplicitTtl() {
        // Empty explicitCacheTtls must not be read as "cannot cache" — mantle caches implicitly on its
        // own window (confirmed by scripts/probe_mantle_capabilities.py, see the class javadoc).
        var luna = BedrockModelProfile.find(LUNA).orElseThrow();
        assertTrue(luna.promptCaching(), "Luna caches; mantle just cannot be told a duration");
        assertTrue(luna.explicitCacheTtls().isEmpty());
    }

    // structuredOutputModeDiffersBetweenTheTwoModels lived here until Nova was removed, the only
    // platform model whose structuredOutput was StructuredOutput.Mode.TOOL_CALL. All four surviving
    // profiles are NATIVE (Haiku, Sonnet: Converse outputConfig; Luna, Terra: mantle's native
    // text.format json_schema) — unknownModelsDefaultToNativeStructuredOutput already covers the
    // NATIVE-default fact, so there is nothing left to contrast and this test is deleted rather than
    // weakened into a tautology.

    @Test
    void unknownModelsDefaultToNativeStructuredOutput() {
        // A user-pinned Bedrock model has no platform profile. Native is what every provider we build
        // supports, and what callers assumed before the matrix existed.
        assertEquals(StructuredOutput.Mode.NATIVE, BedrockModelProfile.structuredMode("anthropic.claude-opus-4-7"));
    }

    @Test
    void onlyAnthropicModelsCanDriveTheSandboxLanes() {
        // The sandbox lanes need a model that can hold a long tool loop. Nova is a fine grading model and
        // an unrunnable sandbox, so this is a lane pairing rule, not a quality judgement.
        assertTrue(BedrockModelProfile.isAgentic(HAIKU));
        assertFalse(BedrockModelProfile.isAgentic(NOVA));
        assertFalse(BedrockModelProfile.isAgentic("some.unknown-model"), "unknown fails closed");
    }

    @Test
    void clampTtlPassesThroughForModelsWeHoldNoProfileFor() {
        // A user-pinned Bedrock model has no platform profile; we must not silently rewrite its TTL.
        assertEquals(CacheTTL.VALUE_1_H, BedrockModelProfile.clampTtl("anthropic.claude-opus-4-7", CacheTTL.VALUE_1_H));
    }

    @Test
    void cacheFloorsDifferBetweenTheTwoModels() {
        // 4,096 vs 1,024 — a quarter of Haiku's floor — the same prompt can be uncacheable on Haiku
        // and cacheable on Sonnet, which is what ChatJudgeRunner's cache-inactive warning reports off.
        // (Nova's 4,096-vs-1,000 contrast is gone with Nova's removal; Sonnet carries the same shape of fact.)
        assertEquals(4_096, BedrockModelProfile.find(HAIKU).orElseThrow().minCacheCheckpointTokens());
        assertEquals(1_024, BedrockModelProfile.find(SONNET).orElseThrow().minCacheCheckpointTokens());
    }

    @Test
    void everyProfileIdNormalizesBackToItsOwnKey() {
        // The link that makes the whole thing hang together: resolve() looks up a profile by key and
        // sends its inferenceProfileId, while every capability read normalizes that id back to a key. If
        // the two ever disagree the model runs with no capability clamp at all — no tier check, no
        // structured-output mode, no cache-ttl clamp — and the first symptom is a Bedrock 400.
        for (BedrockModelProfile.ModelDescriptor p : BedrockModelProfile.platformModels()) {
            assertEquals(
                    p.modelKey(),
                    BedrockModelProfile.normalizeKey(p.inferenceProfileId()),
                    "inference profile id must normalize to the model key");
        }
    }

    @Test
    void normalizeKeyStripsRegionScopeAndVersionSuffixAndNothingElse() {
        // Ported from the retired ModelPricingCatalog's own tests: these are the id shapes Bedrock
        // actually hands back, and each one was a real miss before the rule that covers it.
        assertEquals(
                "anthropic.claude-haiku-4-5",
                BedrockModelProfile.normalizeKey("global.anthropic.claude-haiku-4-5-20251001-v1:0"));
        assertEquals("anthropic.claude-sonnet-4-6", BedrockModelProfile.normalizeKey("us.anthropic.claude-sonnet-4-6"));
        assertEquals(
                "anthropic.claude-opus-4-7", BedrockModelProfile.normalizeKey("eu.anthropic.claude-opus-4-7-v1:0"));
        assertEquals("anthropic.claude-haiku-4-5", BedrockModelProfile.normalizeKey("apac.anthropic.claude-haiku-4-5"));
        // jp. and au. are not decorative: Nova ships a jp. profile and Haiku 4.5 ships both.
        assertEquals("amazon.nova-2-lite", BedrockModelProfile.normalizeKey("jp.amazon.nova-2-lite-v1:0"));
        assertEquals(
                "anthropic.claude-haiku-4-5",
                BedrockModelProfile.normalizeKey("au.anthropic.claude-haiku-4-5-20251001-v1:0"));
        // Mantle's own route-prefixed pricing id (see BedrockModelProfile.MANTLE_ROUTE_PREFIX) strips
        // back to the same logical key normalizeKey produces for every other Bedrock id shape.
        assertEquals("openai.gpt-5.6-luna", BedrockModelProfile.normalizeKey("bedrock_mantle/openai.gpt-5.6-luna"));
        // Ids that need no normalization pass through untouched — including a mantle bare id and a name
        // whose "-v1:0" is mid-string rather than a suffix.
        assertEquals("gpt-5.5", BedrockModelProfile.normalizeKey("gpt-5.5"));
        assertEquals("openai.gpt-5.6-luna", BedrockModelProfile.normalizeKey("openai.gpt-5.6-luna"));
        assertEquals("anthropic.claude-sonnet-4-6", BedrockModelProfile.normalizeKey("anthropic.claude-sonnet-4-6"));
        assertEquals("claude-v1:0-preview", BedrockModelProfile.normalizeKey("claude-v1:0-preview"));
        assertNull(BedrockModelProfile.normalizeKey(null));
    }
}
