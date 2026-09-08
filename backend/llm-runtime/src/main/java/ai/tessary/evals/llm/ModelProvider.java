// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

/**
 * A hosting platform we can reach a model through. This is the <i>platform</i>
 * axis only — the model (and its vendor) is the orthogonal {@code model_name}
 * axis carried by {@link ModelCatalog}. Bedrock and OpenRouter host many
 * vendors' models, so the value is never a vendor+platform combo (the old
 * {@code ANTHROPIC_BEDROCK} is now just {@code BEDROCK}).
 *
 * <p>Per-platform connection metadata (label, auth kind, default base URL)
 * lives in {@link PlatformCatalog}; build dispatch keys off this enum in
 * {@code ChatModelFactory}.
 */
public enum ModelProvider {
    OPENAI,
    ANTHROPIC,
    OPENROUTER,
    // OLLAMA lived here until #939 D6: it was the platform's sole credential-free, AUTH_NONE
    // provider, and D6's maker filter (OpenAI, Anthropic, Google, Moonshot, Zhipu, xAI) drops it —
    // Meta is not a supported maker. Removing it also removed the LAST platform-funded path
    // (ChatModelFactory#resolveApiKey now fails closed unconditionally): every provider requires an
    // org credential.
    MOONSHOT,
    BEDROCK,
    /** Google's Gemini line over its OpenAI-compatible endpoint. */
    GEMINI,
    /** Zhipu's GLM line, served OpenAI-compatible from open.bigmodel.cn. */
    GLM,
    /** xAI's Grok line, served OpenAI-compatible from api.x.ai. */
    GROK,
    /**
     * Any other OpenAI-compatible endpoint a user points us at — a self-hosted vLLM/LiteLLM
     * gateway, a provider not worth a named entry, or an internal proxy. {@link PlatformCatalog}
     * carries no default base URL for this one; the user must supply one. See
     * {@link ProviderCredential#customModelName()} for the free-text model id this provider needs
     * (the catalog holds one representative entry, not a real model list).
     */
    CUSTOM,

    /**
     * AWS's second Bedrock endpoint. Deliberately its own value rather than a flag on {@link #BEDROCK}:
     * it has a different host, a different SigV4 service name and IAM namespace, a different wire
     * (OpenAI Responses rather than Converse), bare model ids with no cross-region inference profile,
     * its own quota pool and its own region. Every one of those is something {@code ChatModelFactory}
     * and {@link PlatformCatalog} already dispatch on, so the alternative was a boolean threaded
     * through all of them.
     */
    BEDROCK_MANTLE;

    /**
     * Platforms built via {@code OpenAiChatModel} — i.e. the OpenAI <i>Chat Completions</i> path.
     *
     * <p>{@link #BEDROCK_MANTLE} is excluded even though it speaks the OpenAI wire: the GPT-5.6 models
     * it hosts support only the Responses API, so it builds through {@code OpenAiResponsesChatModel}
     * and a signing HTTP client instead. "Speaks the OpenAI wire" and "shares this build path" stopped
     * being the same question when mantle arrived.
     */
    public boolean isOpenAiCompat() {
        return this != BEDROCK && this != ANTHROPIC && this != BEDROCK_MANTLE;
    }
}
