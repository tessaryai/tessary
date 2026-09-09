// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.config.MantleProperties;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ServiceTier;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

/**
 * What each platform-funded Bedrock model can actually do — the capability matrix the settings UI
 * renders from and {@link ChatModelFactory} clamps against. <b>Adding a Bedrock model is one entry
 * here</b>; nothing else needs a per-model branch. Rates are not here and never were — they live in
 * the versioned {@code price_book}, resolved by {@code pricing/ModelResolver}.
 *
 * <p><b>Why a matrix and not a set of constants:</b> the capabilities genuinely differ per model and
 * getting them wrong is a hard Bedrock error, not a degradation. The bullets below still cite Amazon
 * Nova 2 Lite as the illustration, even though #939 D6 removed it from {@link #PROFILES} (Amazon is
 * not one of D6's six supported makers — OpenAI, Anthropic, Google, Moonshot, Zhipu, xAI) — the
 * statements about IT are still true, they are just no longer reachable through this class. Flex and
 * Priority are the direct casualty: Nova was the only model here that ever offered them, so both
 * tiers are currently unoffered by anything in {@link #PROFILES} rather than gone as concepts (see
 * {@code ServiceTier}'s own note on this).
 *
 * <ul>
 *   <li><b>Service tiers.</b> Claude Haiku 4.5 on Bedrock supports Standard (and Reserved) only —
 *       sending {@code serviceTier: flex} against it is a 400. Amazon Nova 2 Lite supports Standard,
 *       Priority and Flex. So Flex is only reachable by choosing Nova, and the settings validator
 *       must refuse the Haiku+Flex pair rather than let Bedrock reject it mid-grade.
 *   <li><b>Explicit cache TTL.</b> Every model here caches, but only Anthropic models accept an
 *       explicit {@code ttl} on the cache point. On Bedrock the <b>presence</b> of that field IS the
 *       "extended TTL prompt caching" feature, so sending it to Nova is a 400 <i>whatever the
 *       value</i> — even {@code 5m}, Nova's own window. See {@link #explicitCacheTtls} and
 *       {@link #clampTtl}.
 *   <li><b>Structured output.</b> Anthropic models accept Converse {@code outputConfig}; Nova
 *       rejects it ("This model doesn't support the outputConfig field") and must be asked for a
 *       shaped answer via a forced tool call instead. See {@link StructuredOutput.Mode}.
 *   <li><b>Agentic.</b> An {@link LaneGroup#AGENT_VM} lane hands its inference profile id to the
 *       coding agent inside an E2B microVM, which asks the model to sustain a long tool-use loop
 *       over a repo. That is a capability judgement, not a vendor one: a non-agentic model is a
 *       valid grading model and a broken sandbox, so the lane matters.
 *   <li><b>Offered per lane group.</b> Separately from what a model <i>can</i> do,
 *       {@link #offeredFor} says which models each {@link LaneGroup} may be pointed at. Capability
 *       and offer are not the same list, and reading the first as the second is what let a small
 *       agentic model be saved onto a microVM lane — see {@link #OFFERED_BY_GROUP}.
 *   <li><b>Cache floor.</b> A prompt below the model's minimum checkpoint size caches nothing and
 *       silently pays full input rate every call. The floor is 4,096 tokens on Haiku but only 1,000
 *       on Nova, so the same prompt can be uncacheable on one and cacheable on the other. Driving
 *       {@code ChatJudgeRunner}'s cache-inactive warning off this field keeps that honest.
 * </ul>
 *
 * <p>These profiles describe the models the PLATFORM funds on its own ambient AWS identity. A user's
 * own pinned Bedrock selection goes through {@link ModelCatalog} + their {@link ProviderCredential}
 * and is unaffected.
 */
public final class BedrockModelProfile {

    private static final Logger log = LoggerFactory.getLogger(BedrockModelProfile.class);

    /**
     * One platform-funded Bedrock model.
     *
     * @param modelKey the logical, version-free key — what {@link #normalizeKey} produces from a full
     *     inference-profile id
     * @param inferenceProfileId the id this model is priced and reported under, cross-region
     *     {@code global.} by default on {@link Endpoint#RUNTIME} so throughput isn't pinned to one
     *     region. On {@link Endpoint#MANTLE} this is <b>not</b> the wire id — mantle is always called
     *     with the bare {@code modelKey} (see {@link ChatModelFactory#resolvePlatformMantle}) — it is
     *     LiteLLM's route-prefixed spelling ({@link #MANTLE_ROUTE_PREFIX}), because mantle is its own
     *     priced route in the vendored snapshot, distinct from both OpenAI-direct and Bedrock Converse
     * @param supportedTiers which {@link ServiceTier}s this model accepts; anything outside this set
     *     is rejected at the settings boundary
     * @param minCacheCheckpointTokens smallest prompt Bedrock will create a cache checkpoint for
     * @param explicitCacheTtls the prompt-cache TTLs this model accepts as an <b>explicit</b>
     *     {@code cachePoint.ttl}, as their Bedrock wire values ({@code 5m}, {@code 1h}) — kept as
     *     strings so the AWS SDK enum doesn't leak onto our HTTP contract. <b>Empty means the model
     *     still caches but the field must be omitted entirely</b>, not that it cannot cache; that
     *     distinction is the whole reason this is not a boolean.
     * @param structuredOutput how to ask this model for a strictly-shaped JSON answer
     * @param forcedToolChoice in {@link StructuredOutput.Mode#TOOL_CALL}, whether the model accepts
     *     being <i>forced</i> to call the tool (Bedrock {@code toolChoice: {any:{}}}) rather than
     *     merely offered it. Meaningless under {@link StructuredOutput.Mode#NATIVE}. False falls back
     *     to {@code auto}, which relies on the tool description to prompt the call.
     * @param agentic whether this model can drive the sandbox agent's tool loop — a capability, not a
     *     permission; {@link #offeredFor} decides which lanes actually get to pick it
     */
    public record ModelDescriptor(
            @JsonProperty("model_key") String modelKey,
            @JsonProperty("inference_profile_id") String inferenceProfileId,
            @JsonProperty("display_name") String displayName,
            String vendor,
            @JsonProperty("supported_tiers") Set<ServiceTier> supportedTiers,
            @JsonProperty("prompt_caching") boolean promptCaching,
            @JsonProperty("min_cache_checkpoint_tokens") int minCacheCheckpointTokens,
            @JsonProperty("explicit_cache_ttls") Set<String> explicitCacheTtls,
            @JsonProperty("structured_output") StructuredOutput.Mode structuredOutput,
            @JsonProperty("forced_tool_choice") boolean forcedToolChoice,
            @JsonProperty("agentic") boolean agentic,
            @JsonProperty("endpoint") Endpoint endpoint,
            @JsonProperty("api_path") @Nullable String apiPath,
            @JsonProperty("effort_levels") Set<String> effortLevels) {

        /**
         * A {@link Endpoint#RUNTIME} model with no reasoning control — the shape every model here had
         * before mantle existed, kept so those entries read unchanged and only the mantle ones carry
         * the extra three fields.
         */
        public ModelDescriptor(
                String modelKey,
                String inferenceProfileId,
                String displayName,
                String vendor,
                Set<ServiceTier> supportedTiers,
                boolean promptCaching,
                int minCacheCheckpointTokens,
                Set<String> explicitCacheTtls,
                StructuredOutput.Mode structuredOutput,
                boolean forcedToolChoice,
                boolean agentic) {
            this(
                    modelKey,
                    inferenceProfileId,
                    displayName,
                    vendor,
                    supportedTiers,
                    promptCaching,
                    minCacheCheckpointTokens,
                    explicitCacheTtls,
                    structuredOutput,
                    forcedToolChoice,
                    agentic,
                    Endpoint.RUNTIME,
                    null,
                    Set.of());
        }
    }

    /**
     * Which Bedrock endpoint serves a model, and therefore how {@code ChatModelFactory} builds it.
     *
     * <p>Not cosmetic: the two endpoints differ in host, SigV4 service name, wire protocol, model-id
     * shape and region. A model is reachable on one or the other (occasionally both, but never
     * identically), so this is the field the build path branches on rather than the provider enum —
     * which keeps "which endpoint is this model on" answerable from the same table that answers
     * "which tiers does it support".
     */
    public enum Endpoint {
        /** {@code bedrock-runtime} — Converse, cross-region inference profiles, explicit cache points. */
        RUNTIME,
        /** {@code bedrock-mantle} — OpenAI Responses, bare model ids on the wire, implicit caching. */
        MANTLE
    }

    /**
     * LiteLLM's own route prefix for models served over {@code bedrock-mantle} — its own priced route,
     * distinct from OpenAI-direct ({@code openai.gpt-5.6-luna}, a different product at a different
     * price) and from Bedrock Converse's cross-region spellings ({@code us./global.openai.gpt-5.6-luna},
     * a different Bedrock endpoint entirely). A call reported under the bare id has no scope prefix for
     * {@code pricing/ModelResolver} to strip, so without this prefix it falls through to OpenAI-direct
     * pricing — see #1032. Stripped back off by {@link #normalizeKey}.
     */
    static final String MANTLE_ROUTE_PREFIX = "bedrock_mantle/";

    /**
     * The platform's Bedrock line-up. Capabilities are transcribed from each model's AWS Bedrock
     * model card (service-tier table, prompt-caching table); re-check the card when adding a model or
     * when AWS enables a tier on an existing one. Rates live in the {@code price_book} tables, not here
     * — this file is capabilities only, so a price change never touches a capability and vice versa.
     */
    /**
     * The reasoning-effort levels the GPT-5.6 line accepts on mantle, in ascending order so a UI can
     * render them as a scale.
     *
     * <p>Transcribed from what the endpoint itself enumerates, not from a doc page: AWS's launch blog
     * lists these six, a secondary source claimed a seventh ({@code minimal}), and the API settles it
     * by rejecting {@code minimal} with a message naming the supported set. Ordered, so
     * {@code LinkedHashSet} rather than {@code Set.of}.
     *
     * <p>No Anthropic model here carries an effort set, and that is a deliberate absence rather than
     * an omission: Claude's effort rides in {@code additionalModelRequestFields.output_config}, which
     * is the same object Converse's native {@code outputConfig} — our structured-output path — writes
     * to. The pair is rejected ("output_config.format: Extra inputs are not permitted"), and the judge
     * always sends structured output, so effort is unreachable there. The probe asserts that constraint
     * still holds.
     */
    static final Set<String> MANTLE_GPT_EFFORTS =
            new LinkedHashSet<>(List.of("none", "low", "medium", "high", "xhigh", "max"));

    private static final List<ModelDescriptor> PROFILES = List.of(
            // Claude Haiku 4.5 — the incumbent default. Standard + Reserved ONLY: no Flex, no
            // Priority. Caches at 5m or 1h, floor 4,096 tokens.
            new ModelDescriptor(
                    "anthropic.claude-haiku-4-5",
                    "global.anthropic.claude-haiku-4-5-20251001-v1:0",
                    "Claude Haiku 4.5",
                    "Anthropic",
                    Set.of(ServiceTier.STANDARD),
                    true,
                    4_096,
                    Set.of("5m", "1h"),
                    StructuredOutput.Mode.NATIVE,
                    true,
                    true),
            // Claude Sonnet 5 — the frontier option, and the default the SYNTHESIS lane inherits
            // (tessary.synth.agentic-model). Standard only, like the rest of the Claude line on
            // Bedrock. Sonnet's cache checkpoint floor is 1,024 tokens — a quarter of Haiku's — so
            // prompts that cache nothing on Haiku do cache here.
            new ModelDescriptor(
                    "anthropic.claude-sonnet-5",
                    "global.anthropic.claude-sonnet-5",
                    "Claude Sonnet 5",
                    "Anthropic",
                    Set.of(ServiceTier.STANDARD),
                    true,
                    1_024,
                    Set.of("5m", "1h"),
                    StructuredOutput.Mode.NATIVE,
                    true,
                    true),
            // Amazon Nova 2 Lite lived here until #939 D6 dropped it — Amazon is not one of D6's six
            // supported makers (OpenAI, Anthropic, Google, Moonshot, Zhipu, xAI). It was the only
            // platform model that ever offered Flex/Priority (Set.of(STANDARD, FLEX, PRIORITY)) and
            // the only one with a sub-4,096-token cache floor (1,000) — see the class javadoc's note
            // on what that leaves unoffered.
            //
            // GPT-5.6 Luna — bedrock-mantle only, and by a distance the cheapest model here
            // ($0.22/$1.32 per 1M). OpenAI builds it for classification,
            // summarization and routing, which is what a grader does.
            //
            // Everything below the vendor is different from the Converse models above, and all of it
            // is confirmed by scripts/probe_mantle_capabilities.py rather than read off a doc page:
            // caching is IMPLICIT (a repeated 2,413-token prompt reported 2,411 cached with no request
            // parameters at all), which is why the explicit-ttl set is empty AND why cacheParams stays
            // null on this path — the empty set here means the same thing it means for Nova, that the
            // model caches on its own terms. Structured output is native (`text.format: json_schema`).
            // Standard tier only.
            //
            // Agentic: TRUE, as of A (#994) — a PRODUCT decision, not a measured one. This flag is a
            // classification of what we choose to offer, not a claim that Luna has been shown to hold
            // a long tool loop over a repo as well as Sonnet 5 does; that is unmeasured. It is
            // architecturally sound regardless: GPT-5.6 Terra below is already agentic=true on this
            // same bedrock-mantle endpoint and already offered for AGENT_VM, so a mantle-routed model
            // driving a sandbox agent is proven wiring, not new ground. Luna is offered because triage
            // is a cost-dominated lane (launch decision) — whether Luna SPECIFICALLY performs there as
            // well as Sonnet 5 is what #994's A.5 A/B verdict-agreement validation is for. If that
            // validation goes the other way, this flag (and TRIAGE's order in llm/LanePriority) is what reverts.
            new ModelDescriptor(
                    "openai.gpt-5.6-luna",
                    MANTLE_ROUTE_PREFIX + "openai.gpt-5.6-luna",
                    "GPT-5.6 Luna",
                    "OpenAI",
                    Set.of(ServiceTier.STANDARD),
                    true,
                    0,
                    Set.of(),
                    StructuredOutput.Mode.NATIVE,
                    true,
                    true,
                    Endpoint.MANTLE,
                    MantleProperties.OPENAI_API_PATH,
                    MANTLE_GPT_EFFORTS),
            // GPT-5.6 Terra — the balanced sibling, Sonnet-class in price ($2.20/$13.20 per 1M).
            // Same capability shape as Luna, except this one IS agentic: it is the non-Anthropic model
            // the sandbox reaches over the bedrock-mantle provider. Whether it holds a long tool loop
            // as well as Sonnet is unsettled — the flag says reachable and permitted, not better.
            new ModelDescriptor(
                    "openai.gpt-5.6-terra",
                    MANTLE_ROUTE_PREFIX + "openai.gpt-5.6-terra",
                    "GPT-5.6 Terra",
                    "OpenAI",
                    Set.of(ServiceTier.STANDARD),
                    true,
                    0,
                    Set.of(),
                    StructuredOutput.Mode.NATIVE,
                    true,
                    true,
                    Endpoint.MANTLE,
                    MantleProperties.OPENAI_API_PATH,
                    MANTLE_GPT_EFFORTS));

    /**
     * Which of the profiles above each {@link LaneGroup} may actually be pointed at, in the order a
     * dropdown should list them.
     *
     * <p><b>Narrower than "what the model can do" on purpose.</b> {@link #isAgentic} answers whether a
     * model <i>could</i> drive a sandbox agent; this answers whether we <i>offer</i> it for that kind of
     * work. In the other direction Sonnet 5 grades perfectly well and is not offered for
     * {@link LaneGroup#LLM_CALLS}, because those lanes run per trace and per keystroke and a frontier
     * model there is a bill, not a feature.
     *
     * <p><b>{@link LaneGroup#AGENT_VM} was, before A (#994), deliberately narrow — Sonnet 5 and Terra
     * only — with Claude Haiku 4.5 as the motivating exclusion: agentic by capability, but a small
     * model driving a microVM run over a repository was not work this list wanted to do.</b> A (#994)
     * adds BOTH Luna and Haiku 4.5 to this list as deliberate, considered exceptions to that narrowness
     * — not a return to "any agentic model may go here". Luna, because triage is a cost-dominated lane
     * where the product default is now the cheapest agentic option (see its {@code ModelDescriptor}
     * comment for what is and is not measured about that choice). Haiku 4.5, so the settings page can
     * demonstrate NOT warning on it for the triage lane (the price-gated warning only fires above a
     * threshold Haiku sits at or under). RCA, the other AGENT_VM lane, keeps its Sonnet 5 default.
     *
     * <p>It lives here rather than on {@link LaneGroup} so that every per-model question — tiers,
     * caching, structured output, effort, and now offerability — is answered by the same table. A model
     * removed from {@link #PROFILES} must also leave this list; {@link #verifyGroupsAreServiceable}
     * refuses to load the class otherwise.
     */
    private static final Map<LaneGroup, List<String>> OFFERED_BY_GROUP = offeredByGroup();

    private static Map<LaneGroup, List<String>> offeredByGroup() {
        Map<LaneGroup, List<String>> m = new EnumMap<>(LaneGroup.class);
        // "amazon.nova-2-lite" was offered here until #939 D6 removed it from PROFILES (see that
        // entry's own removal note) — LaneGroup.LLM_CALLS has had zero ModelLane members since #1117,
        // so this list is unreachable today regardless; verifyGroupsAreServiceable still requires
        // every name here to resolve, so the dropped model must leave this list too.
        m.put(LaneGroup.LLM_CALLS, List.of("openai.gpt-5.6-luna", "anthropic.claude-haiku-4-5"));
        m.put(
                LaneGroup.AGENT_VM,
                List.of(
                        "anthropic.claude-sonnet-5",
                        "openai.gpt-5.6-terra",
                        "openai.gpt-5.6-luna",
                        "anthropic.claude-haiku-4-5"));
        return Collections.unmodifiableMap(m);
    }

    static {
        verifyGroupsAreServiceable();
    }

    /**
     * Fail at class load rather than at the settings page, if the profile table and the per-group offer
     * list have drifted apart. Each drift has a silent symptom no compile catches: an offer list naming
     * a deleted profile renders a dropdown option that 400s on save, and a non-agentic model on the
     * {@link LaneGroup#AGENT_VM} list pins a sandbox to something that never starts.
     *
     * <p>The third file that has to agree, {@code llm/LanePriority}, is checked from
     * {@code ModelCatalog} instead — a lane's options span both tables, and a lane check here would
     * have this class read the catalog while the catalog is still reading this one. See
     * {@code ModelCatalog#verifyLanePriorities}.
     */
    private static void verifyGroupsAreServiceable() {
        for (Map.Entry<LaneGroup, List<String>> e : OFFERED_BY_GROUP.entrySet()) {
            for (String key : e.getValue()) {
                ModelDescriptor p = PROFILES.stream()
                        .filter(d -> d.modelKey().equals(key))
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException(e.getKey() + " offers " + key + ", which is not a profile"));
                if (e.getKey() == LaneGroup.AGENT_VM && !p.agentic()) {
                    throw new IllegalStateException(key + " is offered for " + e.getKey() + " but is not agentic");
                }
            }
        }
    }

    private BedrockModelProfile() {}

    /**
     * Bedrock cross-region inference-profile prefix — see {@link #normalizeKey}. The geo set is
     * {@code global.}, {@code us.}, {@code eu.}, {@code jp.}, {@code au.} and the older {@code apac.}
     * (still carried by pre-2025 Claude profiles). {@code jp.} and {@code au.} matter concretely: Nova 2
     * Lite ships a {@code jp.} profile and Haiku 4.5 ships both {@code jp.} and {@code au.}, so omitting
     * them would leave those regions' calls resolving to no profile at all.
     */
    private static final Pattern PROFILE_PREFIX = Pattern.compile("^(global|us|eu|jp|au|apac)\\.");

    /**
     * Bedrock model-id version suffix: an optional {@code -YYYYMMDD} snapshot date followed by the
     * {@code -v<n>:<n>} revision (e.g. {@code -20251001-v1:0}, {@code -v1:0}) — see {@link #normalizeKey}.
     */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("(-\\d{8})?-v\\d+:\\d+$");

    /**
     * Reduce a provider-specific model id to the logical {@link ModelDescriptor#modelKey} this table is
     * keyed on.
     *
     * <p>Bedrock is invoked with a full inference-profile id — {@code
     * global.anthropic.claude-haiku-4-5-20251001-v1:0} — which is what {@code ChatModelFactory} stamps as
     * {@code Resolved.modelName()} and therefore what lands on the span and in {@code verdict.model}. This
     * table is keyed on the logical {@code anthropic.claude-haiku-4-5}, so an exact lookup misses every
     * Bedrock call and the model reads as "not one of ours" — no capability clamp, no structured-output
     * mode. Stripping the region-routing prefix and the version suffix maps the id back onto its logical
     * name. A mantle model reports its {@link #MANTLE_ROUTE_PREFIX}-prefixed pricing id the same way, so
     * that is stripped first. Names that need no normalization (OpenAI's {@code gpt-5.5}, a mantle bare
     * id, an already logical Bedrock name) pass through unchanged.
     *
     * <p><b>This is identity, not pricing.</b> It used to live on the hand-maintained rate catalog because
     * that catalog was keyed the same way; rates now come from {@code price_book}, whose own
     * {@code ModelResolver} does its resolution against what the book actually carries. The two must not be
     * conflated again — the book prices a REGIONAL profile at its regional premium, so collapsing
     * {@code us.} into the bare name there would under-report that spend by 10%. Here, where the question
     * is "which of our models is this", collapsing it is exactly right.
     */
    public static String normalizeKey(@Nullable String modelName) {
        if (modelName == null) return null;
        String trimmed = modelName.trim();
        String noRoute =
                trimmed.startsWith(MANTLE_ROUTE_PREFIX) ? trimmed.substring(MANTLE_ROUTE_PREFIX.length()) : trimmed;
        String noPrefix = PROFILE_PREFIX.matcher(noRoute).replaceFirst("");
        return VERSION_SUFFIX.matcher(noPrefix).replaceFirst("");
    }

    /** Every platform-funded Bedrock model, in display order. */
    public static List<ModelDescriptor> platformModels() {
        return PROFILES;
    }

    /**
     * The model keys {@code group}'s lanes may be pointed at, in dropdown order. Returned as keys
     * rather than descriptors because that is what the settings payload carries alongside the full
     * model list — the client already has every descriptor and needs only to know which to offer.
     */
    public static List<String> offeredFor(LaneGroup group) {
        return OFFERED_BY_GROUP.getOrDefault(group, List.of());
    }

    /**
     * Whether {@code modelKey} may be pointed at a lane in {@code group}. False for an unknown model
     * and for an unknown group, so both the settings validator and the stored-row read fail closed.
     */
    public static boolean isOfferedFor(LaneGroup group, String modelKey) {
        return modelKey != null && offeredFor(group).contains(modelKey.trim());
    }

    /** The profile for a logical model key, or empty when the key isn't one of ours. */
    public static Optional<ModelDescriptor> find(String modelKey) {
        if (modelKey == null) return Optional.empty();
        String key = modelKey.trim();
        return PROFILES.stream().filter(p -> p.modelKey().equals(key)).findFirst();
    }

    /**
     * Whether {@code modelKey} can be served at {@code tier}. False for an unknown model, so a
     * dangling setting fails closed rather than reaching Bedrock with an unvalidated pair.
     */
    public static boolean supports(String modelKey, ServiceTier tier) {
        return find(modelKey).map(p -> p.supportedTiers().contains(tier)).orElse(false);
    }

    /**
     * The prompt-cache TTL to actually send for {@code modelKey}, or <b>{@code null} to send no
     * {@code ttl} field at all</b>.
     *
     * <p>Null is a first-class result, not an error path. On Bedrock the presence of
     * {@code cachePoint.ttl} is the Anthropic-only "extended TTL" feature, and langchain4j's
     * {@code AbstractBedrockChatModel.buildCachePoint} branches solely on null — any non-null
     * {@link CacheTTL} puts the field on the wire. So for a model with no explicit TTLs, null is the
     * ONLY way to ask for its default cache window; returning its "shortest supported" value instead
     * is what made every Nova call 400.
     *
     * <p>For a model that does accept the field, {@code requested} passes through when supported and
     * is otherwise clamped down to the model's shortest, so a single global
     * {@code tessary.grader.cache.ttl} stays usable across models with different menus. The downgrade
     * is logged rather than silent because it shortens a cache window someone configured.
     */
    public static @Nullable CacheTTL clampTtl(String modelKey, @Nullable CacheTTL requested) {
        ModelDescriptor p = find(modelKey).orElse(null);
        if (p == null || requested == null) return requested;
        if (p.explicitCacheTtls().isEmpty()) {
            // Not a downgrade — this model caches on its own default window and simply cannot be
            // told a duration. Debug, not warn: it is the correct steady state for such a model.
            log.debug(
                    "{} takes no explicit prompt-cache ttl; omitting the field (requested {})",
                    p.modelKey(),
                    requested);
            return null;
        }
        String wanted = requested.toString();
        if (p.explicitCacheTtls().contains(wanted)) return requested;
        CacheTTL fallback = shortest(p.explicitCacheTtls());
        log.warn(
                "prompt-cache ttl {} is not supported by {} (supports {}); using {} instead",
                wanted,
                p.modelKey(),
                p.explicitCacheTtls(),
                fallback);
        return fallback;
    }

    /**
     * How to ask {@code modelKey} for a strictly-shaped answer. Unknown models get
     * {@link StructuredOutput.Mode#NATIVE}: that is what every non-Bedrock provider we build does,
     * and it is what a caller resolving outside this matrix already assumed.
     */
    public static StructuredOutput.Mode structuredMode(String modelKey) {
        return find(modelKey).map(ModelDescriptor::structuredOutput).orElse(StructuredOutput.Mode.NATIVE);
    }

    /** Whether {@code modelKey} accepts being forced to call the tool; defaults true for unknown models. */
    public static boolean forcedToolChoice(String modelKey) {
        return find(modelKey).map(ModelDescriptor::forcedToolChoice).orElse(true);
    }

    /**
     * Whether {@code modelKey} can drive the agent in a sandbox lane. False for an unknown model,
     * so a dangling setting fails closed rather than pinning the sandbox to something unrunnable.
     */
    public static boolean isAgentic(String modelKey) {
        return find(modelKey).map(ModelDescriptor::agentic).orElse(false);
    }

    /**
     * The reasoning-effort levels a UI should offer for a model, in ascending order. Empty means the
     * model takes no effort parameter at all — the control is hidden rather than shown inert, exactly
     * as {@link #selectableTiers} handles a model with one tier.
     */
    public static Set<String> selectableEfforts(String modelKey) {
        return find(modelKey).map(ModelDescriptor::effortLevels).orElse(Set.of());
    }

    /**
     * Whether {@code modelKey} can be served at reasoning effort {@code level}.
     *
     * <p>A null/blank level is always supported: it means "send no reasoning parameter and let the
     * model use its own default", which every model accepts including those with no effort control.
     * A non-blank level against an unknown model is false, so a dangling setting fails closed rather
     * than reaching the endpoint with an unvalidated pair.
     */
    public static boolean supportsEffort(String modelKey, @Nullable String level) {
        if (level == null || level.isBlank()) return true;
        return selectableEfforts(modelKey).contains(level.trim());
    }

    /**
     * The model's shortest explicit TTL — the safe clamp target, since a shorter cache window can
     * only cost a re-write, whereas an unsupported one fails the call outright. Only ever called with
     * a non-empty set ({@link #clampTtl} handles empty as "omit the field"), so the 5m default here
     * covers a profile listing values the SDK cannot parse, never the no-explicit-ttl case.
     */
    private static CacheTTL shortest(Set<String> ttls) {
        CacheTTL best = null;
        long bestMs = Long.MAX_VALUE;
        for (String ttl : ttls) {
            CacheTTL parsed = CacheTTL.fromValue(ttl);
            if (parsed == null || parsed == CacheTTL.UNKNOWN_TO_SDK_VERSION) continue;
            long ms = approxMillis(ttl);
            if (ms < bestMs) {
                bestMs = ms;
                best = parsed;
            }
        }
        return best == null ? CacheTTL.VALUE_5_M : best;
    }

    /** Rough ordering key for the TTL strings Bedrock accepts ({@code 5m}, {@code 1h}). */
    private static long approxMillis(String ttl) {
        String v = ttl.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("h")) return Long.parseLong(v.substring(0, v.length() - 1)) * 3_600_000L;
            if (v.endsWith("m")) return Long.parseLong(v.substring(0, v.length() - 1)) * 60_000L;
            if (v.endsWith("s")) return Long.parseLong(v.substring(0, v.length() - 1)) * 1_000L;
        } catch (NumberFormatException unparseable) {
            return Long.MAX_VALUE;
        }
        return Long.MAX_VALUE;
    }

    /**
     * The tiers a UI should offer for a model, ordered as {@link ServiceTier} declares them so the
     * list reads Standard-first. Returns an empty set for an unknown model.
     *
     * <p>Only ONLINE tiers are offered: {@link ServiceTier#BATCH} exists for pricing and has no wire
     * form, so it must never appear as a selectable option (see {@link ServiceTier}).
     */
    public static Set<ServiceTier> selectableTiers(String modelKey) {
        ModelDescriptor p = find(modelKey).orElse(null);
        if (p == null) return Set.of();
        Set<ServiceTier> out = new LinkedHashSet<>();
        for (ServiceTier t : ServiceTier.values()) {
            if (t.isOnline() && p.supportedTiers().contains(t)) out.add(t);
        }
        return out;
    }
}
