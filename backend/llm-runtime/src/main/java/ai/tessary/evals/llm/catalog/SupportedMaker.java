// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * #939 D6's six-maker allowlist — the ONLY makers a live-fetched model is offered under. Everything
 * else (Amazon/Nova, Meta/Llama, Mistral, Cohere, DeepSeek, and every other maker a hosting platform
 * might add) is filtered out, so a newly released model from a supported maker appears with no code
 * change while an unsupported one never does. {@link ai.tessary.evals.llm.ModelProvider#CUSTOM} is
 * the one deliberate exemption — see {@link OpenAiCompatModelLister}'s javadoc.
 *
 * <p>The alias tables below translate each hosting platform's OWN spelling of a maker into this enum.
 * A direct provider (OPENAI, ANTHROPIC, GEMINI, GLM, GROK, MOONSHOT) never needs one — the credential
 * IS the maker, so every model it reports is offered unfiltered by {@link OpenAiCompatModelLister} /
 * {@link AnthropicModelLister}. Only the multi-maker hosts (OpenRouter, Bedrock) need to ask "whose
 * model is this" per entry.
 */
public enum SupportedMaker {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    MOONSHOT,
    ZHIPU,
    XAI;

    /**
     * OpenRouter's own id-namespace prefix (the segment before the first {@code /}) for each maker,
     * confirmed against a live, unauthenticated {@code GET https://openrouter.ai/api/v1/models} read
     * during #939 TASK 2's implementation (2026-09-04) — the id shapes seen there:
     * {@code openai/gpt-5.5}, {@code anthropic/claude-...}, {@code google/gemini-...},
     * {@code moonshotai/kimi-...}, {@code z-ai/glm-...}, {@code x-ai/grok-...}. OpenRouter also
     * prefixes some "latest"-pointer ids with {@code ~} (e.g. {@code ~anthropic/claude-haiku-latest})
     * — {@link #fromOpenRouterPrefix} strips it before matching, so those resolve the same as their
     * un-prefixed sibling instead of being silently dropped as an unrecognised namespace.
     */
    private static final Map<String, SupportedMaker> OPENROUTER_PREFIX = Map.of(
            "openai", OPENAI,
            "anthropic", ANTHROPIC,
            "google", GOOGLE,
            "moonshotai", MOONSHOT,
            "z-ai", ZHIPU,
            "x-ai", XAI);

    /**
     * Bedrock's {@code FoundationModelSummary.providerName()} string for each maker.
     *
     * <p><b>Not independently probe-verified against a live {@code ListFoundationModels} call</b> —
     * this environment has no AWS credential to make one. Anthropic's spelling ({@code "Anthropic"})
     * is the one entry this repo can already corroborate, indirectly: it is the sole maker
     * {@link ai.tessary.evals.llm.BedrockModelProfile}'s PROFILES table has ever carried for
     * {@link ai.tessary.evals.llm.ModelProvider#BEDROCK} (the mantle profiles are OpenAI's, reported
     * differently — see {@link #fromBedrockProviderName}'s own note). The other five are AWS's
     * documented provider-name spellings as of this writing, not a live read; a follow-up with a real
     * Bedrock credential should confirm them before this table is trusted for anything beyond "does
     * not crash on an unmapped provider" (unmapped simply drops the entry, fail-closed, same as an
     * unknown model everywhere else in this codebase).
     */
    private static final Map<String, SupportedMaker> BEDROCK_PROVIDER_NAME = Map.of(
            "anthropic", ANTHROPIC,
            "openai", OPENAI,
            "google", GOOGLE,
            "moonshot ai", MOONSHOT,
            "zhipu ai", ZHIPU,
            "xai", XAI);

    /** {@link #OPENROUTER_PREFIX}, resolving a raw OpenRouter model id's namespace segment. */
    public static Optional<SupportedMaker> fromOpenRouterPrefix(String modelId) {
        if (modelId == null) return Optional.empty();
        int slash = modelId.indexOf('/');
        if (slash < 0) return Optional.empty();
        String prefix = modelId.substring(0, slash).toLowerCase(Locale.ROOT);
        if (prefix.startsWith("~")) prefix = prefix.substring(1);
        return Optional.ofNullable(OPENROUTER_PREFIX.get(prefix));
    }

    /**
     * {@link #BEDROCK_PROVIDER_NAME}, resolving Bedrock's {@code providerName()}. Mantle is a
     * different case: it has no {@code ListFoundationModels}-equivalent discovery call at all — see
     * {@code BedrockMantleModelLister}'s own javadoc for what was checked instead — so this method is
     * {@link ai.tessary.evals.llm.ModelProvider#BEDROCK} (Converse) only.
     */
    public static Optional<SupportedMaker> fromBedrockProviderName(String providerName) {
        if (providerName == null) return Optional.empty();
        return Optional.ofNullable(BEDROCK_PROVIDER_NAME.get(providerName.trim().toLowerCase(Locale.ROOT)));
    }
}
