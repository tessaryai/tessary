// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.util.List;

/**
 * Mantle has no discovery endpoint, so this is a hardcoded allowlist filtered through {@link SupportedMaker}. Never
 * returns empty, which would regress the picker. LiteLLM's vendored price book has a better list, but using it would
 * make {@code llm-runtime} depend on {@code substrate}.
 */
public final class BedrockMantleModelLister implements ProviderModelLister {

    private static final List<ProviderModel> KNOWN_MANTLE_MODELS = List.of(
            new ProviderModel("openai.gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI"),
            new ProviderModel("openai.gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI"));

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        return KNOWN_MANTLE_MODELS;
    }
}
