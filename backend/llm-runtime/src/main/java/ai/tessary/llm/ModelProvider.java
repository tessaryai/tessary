// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

/**
 * A hosting platform we reach a model through: the platform axis only, never a vendor+platform combo; the model is the
 * orthogonal {@code model_name} axis. Connection metadata lives in {@link PlatformCatalog}.
 */
public enum ModelProvider {
    OPENAI,
    ANTHROPIC,
    OPENROUTER,
    // Every provider here requires an org credential except PLATFORM.
    MOONSHOT,
    BEDROCK,
    /** Google's Gemini line over its OpenAI-compatible endpoint. */
    GEMINI,
    /** Zhipu's GLM line, served OpenAI-compatible from open.bigmodel.cn. */
    GLM,
    /** xAI's Grok line, served OpenAI-compatible from api.x.ai. */
    GROK,
    /**
     * Any other OpenAI-compatible endpoint. {@link PlatformCatalog} has no default base URL for it; see
     * {@link ProviderCredential#customModelName()} for its free-text model id.
     */
    CUSTOM,

    /**
     * Its own value rather than a flag on {@link #BEDROCK}: a different host, SigV4 service, wire (OpenAI Responses),
     * model ids, quota pool and region, all of which the catalog and launcher dispatch on.
     */
    BEDROCK_MANTLE,

    /** Never a chat model: reached only through {@code llm/decisions/}. */
    TYPESAFE,

    /**
     * Supplied by the deployment with no org credential, offered only when a {@link PlatformProviderSupplier} says so;
     * the default supplier offers it to no one.
     */
    PLATFORM
}
