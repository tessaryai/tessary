// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llm.catalog.ProviderModel;
import ai.tessary.llm.catalog.SupportedMaker;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-(provider, model) capability metadata. Not the full roster: {@link #mergeLive} overlays a live listing, so new
 * provider models need no change here. Bedrock entries carry a logical id; the account-scoped ARN and region live on
 * the {@link ProviderCredential}.
 */
public final class ModelCatalog {

    public record CatalogEntry(
            ModelProvider provider,
            String vendor,
            @JsonProperty("model_name") String modelName,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("default_base_url") String defaultBaseUrl,
            /**
             * Mirrors {@link BedrockModelProfile.ModelDescriptor#agentic()} for other providers; read only by
             * {@link ProjectModelSettings} to allow an {@link ai.tessary.llmspi.LaneGroup#AGENT_VM} lane.
             */
            @JsonProperty("agentic") boolean agentic,
            /**
             * A hosted decision model answers typed questions over {@code llm/decisions/}, never through a chat model,
             * so no chat or agent lane may run it.
             */
            @JsonProperty("decision") boolean decision) {

        public CatalogEntry(
                ModelProvider provider,
                String vendor,
                String modelName,
                String displayName,
                String defaultBaseUrl,
                boolean agentic) {
            this(provider, vendor, modelName, displayName, defaultBaseUrl, agentic, false);
        }
    }

    private static final List<CatalogEntry> ENTRIES = List.of(
            new CatalogEntry(ModelProvider.OPENAI, "OpenAI", "gpt-5.5", "GPT-5.5", "https://api.openai.com/v1", false),
            new CatalogEntry(
                    ModelProvider.OPENAI, "OpenAI", "gpt-5.4-mini", "GPT-5.4 mini", "https://api.openai.com/v1", false),
            new CatalogEntry(
                    ModelProvider.OPENAI, "OpenAI", "gpt-5.4-nano", "GPT-5.4 nano", "https://api.openai.com/v1", false),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-opus-5-5",
                    "Claude Opus 5.5",
                    "https://api.anthropic.com/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-sonnet-5-5",
                    "Claude Sonnet 5.5",
                    "https://api.anthropic.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-sonnet-4-6",
                    "Claude Sonnet 4.6",
                    "https://api.anthropic.com/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-haiku-4-5",
                    "Claude Haiku 4.5",
                    "https://api.anthropic.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Anthropic",
                    "anthropic/claude-opus-5.5",
                    "Claude Opus 5.5",
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Anthropic",
                    "anthropic/claude-sonnet-5.5",
                    "Claude Sonnet 5.5",
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Anthropic",
                    "anthropic/claude-sonnet-4.6",
                    "Claude Sonnet 4.6",
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-5.5",
                    "GPT-5.5",
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Moonshot",
                    "moonshotai/kimi-k2.6",
                    "Kimi K2.6",
                    "https://openrouter.ai/api/v1",
                    false),
            // Named with the route, since each route prices them separately; priced via #pricingId's "openrouter/"
            // prefix.
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-6-sol",
                    "GPT-6 Sol (OpenRouter)",
                    "https://openrouter.ai/api/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-6-luna",
                    "GPT-6 Luna (OpenRouter)",
                    "https://openrouter.ai/api/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Auto",
                    "openrouter/auto",
                    "Auto (route best)",
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.MOONSHOT, "Moonshot", "kimi-k2.6", "Kimi K2.6", "https://api.moonshot.ai/v1", true),

            // Logical ids; ARN and region are on the credential row.
            new CatalogEntry(
                    ModelProvider.BEDROCK,
                    "Anthropic",
                    "anthropic.claude-sonnet-5-5",
                    "Claude Sonnet 5.5",
                    null,
                    false),
            new CatalogEntry(
                    ModelProvider.BEDROCK,
                    "Anthropic",
                    "anthropic.claude-sonnet-4-6",
                    "Claude Sonnet 4.6",
                    null,
                    false),
            new CatalogEntry(
                    ModelProvider.BEDROCK, "Anthropic", "anthropic.claude-opus-5-5", "Claude Opus 5.5", null, false),
            new CatalogEntry(
                    ModelProvider.BEDROCK, "Anthropic", "anthropic.claude-haiku-4-5", "Claude Haiku 4.5", null, false),

            // The only place the GPT-5.6 line exists, over the Responses API.
            new CatalogEntry(
                    ModelProvider.BEDROCK_MANTLE, "OpenAI", "openai.gpt-5.6-luna", "GPT-5.6 Luna", null, false),
            new CatalogEntry(
                    ModelProvider.BEDROCK_MANTLE, "OpenAI", "openai.gpt-5.6-terra", "GPT-5.6 Terra", null, false),

            // The agentic entries: one current-generation model per provider the sandbox launcher can reach, checked
            // against the vendored LiteLLM price book. Google Vertex is not covered: its auth fits none of
            // PlatformCatalog's kinds. Sol is RCA's default here because it is half the flagship's input price.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-6-sol",
                    "GPT-6 Sol (OpenAI)",
                    "https://api.openai.com/v1",
                    true),
            new CatalogEntry(ModelProvider.OPENAI, "OpenAI", "gpt-5.6", "GPT-5.6", "https://api.openai.com/v1", true),
            new CatalogEntry(
                    ModelProvider.GEMINI,
                    "Google",
                    "gemini-3.1-pro-preview",
                    "Gemini 3.1 Pro",
                    "https://generativelanguage.googleapis.com/v1beta/openai/",
                    true),
            new CatalogEntry(
                    ModelProvider.GLM, "Zhipu", "glm-5.3", "GLM-5.3", "https://open.bigmodel.cn/api/paas/v4", true),
            new CatalogEntry(ModelProvider.GROK, "xAI", "grok-4.6", "Grok 4.6", "https://api.x.ai/v1", true),

            // One small model per provider, selectable but nobody's default, for projects that want unattended TRIAGE
            // to spend less.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-6-luna",
                    "GPT-6 Luna (OpenAI)",
                    "https://api.openai.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.GEMINI,
                    "Google",
                    "gemini-3.7-flash",
                    "Gemini 3.7 Flash",
                    "https://generativelanguage.googleapis.com/v1beta/openai/",
                    true),
            new CatalogEntry(
                    ModelProvider.GLM,
                    "Zhipu",
                    "glm-5.3-flash",
                    "GLM-5.3 Flash",
                    "https://open.bigmodel.cn/api/paas/v4",
                    true),
            // Only the moving pointer is offered; the provider echoes the version that answered. OpenRouter needs the
            // leading ~ or answers 400.
            new CatalogEntry(
                    ModelProvider.TYPESAFE,
                    "TypeSafe",
                    "jev-latest",
                    "Jev (latest)",
                    "https://api.typesafe.ai",
                    false,
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "TypeSafe",
                    "~typesafe/jev-latest",
                    "Jev (latest)",
                    "https://openrouter.ai/api/v1",
                    false,
                    true),
            // Prices under Anthropic's bare book key; the supplying build decides the route.
            new CatalogEntry(ModelProvider.PLATFORM, "Anthropic", "claude-sonnet-5-5", "Claude Sonnet 5.5", null, true),
            // A placeholder: a project actually runs ProviderCredential#customModelName.
            new CatalogEntry(ModelProvider.CUSTOM, "Custom", "custom-model", "Custom model", null, true));

    private ModelCatalog() {}

    public static List<CatalogEntry> entries() {
        return ENTRIES;
    }

    /** One function, so the settings payload, lane priority list and stored row spell it the same way. */
    public static String key(CatalogEntry entry) {
        return entry.provider().name() + ":" + entry.modelName();
    }

    /**
     * The book has no {@code openrouter/typesafe/...} key, so the OpenRouter route prices under this prefix instead of
     * {@link #pricingId}'s {@code openrouter/} case.
     */
    public static final String DECISION_PRICING_PREFIX = "typesafe/";

    /**
     * The id this pair is priced under in the LiteLLM book, which prefixes some routes ({@code xai/}, {@code zai/},
     * {@code moonshot/}, {@code openrouter/}, TypeSafe, mantle). Rate lookup only: the agent and {@code llm_call.model}
     * keep {@code modelName}.
     */
    public static String pricingId(ModelProvider provider, String modelName) {
        return switch (provider) {
            case GROK -> "xai/" + modelName;
            case GLM -> "zai/" + modelName;
            case MOONSHOT -> "moonshot/" + modelName;
            case OPENROUTER -> "openrouter/" + modelName;
            case BEDROCK_MANTLE -> BedrockModelProfile.MANTLE_ROUTE_PREFIX + modelName;
            case TYPESAFE -> DECISION_PRICING_PREFIX + modelName;
            default -> modelName;
        };
    }

    /**
     * Empty where models.dev cannot know the model. Sandbox runs are billed from these rates, so every agentic row must
     * resolve here ({@code ModelsDevCatalogTest}).
     */
    public static Optional<String> modelsDevId(ModelProvider provider, String modelName) {
        String prefix =
                switch (provider) {
                    case ANTHROPIC -> "anthropic/";
                    case OPENAI -> "openai/";
                    case GEMINI -> "google/";
                    case GROK -> "xai/";
                    case GLM -> "zai/";
                    case MOONSHOT -> "moonshotai/";
                    case OPENROUTER -> "openrouter/";
                    case BEDROCK, BEDROCK_MANTLE -> "amazon-bedrock/";
                    default -> null;
                };
        if (prefix == null) return Optional.empty();
        String bare = modelName.startsWith(BedrockModelProfile.MANTLE_ROUTE_PREFIX)
                ? modelName.substring(BedrockModelProfile.MANTLE_ROUTE_PREFIX.length())
                : modelName;
        return Optional.of(prefix + bare);
    }

    /** Already-namespaced names only lose OpenRouter's {@code ~} pointer mark. */
    public static String decisionPricingId(ModelProvider provider, String modelName) {
        if (provider == ModelProvider.TYPESAFE) return pricingId(provider, modelName);
        return modelName.startsWith("~") ? modelName.substring(1) : modelName;
    }

    public static Optional<CatalogEntry> find(ModelProvider provider, String modelName) {
        return ENTRIES.stream()
                .filter(e -> e.provider() == provider && e.modelName().equals(modelName))
                .findFirst();
    }

    /**
     * Overlay a live listing per (provider, model): a matching static row keeps its hand-verified capabilities and
     * takes only display name and vendor; an unknown live model is synthesized fail-closed (not agentic). Unmatched
     * static rows pass through, so a cold cache degrades to the static list.
     */
    public static List<CatalogEntry> mergeLive(ModelProvider provider, List<ProviderModel> live) {
        Map<String, ProviderModel> liveByName = new LinkedHashMap<>();
        for (ProviderModel m : live) {
            liveByName.put(m.modelName(), m);
        }
        List<CatalogEntry> merged = new ArrayList<>();
        for (CatalogEntry e : ENTRIES) {
            if (e.provider() != provider) continue;
            ProviderModel overlay = liveByName.remove(e.modelName());
            merged.add(
                    overlay == null
                            ? e
                            : new CatalogEntry(
                                    e.provider(),
                                    overlay.vendor(),
                                    e.modelName(),
                                    overlay.displayName(),
                                    e.defaultBaseUrl(),
                                    e.agentic(),
                                    e.decision()));
        }
        // Left only in the live listing: synthesize fail-closed.
        for (ProviderModel m : liveByName.values()) {
            // Pinned Jev versions stay unlisted.
            if (provider == ModelProvider.OPENROUTER
                    && SupportedMaker.fromOpenRouterPrefix(m.modelName()).orElse(null) == SupportedMaker.TYPESAFE) {
                continue;
            }
            merged.add(new CatalogEntry(provider, m.vendor(), m.modelName(), m.displayName(), null, false));
        }
        return List.copyOf(merged);
    }
}
