// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The only makers a live-fetched model is offered under, so a new model from a supported maker appears with no code
 * change. {@link ai.tessary.llm.ModelProvider#CUSTOM} is exempt. Only multi-maker hosts (OpenRouter, Bedrock) need the
 * alias tables below.
 */
public enum SupportedMaker {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    MOONSHOT,
    ZHIPU,
    XAI,
    /** Decision models only (Jev); offered on decision lanes, never on a chat or agent lane. */
    TYPESAFE;

    /**
     * Confirmed against a live {@code GET https://openrouter.ai/api/v1/models} on 2026-09-04.
     * {@link #fromOpenRouterPrefix} strips a leading {@code ~} ("latest" pointers) before matching.
     */
    private static final Map<String, SupportedMaker> OPENROUTER_PREFIX = Map.of(
            "openai", OPENAI,
            "anthropic", ANTHROPIC,
            "google", GOOGLE,
            "moonshotai", MOONSHOT,
            "z-ai", ZHIPU,
            "x-ai", XAI,
            "typesafe", TYPESAFE);

    /**
     * Not verified against a live {@code ListFoundationModels} call; only {@code "Anthropic"} is corroborated. An
     * unmapped provider drops the entry, fail-closed.
     */
    private static final Map<String, SupportedMaker> BEDROCK_PROVIDER_NAME = Map.of(
            "anthropic", ANTHROPIC,
            "openai", OPENAI,
            "google", GOOGLE,
            "moonshot ai", MOONSHOT,
            "zhipu ai", ZHIPU,
            "xai", XAI);

    public static Optional<SupportedMaker> fromOpenRouterPrefix(String modelId) {
        if (modelId == null) return Optional.empty();
        int slash = modelId.indexOf('/');
        if (slash < 0) return Optional.empty();
        String prefix = modelId.substring(0, slash).toLowerCase(Locale.ROOT);
        if (prefix.startsWith("~")) prefix = prefix.substring(1);
        return Optional.ofNullable(OPENROUTER_PREFIX.get(prefix));
    }

    /** Converse ({@link ai.tessary.llm.ModelProvider#BEDROCK}) only: mantle has no discovery call. */
    public static Optional<SupportedMaker> fromBedrockProviderName(String providerName) {
        if (providerName == null) return Optional.empty();
        return Optional.ofNullable(BEDROCK_PROVIDER_NAME.get(providerName.trim().toLowerCase(Locale.ROOT)));
    }
}
