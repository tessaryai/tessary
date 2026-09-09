// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llm.catalog.ProviderModel;
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
            @JsonProperty("agentic") boolean agentic) {

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
                    "claude-opus-4-7",
                    "Claude Opus 4.7",
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
                    "anthropic/claude-opus-4.7",
                    "Claude Opus 4.7",
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
            // The GPT-5.6 line over OpenRouter, the two agentic lanes' defaults for this provider. Named
            // with the route, like the OpenAI-direct and mantle spellings of the same models, since three
            // providers reach Terra and Luna at three different prices.
            //
            // Unpriced today: substrate/pricing/litellm-model-prices.json carries no
            // `openrouter/openai/gpt-5.6*` row. TRIAGE's ceiling is taken on trust for Luna here, as it
            // is for CUSTOM.
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-5.6-terra",
                    "GPT-5.6 Terra (OpenRouter)",
                    false,
                    OPENAI_EFFORTS,
                    "https://openrouter.ai/api/v1",
                    true),
            new CatalogEntry(
                    ModelProvider.OPENROUTER,
                    "OpenAI",
                    "openai/gpt-5.6-luna",
                    "GPT-5.6 Luna (OpenRouter)",
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
                    "anthropic.claude-opus-4-7",
                    "Claude Opus 4.7",
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
            // The GPT-5.6 line direct, distinct from the two bedrock-mantle entries above: same models,
            // a different route at a different price. Terra is RCA's default on this provider since it's
            // half the price of the flagship gpt-5.6 on the same line and cheaper than the mantle route
            // to itself. Both stay offered; the price book decides the default.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-5.6-terra",
                    "GPT-5.6 Terra (OpenAI)",
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

            // One current-generation model per provider that fits the TRIAGE lane's price ceiling
            // (<= $1 in / <= $5 out per MTok, see LanePriority): triage runs unattended, once per
            // distinct cause, so the ceiling keeps an automatic choice from being an expensive one. Kimi
            // and Haiku are declared with their provider's own rows above instead, since each is that
            // provider's only current-generation model at its size.
            //
            // Luna is named for its route, since the platform also reaches it over bedrock-mantle at a
            // different price on a different credential.
            new CatalogEntry(
                    ModelProvider.OPENAI,
                    "OpenAI",
                    "gpt-5.6-luna",
                    "GPT-5.6 Luna (OpenAI)",
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
            // xAI's only current-generation model under the ceiling; its coding tier is what fits.
            new CatalogEntry(
                    ModelProvider.GROK,
                    "xAI",
                    "grok-code-fast-1",
                    "Grok Code Fast 1",
                    false,
                    NO_EFFORT,
                    "https://api.x.ai/v1",
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
     * Fail at class load if what {@link LanePriority}'s lanes name and what those lanes' group offers
     * are not the same set of models.
     *
     * <p>Two directions, catching different mistakes: a model a lane names that its group doesn't
     * offer renders in the dropdown and 400s on save, while a model the group offers that no lane
     * names is unreachable, in no picker and no automatic choice.
     *
     * <p>The union is taken across lanes rather than lane by lane, since the two lanes in
     * {@link LaneGroup#AGENT_VM} deliberately differ: TRIAGE carries only the models under its price
     * ceiling, so demanding the full set from each lane individually would forbid the ceiling itself.
     */
    private static void verifyLanePriorities() {
        Map<LaneGroup, Set<String>> namedByLanes = new LinkedHashMap<>();
        for (ModelLane lane : ModelLane.values()) {
            Set<String> offered = offeredFor(lane.group());
            for (String key : LanePriority.modelKeys(lane)) {
                if (!offered.contains(key)) {
                    throw new IllegalStateException(
                            "lane " + lane + " offers " + key + ", which " + lane.group() + " does not permit");
                }
            }
            namedByLanes
                    .computeIfAbsent(lane.group(), g -> new LinkedHashSet<>())
                    .addAll(LanePriority.modelKeys(lane));
        }
        for (Map.Entry<LaneGroup, Set<String>> e : namedByLanes.entrySet()) {
            Set<String> unreachable = new LinkedHashSet<>(offeredFor(e.getKey()));
            unreachable.removeAll(e.getValue());
            if (!unreachable.isEmpty()) {
                throw new IllegalStateException(e.getKey() + " offers " + unreachable
                        + ", which no lane in it names — nothing can ever select them");
            }
        }
    }

    /** Every model a lane group permits: its Bedrock offer list, plus the agentic catalog entries. */
    private static Set<String> offeredFor(LaneGroup group) {
        Set<String> offered = new LinkedHashSet<>(BedrockModelProfile.offeredFor(group));
        if (group == LaneGroup.AGENT_VM) {
            ENTRIES.stream().filter(CatalogEntry::agentic).forEach(e -> offered.add(key(e)));
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
                                    e.agentic()));
        }
        // Whatever is left in liveByName exists only in the live listing, synthesize fail-closed.
        for (ProviderModel m : liveByName.values()) {
            merged.add(new CatalogEntry(
                    provider, m.vendor(), m.modelName(), m.displayName(), false, NO_EFFORT, null, false));
        }
        return List.copyOf(merged);
    }
}
