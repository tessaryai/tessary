// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.LaneGroup;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Bedrock models the platform offers, and what the settings page needs to know about each:
 * which endpoint serves it and whether it can drive the sandbox agent. Adding a Bedrock model is one
 * entry here; nothing else needs a per-model branch. Rates are not here: they live in the versioned
 * {@code price_book}, resolved by {@code pricing/ModelResolver}.
 *
 * <ul>
 *   <li><b>Agentic.</b> An {@link LaneGroup#AGENT_VM} lane hands its inference profile id to the
 *       coding agent inside an E2B microVM, which asks the model to sustain a long tool-use loop
 *       over a repo. That is a capability judgement, not a vendor one, so the lane matters.
 *   <li><b>Offered per lane group.</b> Separately from what a model <i>can</i> do,
 *       {@link #offeredFor} says which models each {@link LaneGroup} may be pointed at. Capability
 *       and offer are not the same list; see {@link #OFFERED_BY_GROUP}.
 * </ul>
 *
 * <p>A user's own pinned Bedrock selection goes through {@link ModelCatalog} + their
 * {@link ProviderCredential} and is unaffected.
 */
public final class BedrockModelProfile {

    /**
     * One Bedrock model.
     *
     * @param modelKey the logical, version-free key
     * @param inferenceProfileId the id this model is priced and reported under, cross-region
     *     {@code global.} by default on {@link Endpoint#RUNTIME} so throughput isn't pinned to one
     *     region. On {@link Endpoint#MANTLE} this is not the wire id: mantle is always called with
     *     the bare {@code modelKey}; it is LiteLLM's route-prefixed spelling
     *     ({@link #MANTLE_ROUTE_PREFIX}), because mantle is its own priced route in the vendored
     *     snapshot, distinct from both OpenAI-direct and Bedrock Converse
     * @param agentic whether this model can drive the sandbox agent's tool loop: a capability, not a
     *     permission; {@link #offeredFor} decides which lanes actually get to pick it
     */
    public record ModelDescriptor(
            @JsonProperty("model_key") String modelKey,
            @JsonProperty("inference_profile_id") String inferenceProfileId,
            @JsonProperty("display_name") String displayName,
            String vendor,
            @JsonProperty("agentic") boolean agentic,
            @JsonProperty("endpoint") Endpoint endpoint) {}

    /**
     * Which Bedrock endpoint serves a model, and therefore which {@link ModelProvider} a lane pointed
     * at it resolves to.
     *
     * <p>Not cosmetic: the two endpoints differ in host, SigV4 service name, wire protocol, model-id
     * shape and region. A model is reachable on one or the other (occasionally both, but never
     * identically), so this is the field the provider is derived from rather than a vendor name.
     */
    public enum Endpoint {
        /** {@code bedrock-runtime}: Converse, cross-region inference profiles, explicit cache points. */
        RUNTIME,
        /** {@code bedrock-mantle}: OpenAI Responses, bare model ids on the wire, implicit caching. */
        MANTLE
    }

    /**
     * LiteLLM's own route prefix for models served over {@code bedrock-mantle}: its own priced route,
     * distinct from OpenAI-direct ({@code openai.gpt-5.6-luna}, a different product at a different
     * price) and from Bedrock Converse's cross-region spellings ({@code us./global.openai.gpt-5.6-luna},
     * a different Bedrock endpoint entirely). A call reported under the bare id has no scope prefix for
     * {@code pricing/ModelResolver} to strip, so without this prefix it falls through to OpenAI-direct
     * pricing.
     */
    static final String MANTLE_ROUTE_PREFIX = "bedrock_mantle/";

    /**
     * The platform's Bedrock line-up. Rates live in the {@code price_book} tables, not here, so a
     * price change never touches this table and vice versa.
     */
    private static final List<ModelDescriptor> PROFILES = List.of(
            new ModelDescriptor(
                    "anthropic.claude-haiku-4-5",
                    "global.anthropic.claude-haiku-4-5-20251001-v1:0",
                    "Claude Haiku 4.5",
                    "Anthropic",
                    true,
                    Endpoint.RUNTIME),
            // Claude Sonnet 5, the default the SYNTHESIS lane inherits (tessary.synth.agentic-model).
            new ModelDescriptor(
                    "anthropic.claude-sonnet-5",
                    "global.anthropic.claude-sonnet-5",
                    "Claude Sonnet 5",
                    "Anthropic",
                    true,
                    Endpoint.RUNTIME),
            // GPT-5.6 Luna, bedrock-mantle only and the cheapest model here ($0.22/$1.32 per 1M).
            //
            // Agentic is a product choice here, not a measured claim that Luna holds a long tool loop
            // over a repo as well as Sonnet 5 does. Terra below is already agentic on the same
            // bedrock-mantle endpoint and offered for AGENT_VM, so a mantle-routed agent is proven
            // wiring. Luna is offered because triage is a cost-dominated lane.
            new ModelDescriptor(
                    "openai.gpt-5.6-luna",
                    MANTLE_ROUTE_PREFIX + "openai.gpt-5.6-luna",
                    "GPT-5.6 Luna",
                    "OpenAI",
                    true,
                    Endpoint.MANTLE),
            // GPT-5.6 Terra, the balanced sibling, Sonnet-class in price ($2.20/$13.20 per 1M). Also
            // agentic: it is the non-Anthropic model the sandbox reaches over the bedrock-mantle
            // provider. Whether it holds a long tool loop as well as Sonnet is unsettled; the flag
            // says reachable and permitted, not better.
            new ModelDescriptor(
                    "openai.gpt-5.6-terra",
                    MANTLE_ROUTE_PREFIX + "openai.gpt-5.6-terra",
                    "GPT-5.6 Terra",
                    "OpenAI",
                    true,
                    Endpoint.MANTLE));

    /**
     * Which of the profiles above each {@link LaneGroup} may actually be pointed at, in the order a
     * dropdown should list them.
     *
     * <p>Narrower than "what the model can do" on purpose: {@link ModelDescriptor#agentic()} answers
     * whether a model could drive a sandbox agent, this answers whether we offer it for that kind of
     * work.
     *
     * <p>{@link LaneGroup#AGENT_VM} lists Luna and Haiku 4.5 alongside Sonnet 5 and Terra as
     * deliberate exceptions, not a rule that any agentic model may go here: Luna because triage is a
     * cost-dominated lane and the cheapest agentic option is the product default there, and Haiku 4.5
     * so the settings page does not warn on it for the triage lane. RCA, the other AGENT_VM lane,
     * keeps its Sonnet 5 default.
     *
     * <p>It lives here rather than on {@link LaneGroup} so that every per-model question is answered
     * by the same table. A model removed from {@link #PROFILES} must also leave this list.
     */
    private static final Map<LaneGroup, List<String>> OFFERED_BY_GROUP = offeredByGroup();

    private static Map<LaneGroup, List<String>> offeredByGroup() {
        Map<LaneGroup, List<String>> m = new EnumMap<>(LaneGroup.class);
        m.put(
                LaneGroup.AGENT_VM,
                List.of(
                        "anthropic.claude-sonnet-5",
                        "openai.gpt-5.6-terra",
                        "openai.gpt-5.6-luna",
                        "anthropic.claude-haiku-4-5"));
        return Collections.unmodifiableMap(m);
    }

    private BedrockModelProfile() {}

    /** Every platform-funded Bedrock model, in display order. */
    public static List<ModelDescriptor> platformModels() {
        return PROFILES;
    }

    /**
     * The model keys {@code group}'s lanes may be pointed at, in dropdown order. Returned as keys
     * rather than descriptors because that is what the settings payload carries alongside the full
     * model list: the client already has every descriptor and needs only to know which to offer.
     */
    public static List<String> offeredFor(LaneGroup group) {
        return OFFERED_BY_GROUP.getOrDefault(group, List.of());
    }

    /** The profile for a logical model key, or empty when the key isn't one of ours. */
    public static Optional<ModelDescriptor> find(String modelKey) {
        if (modelKey == null) return Optional.empty();
        String key = modelKey.trim();
        return PROFILES.stream().filter(p -> p.modelKey().equals(key)).findFirst();
    }
}
