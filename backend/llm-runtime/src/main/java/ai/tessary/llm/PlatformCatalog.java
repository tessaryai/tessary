// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * Per-platform connection metadata, served alongside the {@link ModelCatalog}
 * so the "Add a model" form can render generically. The {@code auth} kind drives
 * which credential fields the UI shows, which keeps the form consistent across
 * platforms (every {@code api_key} platform looks identical) and means adding a
 * platform is one descriptor here plus a build branch in {@code ChatModelFactory},
 * no per-provider conditionals to edit in the frontend.
 */
public final class PlatformCatalog {

    /** How a platform authenticates, and therefore which fields the form renders. */
    public static final String AUTH_API_KEY = "api_key";

    public static final String AUTH_AWS = "aws";

    public record PlatformDescriptor(
            ModelProvider id,
            String label,
            String auth,
            @JsonProperty("supports_base_url") boolean supportsBaseUrl,
            @JsonProperty("default_base_url") String defaultBaseUrl) {}

    // Every platform requires an org-provided credential; there is no credential-free platform.
    // ChatModelFactory never falls back to an ambient key for a run selection.
    private static final List<PlatformDescriptor> PLATFORMS = List.of(
            new PlatformDescriptor(ModelProvider.OPENAI, "OpenAI", AUTH_API_KEY, true, "https://api.openai.com/v1"),
            // WITH the /v1: langchain4j-anthropic's DefaultAnthropicClient appends the bare path
            // "messages" to whatever baseUrl it's given rather than adding the version segment itself,
            // and OpenCode's @ai-sdk/anthropic in the agentic sandbox does the same. A bare host here
            // would post to /messages and 404 on both paths if a user typed it into the form.
            new PlatformDescriptor(
                    ModelProvider.ANTHROPIC, "Anthropic", AUTH_API_KEY, true, "https://api.anthropic.com/v1"),
            new PlatformDescriptor(
                    ModelProvider.OPENROUTER, "OpenRouter", AUTH_API_KEY, true, "https://openrouter.ai/api/v1"),
            new PlatformDescriptor(
                    ModelProvider.MOONSHOT, "Moonshot", AUTH_API_KEY, true, "https://api.moonshot.ai/v1"),
            // Gemini over its own OpenAI-compatible endpoint, the same Chat Completions build path
            // as OpenRouter/Moonshot, so no new auth kind or build method.
            new PlatformDescriptor(
                    ModelProvider.GEMINI,
                    "Google Gemini",
                    AUTH_API_KEY,
                    false,
                    "https://generativelanguage.googleapis.com/v1beta/openai/"),
            new PlatformDescriptor(
                    ModelProvider.GLM, "Zhipu GLM", AUTH_API_KEY, true, "https://open.bigmodel.cn/api/paas/v4"),
            new PlatformDescriptor(ModelProvider.GROK, "xAI Grok", AUTH_API_KEY, true, "https://api.x.ai/v1"),
            // No default base URL: CUSTOM is "any other OpenAI-compatible endpoint", so the user must
            // supply one. supportsBaseUrl is true and required in practice, since the form has nothing
            // else to send the request to.
            new PlatformDescriptor(ModelProvider.CUSTOM, "Custom (OpenAI-compatible)", AUTH_API_KEY, true, null),
            new PlatformDescriptor(ModelProvider.BEDROCK, "AWS Bedrock", AUTH_AWS, false, null),
            // Mantle authenticates with the same AWS credentials as Bedrock (SigV4), just against the
            // bedrock-mantle service name, so it reuses AUTH_AWS and the Providers form renders it
            // unchanged. No base-URL override: the host is derived from the region.
            new PlatformDescriptor(ModelProvider.BEDROCK_MANTLE, "AWS Bedrock (mantle)", AUTH_AWS, false, null));

    private PlatformCatalog() {}

    public static List<PlatformDescriptor> platforms() {
        return PLATFORMS;
    }

    public static Optional<PlatformDescriptor> find(ModelProvider id) {
        return PLATFORMS.stream().filter(p -> p.id() == id).findFirst();
    }

    /** Auth kind for a platform, defaulting to {@code api_key} for any unmapped value. */
    public static String authOf(ModelProvider id) {
        return find(id).map(PlatformDescriptor::auth).orElse(AUTH_API_KEY);
    }
}
