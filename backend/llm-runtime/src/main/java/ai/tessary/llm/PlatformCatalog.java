// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Per-platform connection metadata for the "Add a model" form. The {@code auth} kind drives which fields render, so
 * adding a platform is one descriptor here.
 */
public final class PlatformCatalog {

    public static final String AUTH_API_KEY = "api_key";

    public static final String AUTH_AWS = "aws";

    /** The deployment supplies the credential; the form renders only the supplier's label and {@code detail}. */
    public static final String AUTH_PLATFORM = "platform";

    /**
     * @param usedBy the lanes this platform's key is only for; empty for a chat provider @param detail set only on the
     * {@link #AUTH_PLATFORM} descriptor a {@link PlatformProviderSupplier} describes
     */
    public record PlatformDescriptor(
            ModelProvider id,
            String label,
            String auth,
            @JsonProperty("supports_base_url") boolean supportsBaseUrl,
            @JsonProperty("default_base_url") String defaultBaseUrl,
            @JsonProperty("used_by") List<String> usedBy,
            @JsonProperty("detail") @Nullable String detail) {

        PlatformDescriptor(
                ModelProvider id, String label, String auth, boolean supportsBaseUrl, String defaultBaseUrl) {
            this(id, label, auth, supportsBaseUrl, defaultBaseUrl, List.of(), null);
        }

        PlatformDescriptor(
                ModelProvider id,
                String label,
                String auth,
                boolean supportsBaseUrl,
                String defaultBaseUrl,
                List<String> usedBy) {
            this(id, label, auth, supportsBaseUrl, defaultBaseUrl, usedBy, null);
        }
    }

    // PLATFORM's descriptor is a placeholder the settings endpoints drop unless a supplier offers it.
    private static final List<PlatformDescriptor> PLATFORMS = List.of(
            new PlatformDescriptor(ModelProvider.OPENAI, "OpenAI", AUTH_API_KEY, true, "https://api.openai.com/v1"),
            // WITH the /v1: OpenCode's @ai-sdk/anthropic appends bare "messages" to the baseUrl.
            new PlatformDescriptor(
                    ModelProvider.ANTHROPIC, "Anthropic", AUTH_API_KEY, true, "https://api.anthropic.com/v1"),
            new PlatformDescriptor(
                    ModelProvider.OPENROUTER, "OpenRouter", AUTH_API_KEY, true, "https://openrouter.ai/api/v1"),
            new PlatformDescriptor(
                    ModelProvider.MOONSHOT, "Moonshot", AUTH_API_KEY, true, "https://api.moonshot.ai/v1"),
            // Gemini over its own OpenAI-compatible endpoint, like OpenRouter/Moonshot, so no new auth
            // kind.
            new PlatformDescriptor(
                    ModelProvider.GEMINI,
                    "Google Gemini",
                    AUTH_API_KEY,
                    false,
                    "https://generativelanguage.googleapis.com/v1beta/openai/"),
            new PlatformDescriptor(
                    ModelProvider.GLM, "Zhipu GLM", AUTH_API_KEY, true, "https://open.bigmodel.cn/api/paas/v4"),
            new PlatformDescriptor(ModelProvider.GROK, "xAI Grok", AUTH_API_KEY, true, "https://api.x.ai/v1"),
            // No default base URL: the user must supply one.
            new PlatformDescriptor(ModelProvider.CUSTOM, "Custom (OpenAI-compatible)", AUTH_API_KEY, true, null),
            new PlatformDescriptor(ModelProvider.BEDROCK, "AWS Bedrock", AUTH_AWS, false, null),
            // Same SigV4 credentials as Bedrock against the bedrock-mantle service; the host derives from the region.
            new PlatformDescriptor(ModelProvider.BEDROCK_MANTLE, "AWS Bedrock (mantle)", AUTH_AWS, false, null),
            // Its key reaches llm/decisions/ only.
            new PlatformDescriptor(
                    ModelProvider.TYPESAFE,
                    "TypeSafe",
                    AUTH_API_KEY,
                    true,
                    "https://api.typesafe.ai",
                    List.of(ModelLane.FRUSTRATION.wire())),
            new PlatformDescriptor(ModelProvider.PLATFORM, "Platform", AUTH_PLATFORM, false, null));

    private PlatformCatalog() {}

    public static List<PlatformDescriptor> platforms() {
        return PLATFORMS;
    }

    public static Optional<PlatformDescriptor> find(ModelProvider id) {
        return PLATFORMS.stream().filter(p -> p.id() == id).findFirst();
    }

    public static String authOf(ModelProvider id) {
        return find(id).orElseThrow().auth();
    }
}
