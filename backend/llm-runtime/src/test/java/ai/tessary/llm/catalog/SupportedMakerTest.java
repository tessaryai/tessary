// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The maker allowlist and the two alias tables that resolve into it. */
class SupportedMakerTest {

    @Test
    void openRouterPrefixResolvesEachMaker() {
        assertEquals(Optional.of(SupportedMaker.OPENAI), SupportedMaker.fromOpenRouterPrefix("openai/gpt-5.5"));
        assertEquals(Optional.of(SupportedMaker.ANTHROPIC), SupportedMaker.fromOpenRouterPrefix("anthropic/claude-5"));
        assertEquals(Optional.of(SupportedMaker.GOOGLE), SupportedMaker.fromOpenRouterPrefix("google/gemini-2.5"));
        assertEquals(Optional.of(SupportedMaker.MOONSHOT), SupportedMaker.fromOpenRouterPrefix("moonshotai/kimi-k2.6"));
        assertEquals(Optional.of(SupportedMaker.ZHIPU), SupportedMaker.fromOpenRouterPrefix("z-ai/glm-5.2"));
        assertEquals(Optional.of(SupportedMaker.XAI), SupportedMaker.fromOpenRouterPrefix("x-ai/grok-4.3"));
        assertEquals(Optional.of(SupportedMaker.TYPESAFE), SupportedMaker.fromOpenRouterPrefix("typesafe/jev-latest"));
    }

    @Test
    void openRouterPrefixDropsEveryUnsupportedMaker() {
        // Confirmed real OpenRouter namespaces (live read) that must NOT
        // pass the filter — none of them a supported maker.
        assertEquals(Optional.empty(), SupportedMaker.fromOpenRouterPrefix("meta/muse-spark-1.3"));
        assertEquals(Optional.empty(), SupportedMaker.fromOpenRouterPrefix("mistralai/mistral-large"));
        assertEquals(Optional.empty(), SupportedMaker.fromOpenRouterPrefix("deepseek/deepseek-v4"));
        assertEquals(Optional.empty(), SupportedMaker.fromOpenRouterPrefix("cohere/command-r"));
    }

    @Test
    void openRouterPrefixStripsTheLatestPointerTilde() {
        // OpenRouter's "~<maker>/<slug>-latest" pointer aliases (e.g. ~anthropic/claude-haiku-latest,
        // confirmed live) must resolve the same as their un-prefixed sibling, not be dropped as an
        // unrecognised namespace.
        assertEquals(
                Optional.of(SupportedMaker.ANTHROPIC),
                SupportedMaker.fromOpenRouterPrefix("~anthropic/claude-haiku-latest"));
        assertEquals(Optional.of(SupportedMaker.XAI), SupportedMaker.fromOpenRouterPrefix("~x-ai/grok-latest"));
    }

    @Test
    void openRouterPrefixHandlesMalformedIds() {
        assertEquals(Optional.empty(), SupportedMaker.fromOpenRouterPrefix("no-slash-at-all"));
        assertEquals(Optional.empty(), SupportedMaker.fromOpenRouterPrefix(null));
    }

    @Test
    void bedrockProviderNameResolvesCaseInsensitively() {
        assertEquals(Optional.of(SupportedMaker.ANTHROPIC), SupportedMaker.fromBedrockProviderName("Anthropic"));
        assertEquals(Optional.of(SupportedMaker.ANTHROPIC), SupportedMaker.fromBedrockProviderName("anthropic"));
        assertEquals(Optional.of(SupportedMaker.ANTHROPIC), SupportedMaker.fromBedrockProviderName("ANTHROPIC"));
    }

    @Test
    void bedrockProviderNameDropsAnUnsupportedMaker() {
        // Amazon/Nova is the entire point of the Bedrock maker removal — must never resolve.
        assertEquals(Optional.empty(), SupportedMaker.fromBedrockProviderName("Amazon"));
        assertEquals(Optional.empty(), SupportedMaker.fromBedrockProviderName("Meta"));
        assertEquals(Optional.empty(), SupportedMaker.fromBedrockProviderName(null));
    }

    @Test
    void everySupportedMakerHasExactlySevenValues() {
        // Pins the allowlist's own count so a maker silently added/removed here fails a test, not a code review.
        assertTrue(SupportedMaker.values().length == 7);
    }
}
