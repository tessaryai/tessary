// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.PlatformCatalog;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Where a chat call goes, on whose key and over which wire. {@link #toString} never prints the key. */
public record ChatTarget(ModelProvider provider, String modelId, URI endpoint, String apiKey, ChatDialect dialect) {

    private static final String OPENROUTER_HOST = "openrouter.ai";

    /** Bedrock and mantle sign with SigV4 on other wires; TypeSafe and the platform provider are never chat models. */
    public static ChatDialect dialectFor(ModelProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> ChatDialect.ANTHROPIC_MESSAGES;
            case OPENAI, OPENROUTER, MOONSHOT, GEMINI, GLM, GROK, CUSTOM -> ChatDialect.OPENAI_CHAT_COMPLETIONS;
            case BEDROCK, BEDROCK_MANTLE, TYPESAFE, PLATFORM ->
                throw new TessaryException(
                        PlaygroundError.PROVIDER_UNSUPPORTED,
                        provider,
                        "the playground speaks only the OpenAI chat completions and Anthropic Messages wires");
        };
    }

    /**
     * The chat base URL with its version segment kept (the Providers form shows it that way, and Anthropic's carries
     * {@code /v1}); only trailing slashes go before the path is appended.
     */
    public static URI endpointFor(ModelProvider provider, @Nullable String baseUrlOverride) {
        String path = dialectFor(provider) == ChatDialect.ANTHROPIC_MESSAGES ? "/messages" : "/chat/completions";
        String base = baseUrlOverride != null && !baseUrlOverride.isBlank()
                ? baseUrlOverride.trim()
                : PlatformCatalog.find(provider).orElseThrow().defaultBaseUrl();
        if (base == null) {
            throw new TessaryException(
                    PlaygroundError.PROVIDER_UNSUPPORTED, provider, "the credential names no base URL");
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return URI.create(base + path);
        } catch (IllegalArgumentException e) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, e, "malformed URI");
        }
    }

    /** The org's OpenRouter key, or any key pointed at OpenRouter's host: either way OpenRouter sends the bill. */
    public boolean billedByOpenRouter() {
        return provider == ModelProvider.OPENROUTER || OPENROUTER_HOST.equalsIgnoreCase(endpoint.getHost());
    }

    @Override
    public String toString() {
        return "ChatTarget[provider=" + provider + ", modelId=" + modelId + ", endpoint=" + endpoint + ", dialect="
                + dialect + "]";
    }
}
