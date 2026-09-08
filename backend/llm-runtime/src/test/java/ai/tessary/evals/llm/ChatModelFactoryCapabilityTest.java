// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

/**
 * The provider capability matrix: the single "caching" flag is split into three
 * independent capabilities — caching, warm-fan-out eligibility, and pacing — so OpenAI
 * could be fanned out without un-pacing the rate-limited OpenRouter/Moonshot (Ollama was dropped
 * by #939 D6's maker filter)
 * tiers. This pins each axis so a future provider edit can't silently re-couple them
 * (e.g. accidentally un-pacing a rate-limited compat tier).
 */
class ChatModelFactoryCapabilityTest {

    @Test
    void cachesPrompts_bedrockAnthropicOpenAi() {
        assertTrue(ChatModelFactory.cachesPrompts(ModelProvider.BEDROCK));
        assertTrue(ChatModelFactory.cachesPrompts(ModelProvider.ANTHROPIC));
        assertTrue(ChatModelFactory.cachesPrompts(ModelProvider.OPENAI));

        assertFalse(ChatModelFactory.cachesPrompts(ModelProvider.OPENROUTER));
        assertFalse(ChatModelFactory.cachesPrompts(ModelProvider.MOONSHOT));
    }

    @Test
    void paced_onlyTheRateLimitedCompatTiers() {
        // The riskiest invariant: the rate-limited compat tiers MUST stay paced.
        assertTrue(ChatModelFactory.paced(ModelProvider.OPENROUTER));
        assertTrue(ChatModelFactory.paced(ModelProvider.MOONSHOT));

        // The caching providers and OpenAI direct rely on bounded fan-out instead.
        assertFalse(ChatModelFactory.paced(ModelProvider.OPENAI));
        assertFalse(ChatModelFactory.paced(ModelProvider.ANTHROPIC));
        assertFalse(ChatModelFactory.paced(ModelProvider.BEDROCK));
    }

    // --- configurable Bedrock cache TTL -------------------------------------
    // TTL is bound to evals.grader.cache.ttl; default is the unchanged 5-minute value and an
    // unrecognised/blank value falls back to 5m so a typo can never push an unsupported TTL
    // into the Bedrock request or silently disable caching.

    @Test
    void resolveCacheTtl_defaultsToFiveMinutes() {
        assertEquals(CacheTTL.VALUE_5_M, ChatModelFactory.resolveCacheTtl("5m"));
        assertEquals(CacheTTL.VALUE_5_M, ChatModelFactory.resolveCacheTtl(null), "null → 5m default");
        assertEquals(CacheTTL.VALUE_5_M, ChatModelFactory.resolveCacheTtl("  "), "blank → 5m default");
    }

    @Test
    void resolveCacheTtl_acceptsOneHourAndTrims() {
        assertEquals(CacheTTL.VALUE_1_H, ChatModelFactory.resolveCacheTtl("1h"));
        assertEquals(CacheTTL.VALUE_1_H, ChatModelFactory.resolveCacheTtl(" 1h "), "trims whitespace");
    }

    @Test
    void resolveCacheTtl_unknownFallsBackToFiveMinutes() {
        // Bedrock only supports 5m / 1h; anything else must fall back, not pass through as
        // UNKNOWN_TO_SDK_VERSION (which the request would reject).
        assertEquals(CacheTTL.VALUE_5_M, ChatModelFactory.resolveCacheTtl("30s"));
        assertEquals(CacheTTL.VALUE_5_M, ChatModelFactory.resolveCacheTtl("garbage"));
    }

    @Test
    void pacingIsIndependentOfCaching() {
        // Decoupling check: a provider that caches is not necessarily unpaced, and vice
        // versa — the two axes are computed independently for every provider.
        for (ModelProvider p : ModelProvider.values()) {
            boolean caches = ChatModelFactory.cachesPrompts(p);
            boolean paced = ChatModelFactory.paced(p);
            // Today caching and pacing happen to be mutually exclusive, but the point is
            // each is derived from its own switch, not `paced == !caches` by construction.
            assertEquals(caches, !paced, "provider " + p + " caching/pacing matrix");
        }
    }
}
