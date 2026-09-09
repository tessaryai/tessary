// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.util.List;

/**
 * Mantle has no per-request discovery endpoint reachable from this codebase: {@code
 * MantleProperties} only documents chat-call paths, and there's no mantle equivalent of Bedrock's
 * {@code ListFoundationModels} control-plane call.
 *
 * <p>{@code scripts/refresh-model-prices.sh} vendors LiteLLM's price book, which does carry a
 * versioned mantle model-identity list, a better identity source than this class uses. It isn't
 * wired in: {@code llm-runtime} (this module) doesn't depend on {@code substrate}, which owns the
 * ingest path, and adding that edge for one JSON resource would be a real architecture change, not
 * a plumbing detail. The price book's refresh cadence is pricing-driven, not the same contract as
 * "what mantle currently serves."
 *
 * <p>So: a small hardcoded allowlist of today's known mantle models, run through the same {@link
 * SupportedMaker} filter every other lister uses. Never returns empty, which would regress the
 * picker below what it already has. {@link ai.tessary.llm.ModelCatalog}'s own static entries stay
 * authoritative for capability metadata regardless; this class only supplies the live listing merge
 * input. Replace it when mantle gains a real discovery surface, or {@code llm-runtime} gains a
 * legitimate reason to depend on {@code substrate}.
 */
public final class BedrockMantleModelLister implements ProviderModelLister {

    private static final List<ProviderModel> KNOWN_MANTLE_MODELS = List.of(
            new ProviderModel("openai.gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI"),
            new ProviderModel("openai.gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI"));

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        // No network call to make and therefore nothing that can fail per-credential: every org with
        // a mantle credential sees the same fixed list, consistent with it being a hardcoded fallback
        // rather than a real per-account discovery read.
        return KNOWN_MANTLE_MODELS;
    }
}
