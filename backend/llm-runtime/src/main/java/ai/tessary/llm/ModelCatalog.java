// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llm.catalog.ProviderModel;
import ai.tessary.llm.catalog.SupportedMaker;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-{@code (provider, model_name)} capability metadata (agentic, effort levels, strict JSON
 * schema, default base URL). {@code vendor} is the model's maker, used to group the model dropdown
 * when a platform hosts several vendors (Bedrock, OpenRouter).
 *
 * <p>{@link #ENTRIES} is not the full roster a project can pick from: {@link #mergeLive} overlays a
 * live-fetched listing onto this table, so a model a provider adds is offered with no code change
 * here, while this class keeps answering the orthogonal question of whether a given model is agentic,
 * what effort levels it takes, and whether its JSON schema is strict. {@link #entries()} returns every
 * static row unmerged, for a caller with no live listing.
 *
 * <p>Bedrock entries carry a logical model id only; the actual inference-profile ARN and region live
 * on the {@link ProviderCredential} row, because ARNs are AWS-account-scoped.
 */
public final class ModelCatalog {

    public record CatalogEntry(
            ModelProvider provider,
            String vendor,
            @JsonProperty("model_name") String modelName,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("strict_json_schema") boolean strictJsonSchema,
            /**
             * The reasoning-effort levels this model accepts, in ascending order; empty when it takes
             * no effort parameter at all. Surfaced as a per-run selector in the run modal and applied
             * in {@code ChatModelFactory}.
             *
             * <p>A list rather than a boolean because the sets genuinely differ: the OpenAI line takes
             * low/medium/high, while the GPT-5.6 models on bedrock-mantle also take none, xhigh and max.
             * A boolean forced the client to hardcode one vocabulary, which silently hid half the range
             * on the models that have the most of it.
             */
            @JsonProperty("effort_levels") Set<String> effortLevels,
            @JsonProperty("default_base_url") String defaultBaseUrl,
            /**
             * Whether this catalog entry can drive the sandbox agent's tool loop, the
             * {@link ModelCatalog}-side mirror of {@link BedrockModelProfile.ModelDescriptor#agentic()},
             * for non-Bedrock providers. Almost every catalog entry is false: a provider-agnostic
             * catalog exists to let a project judge with any model that provider hosts, and most of
             * those never need to sustain a long tool-use loop inside a microVM. {@link
             * ProjectModelSettings} is the only reader, it is the seam that lets a non-Bedrock model
             * be pointed at an {@link ai.tessary.llmspi.LaneGroup#AGENT_VM} lane at all.
             */
            @JsonProperty("agentic") boolean agentic,
            /**
             * Whether this entry is a hosted decision model (TypeSafe's Jev) rather than a chat model:
             * it answers typed questions over {@code llm/decisions/}, never through {@code ChatModel},
             * so no chat or agent lane may run it.
             */
            @JsonProperty("decision") boolean decision) {

        /** A chat-model entry, the shape every entry but the decision models takes. */
        public CatalogEntry(
                ModelProvider provider,
                String vendor,
                String modelName,
                String displayName,
                boolean strictJsonSchema,
                Set<String> effortLevels,
                String defaultBaseUrl,
                boolean agentic) {
            this(
                    provider,
                    vendor,
                    modelName,
                    displayName,
                    strictJsonSchema,
                    effortLevels,
                    defaultBaseUrl,
                    agentic,
                    false);
        }

        /** Whether a reasoning effort is meaningful for this model at all. */
        @JsonIgnore
        public boolean supportsEffort() {
            return !effortLevels.isEmpty();
        }
    }

    /**
     * What the OpenAI GPT-5 line accepts through the Chat Completions {@code reasoning_effort} field.
     * Kept to the three levels this app has always offered rather than widened speculatively, unlike
     * the mantle set, which is transcribed from what the endpoint itself enumerates.
     */
    private static final Set<String> OPENAI_EFFORTS = new LinkedHashSet<>(List.of("low", "medium", "high"));

    /** No reasoning control. Named so the entries below read as a deliberate absence, not an omission. */
    private static final Set<String> NO_EFFORT = Set.of();

    /**
     * Shared with {@link BedrockModelProfile} so a customer's own mantle credential offers exactly the
     * levels the platform's own lanes do: one transcription of what the endpoint accepts, not two.
     */
    private static final Set<String> MANTLE_GPT_EFFORTS = BedrockModelProfile.MANTLE_GPT_EFFORTS;

    private static final List<CatalogEntry> ENTRIES = List.of(
            // OpenAI (direct), the GPT-5 line.
            // Reasoning models: they accept a reasoning-effort level.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-5.5",
                    "GPT-5.5",
                    true,
                    OPENAI_EFFORTS,
                    "https://api.openai.com/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-5.4-mini",
                    "GPT-5.4 mini",
                    true,
                    OPENAI_EFFORTS,
                    "https://api.openai.com/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-5.4-nano",
                    "GPT-5.4 nano",
                    true,
                    OPENAI_EFFORTS,
                    "https://api.openai.com/v1",
                    false),

            // Anthropic (direct API). Native client; JSON via per-request response format.
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-opus-5-5",
                    "Claude Opus 5.5",
                    false,
                    NO_EFFORT,
                    "https://api.anthropic.com/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-sonnet-5",
                    "Claude Sonnet 5",
                    false,
                    NO_EFFORT,
                    "https://api.anthropic.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-sonnet-4-6",
                    "Claude Sonnet 4.6",
                    false,
                    NO_EFFORT,
                    "https://api.anthropic.com/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.ANTHROPIC,
                    "Anthropic",
                    "claude-haiku-4-5",
                    "Claude Haiku 4.5",
                    false,
                    NO_EFFORT,
                    "https://api.anthropic.com/v1",
                    true),

            // OpenRouter: a hosting platform spanning many vendors, grouped accordingly.
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Anthropic",
                    "anthropic/claude-opus-5.5",
                    "Claude Opus 5.5",
                    false,
                    NO_EFFORT,
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Anthropic",
                    "anthropic/claude-sonnet-5",
                    "Claude Sonnet 5",
                    false,
                    NO_EFFORT,
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Anthropic",
                    "anthropic/claude-sonnet-4.6",
                    "Claude Sonnet 4.6",
                    false,
                    NO_EFFORT,
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-5.5",
                    "GPT-5.5",
                    false,
                    OPENAI_EFFORTS,
                    "https://openrouter.ai/api/v1",
                    false),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Moonshot",
                    "moonshotai/kimi-k2.6",
                    "Kimi K2.6",
                    false,
                    NO_EFFORT,
                    "https://openrouter.ai/api/v1",
                    false),
            // GPT-6 Sol and Luna over OpenRouter, the two agentic lanes' models for this provider. Named
            // with the route, like the OpenAI-direct spellings of the same models, since each route
            // prices them separately. Priced via #pricingId's "openrouter/" prefix, not by modelName
            // alone — see that method's javadoc.
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-6-sol",
                    "GPT-6 Sol (OpenRouter)",
                    false,
                    OPENAI_EFFORTS,
                    "https://openrouter.ai/api/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-6-luna",
                    "GPT-6 Luna (OpenRouter)",
                    false,
                    OPENAI_EFFORTS,
                    "https://openrouter.ai/api/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "Auto",
                    "openrouter/auto",
                    "Auto (route best)",
                    false,
                    NO_EFFORT,
                    "https://openrouter.ai/api/v1",
                    false),

            // Moonshot (direct).
            new CatalogEntry(
                    ModelProvider.MOONSHOT,
                    "Moonshot",
                    "kimi-k2.6",
                    "Kimi K2.6",
                    false,
                    NO_EFFORT,
                    "https://api.moonshot.ai/v1",
                    true),

            // AWS Bedrock: currently hosts Anthropic only (Moonshot/Kimi K2.6 not yet
            // available on Bedrock). Logical ids; ARN/region on the row.
            new CatalogEntry(
                    ModelProvider.BEDROCK,
                    "Anthropic",
                    "anthropic.claude-sonnet-5",
                    "Claude Sonnet 5",
                    false,
                    NO_EFFORT,
                    null,
                    false),
            new CatalogEntry(
                    ModelProvider.BEDROCK,
                    "Anthropic",
                    "anthropic.claude-sonnet-4-6",
                    "Claude Sonnet 4.6",
                    false,
                    NO_EFFORT,
                    null,
                    false),
            new CatalogEntry(
                    ModelProvider.BEDROCK,
                    "Anthropic",
                    "anthropic.claude-opus-5-5",
                    "Claude Opus 5.5",
                    false,
                    NO_EFFORT,
                    null,
                    false),
            new CatalogEntry(
                    ModelProvider.BEDROCK,
                    "Anthropic",
                    "anthropic.claude-haiku-4-5",
                    "Claude Haiku 4.5",
                    false,
                    NO_EFFORT,
                    null,
                    false),

            // AWS Bedrock via the mantle endpoint, the only place the GPT-5.6 line exists, over the
            // OpenAI Responses API. Same AWS credential as Bedrock above, so the base URL stays null
            // here too. These take a reasoning effort, but the level set is not low/medium/high; see
            // BedrockModelProfile.
            new CatalogEntry(
                    ModelProvider.BEDROCK_MANTLE,
                    "OpenAI",
                    "openai.gpt-5.6-luna",
                    "GPT-5.6 Luna",
                    true,
                    MANTLE_GPT_EFFORTS,
                    null,
                    false),
            new CatalogEntry(
                    ModelProvider.BEDROCK_MANTLE,
                    "OpenAI",
                    "openai.gpt-5.6-terra",
                    "GPT-5.6 Terra",
                    true,
                    MANTLE_GPT_EFFORTS,
                    null,
                    false),

            // The agentic entries: one current-generation model per provider the sandbox launcher can
            // reach. ANTHROPIC's two are marked agentic in its own block above, since it speaks its own
            // wire; BEDROCK/BEDROCK_MANTLE's live in BedrockModelProfile. Model ids and generations are
            // checked against the vendored LiteLLM price book
            // (substrate/pricing/litellm-model-prices.json, refreshed by scripts/refresh-model-prices.sh),
            // the same table that prices a completed run. Google Vertex is not covered: its
            // service-account/ADC auth fits none of PlatformCatalog's three auth kinds.
            //
            // OpenAI direct. Sol is RCA's default on this provider: GPT-6 Sol ($2/$10) is half the input
            // price of the flagship gpt-5.6 and replaced GPT-5.6 Terra ($2/$12) outright, since OpenAI
            // shipped no GPT-6 Terra. Both stay offered; the price book decides the default.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-6-sol",
                    "GPT-6 Sol (OpenAI)",
                    true,
                    OPENAI_EFFORTS,
                    "https://api.openai.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-5.6",
                    "GPT-5.6",
                    true,
                    OPENAI_EFFORTS,
                    "https://api.openai.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.GEMINI,
                    "Google",
                    "gemini-3.1-pro-preview",
                    "Gemini 3.1 Pro",
                    false,
                    NO_EFFORT,
                    "https://generativelanguage.googleapis.com/v1beta/openai/",
                    true),
            new CatalogEntry(
                    ModelProvider.GLM,
                    "Zhipu",
                    "glm-5.3",
                    "GLM-5.3",
                    false,
                    NO_EFFORT,
                    "https://open.bigmodel.cn/api/paas/v4",
                    true),
            new CatalogEntry(
                    ModelProvider.GROK, "xAI", "grok-4.6", "Grok 4.6", false, NO_EFFORT, "https://api.x.ai/v1", true),

            // One small current-generation model per provider, selectable but not anyone's default (see
            // LanePriority): a project running TRIAGE unattended, once per distinct cause, can point it
            // here on purpose to spend less than the shared RCA/TRIAGE default. Kimi and Haiku are
            // declared with their provider's own rows above instead, since each is that provider's only
            // current-generation model at its size.
            //
            // Luna is named for its route, like Sol above, since OpenRouter reaches it at a different
            // price on a different credential.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-6-luna",
                    "GPT-6 Luna (OpenAI)",
                    true,
                    OPENAI_EFFORTS,
                    "https://api.openai.com/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.GEMINI,
                    "Google",
                    "gemini-3.7-flash",
                    "Gemini 3.7 Flash",
                    false,
                    NO_EFFORT,
                    "https://generativelanguage.googleapis.com/v1beta/openai/",
                    true),
            new CatalogEntry(
                    ModelProvider.GLM,
                    "Zhipu",
                    "glm-5.3-flash",
                    "GLM-5.3 Flash",
                    false,
                    NO_EFFORT,
                    "https://open.bigmodel.cn/api/paas/v4",
                    true),
            // xAI's small model: every grok-4.x chat model starts at $1.25 input, so its coding tier.
            new CatalogEntry(
                    ModelProvider.GROK,
                    "xAI",
                    "grok-code-fast-1",
                    "Grok Code Fast 1",
                    false,
                    NO_EFFORT,
                    "https://api.x.ai/v1",
                    true),
            // TypeSafe's Jev decision model, direct and over OpenRouter. Only the moving pointer is
            // offered: the provider echoes the version that answered, which is what gets recorded.
            new CatalogEntry(
                    ModelProvider.TYPESAFE,
                    "TypeSafe",
                    "jev-latest",
                    "Jev (latest)",
                    false,
                    NO_EFFORT,
                    "https://api.typesafe.ai",
                    false,
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "TypeSafe",
                    "typesafe/jev-latest",
                    "Jev (latest)",
                    false,
                    NO_EFFORT,
                    "https://openrouter.ai/api/v1",
                    false,
                    true),
            // CUSTOM carries no real model list, see ProviderCredential#customModelName, which is
            // what a project actually runs. modelName here is a placeholder the settings UI never
            // shows unqualified; ChatModelFactory#buildOpenAiCompat overrides it whenever the stored
            // credential's customModelName is set.
            new CatalogEntry(
                    ModelProvider.CUSTOM, "Custom", "custom-model", "Custom model", false, NO_EFFORT, null, true));

    static {
        verifyLanePriorities();
    }

    /**
     * Fail at class load if what a {@link LanePriority} lane names and what that lane's group offers
     * are not the same set of models.
     *
     * <p>Two directions, catching different mistakes: a model a lane names that its group doesn't
     * offer renders in the dropdown and 400s on save, while a model the group offers that the lane
     * doesn't name is missing from that lane's picker even though the group permits it.
     */
    private static void verifyLanePriorities() {
        for (ModelLane lane : ModelLane.values()) {
            Set<String> offered = offeredFor(lane.group());
            Set<String> named = new LinkedHashSet<>(LanePriority.modelKeys(lane));
            for (String key : named) {
                if (!offered.contains(key)) {
                    throw new IllegalStateException(
                            "lane " + lane + " offers " + key + ", which " + lane.group() + " does not permit");
                }
            }
            Set<String> missing = new LinkedHashSet<>(offered);
            missing.removeAll(named);
            if (!missing.isEmpty()) {
                throw new IllegalStateException(lane.group() + " permits " + missing + ", which lane " + lane
                        + " does not name, so its picker cannot offer them");
            }
        }
    }

    /**
     * Every model a lane group permits: its Bedrock offer list, plus the agentic catalog entries for
     * {@link LaneGroup#AGENT_VM} or the decision entries for {@link LaneGroup#DECISION_CALLS}.
     */
    private static Set<String> offeredFor(LaneGroup group) {
        Set<String> offered = new LinkedHashSet<>(BedrockModelProfile.offeredFor(group));
        if (group == LaneGroup.AGENT_VM) {
            ENTRIES.stream().filter(CatalogEntry::agentic).forEach(e -> offered.add(key(e)));
        }
        if (group == LaneGroup.DECISION_CALLS) {
            ENTRIES.stream().filter(CatalogEntry::decision).forEach(e -> offered.add(key(e)));
        }
        return offered;
    }

    private ModelCatalog() {}

    public static List<CatalogEntry> entries() {
        return ENTRIES;
    }

    /**
     * A catalog entry's {@code model_key} on the wire, {@code "<PROVIDER>:<model_name>"}, the
     * non-Bedrock half of the union {@link ProjectModelSettings} decodes. One function, because the
     * settings payload, the lane priority check and the stored row all have to spell it the same way.
     */
    public static String key(CatalogEntry entry) {
        return entry.provider().name() + ":" + entry.modelName();
    }

    /**
     * The book prefix every Jev call is priced under, whichever gateway carried it. The book has no
     * {@code openrouter/typesafe/...} key, so the OpenRouter route prices its already-namespaced
     * {@code typesafe/jev-latest} as is rather than through {@link #pricingId}'s {@code openrouter/} case.
     */
    public static final String DECISION_PRICING_PREFIX = "typesafe/";

    /**
     * The id this {@code (provider, modelName)} pair is priced under in the vendored LiteLLM book,
     * distinct from {@code modelName} for the routes whose book keys carry a prefix this catalog's own
     * names do not: {@code xai/}, {@code zai/}, {@code moonshot/}, {@code openrouter/}, {@link #DECISION_PRICING_PREFIX} for
     * {@link ModelProvider#TYPESAFE}, and
     * {@link BedrockModelProfile#MANTLE_ROUTE_PREFIX} for {@link ModelProvider#BEDROCK_MANTLE} (the
     * same split {@link BedrockModelProfile.ModelDescriptor#inferenceProfileId} documents for the
     * platform-funded lanes). Every other provider's book keys are bare, so {@code modelName} is
     * returned unchanged.
     *
     * <p>This is a rate-lookup id only. It never ships to the sandbox agent and never lands in
     * {@code llm_call.model} — both of those stay {@code modelName}, the name a person actually chose
     * (see {@link ProjectModelSettings.ResolvedAgenticModel}), so a project reading its own usage sees
     * "grok-4.6", not "xai/grok-4.6".
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
     * The id a decision model is priced under: {@code typesafe/<bare id>} on both gateways. OpenRouter's
     * name is already {@code typesafe/jev-latest}, so it passes through unchanged rather than via
     * {@link #pricingId}'s {@code openrouter/} case, which names no book key.
     */
    public static String decisionPricingId(ModelProvider provider, String modelName) {
        return provider == ModelProvider.TYPESAFE ? pricingId(provider, modelName) : modelName;
    }

    public static Optional<CatalogEntry> find(ModelProvider provider, String modelName) {
        return ENTRIES.stream()
                .filter(e -> e.provider() == provider && e.modelName().equals(modelName))
                .findFirst();
    }

    /**
     * Reconcile a live-fetched model listing for {@code provider} with this table's static capability
     * rows. The overlay is per {@code (provider, modelName)}, not per provider, since capability fields
     * vary within one provider (OPENROUTER's {@code openai/gpt-5.5} carries {@link #OPENAI_EFFORTS}
     * while every other OPENROUTER entry carries {@link #NO_EFFORT}).
     *
     * <ul>
     *   <li>a live model matching an existing static entry: the static row's capability fields win
     *       (they're hand-verified facts a vendor's {@code /models} listing doesn't carry), with the
     *       live listing's {@code displayName}/{@code vendor} overlaid, since those are cosmetic;
     *   <li>a live model with no static match: synthesized fail-closed ({@code agentic=false},
     *       {@code effortLevels=NO_EFFORT}, {@code strictJsonSchema=false}, {@code defaultBaseUrl=null}),
     *       since a model this table has never evaluated must not be silently offered as agentic or
     *       structured-output-strict.
     * </ul>
     *
     * <p>A static entry with no live match at all is passed through unchanged, so a cold cache degrades
     * to today's static list rather than to nothing.
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
                                    e.strictJsonSchema(),
                                    e.effortLevels(),
                                    e.defaultBaseUrl(),
                                    e.agentic(),
                                    e.decision()));
        }
        // Whatever is left in liveByName exists only in the live listing, synthesize fail-closed.
        for (ProviderModel m : liveByName.values()) {
            // A decision model is offered only as its static entry: pinned Jev versions stay unlisted.
            if (provider == ModelProvider.OPENROUTER
                    && SupportedMaker.fromOpenRouterPrefix(m.modelName()).orElse(null) == SupportedMaker.TYPESAFE) {
                continue;
            }
            merged.add(new CatalogEntry(
                    provider, m.vendor(), m.modelName(), m.displayName(), false, NO_EFFORT, null, false));
        }
        return List.copyOf(merged);
    }
}
