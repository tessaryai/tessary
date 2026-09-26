// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The maker allowlist and the two alias tables that resolve into it. */
class SupportedMakerTest {

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
    void bedrockProviderNameDropsAnUnsupportedMaker() {
        // Amazon/Nova is the entire point of the Bedrock maker removal — must never resolve.
        assertEquals(Optional.empty(), SupportedMaker.fromBedrockProviderName("Amazon"));
        assertEquals(Optional.empty(), SupportedMaker.fromBedrockProviderName("Meta"));
        assertEquals(Optional.empty(), SupportedMaker.fromBedrockProviderName(null));
    }
}
