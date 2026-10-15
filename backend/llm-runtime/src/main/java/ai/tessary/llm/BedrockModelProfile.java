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
 * Adding a Bedrock model is one entry here. Rates live in the versioned {@code price_book}. Capability
 * ({@code agentic}) and offer ({@link #offeredFor}) are separate lists.
 */
public final class BedrockModelProfile {

    /**
     * @param inferenceProfileId cross-region {@code global.} on RUNTIME; on MANTLE it is LiteLLM's route-prefixed
     * pricing spelling, not the wire id, which is the bare {@code modelKey} @param agentic a capability, not a
     * permission
     */
    public record ModelDescriptor(
            @JsonProperty("model_key") String modelKey,
            @JsonProperty("inference_profile_id") String inferenceProfileId,
            @JsonProperty("display_name") String displayName,
            String vendor,
            @JsonProperty("agentic") boolean agentic,
            @JsonProperty("endpoint") Endpoint endpoint) {}

    /**
     * The endpoints differ in host, SigV4 service, protocol, id shape and region, so the provider is derived from this
     * rather than the vendor.
     */
    public enum Endpoint {
        RUNTIME,
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

    /** Rates live in {@code price_book}, so a price change never touches this table. */
    private static final List<ModelDescriptor> PROFILES = List.of(
            new ModelDescriptor(
                    "anthropic.claude-haiku-4-5",
                    "global.anthropic.claude-haiku-4-5-20251001-v1:0",
                    "Claude Haiku 4.5",
                    "Anthropic",
                    true,
                    Endpoint.RUNTIME),
            // A row that still pins Sonnet 5 reads as unset and the lane runs this.
            new ModelDescriptor(
                    "anthropic.claude-sonnet-5-5",
                    "global.anthropic.claude-sonnet-5-5",
                    "Claude Sonnet 5.5",
                    "Anthropic",
                    true,
                    Endpoint.RUNTIME),
            // Agentic is a product choice for a cost-dominated triage lane, not a measured claim about tool-loop
            // quality.
            new ModelDescriptor(
                    "openai.gpt-5.6-luna",
                    MANTLE_ROUTE_PREFIX + "openai.gpt-5.6-luna",
                    "GPT-5.6 Luna",
                    "OpenAI",
                    true,
                    Endpoint.MANTLE),
            // Agentic means reachable and permitted, not better.
            new ModelDescriptor(
                    "openai.gpt-5.6-terra",
                    MANTLE_ROUTE_PREFIX + "openai.gpt-5.6-terra",
                    "GPT-5.6 Terra",
                    "OpenAI",
                    true,
                    Endpoint.MANTLE));

    /**
     * AGENT_VM's Luna and Haiku 4.5 are deliberate exceptions for the cost-dominated triage lane. A model removed from
     * {@link #PROFILES} must also leave this list.
     */
    private static final Map<LaneGroup, List<String>> OFFERED_BY_GROUP = offeredByGroup();

    private static Map<LaneGroup, List<String>> offeredByGroup() {
        Map<LaneGroup, List<String>> m = new EnumMap<>(LaneGroup.class);
        m.put(
                LaneGroup.AGENT_VM,
                List.of(
                        "anthropic.claude-sonnet-5-5",
                        "openai.gpt-5.6-terra",
                        "openai.gpt-5.6-luna",
                        "anthropic.claude-haiku-4-5"));
        return Collections.unmodifiableMap(m);
    }

    private BedrockModelProfile() {}

    public static List<ModelDescriptor> platformModels() {
        return PROFILES;
    }

    /** Keys rather than descriptors: the client already has every descriptor. */
    public static List<String> offeredFor(LaneGroup group) {
        return OFFERED_BY_GROUP.getOrDefault(group, List.of());
    }

    public static Optional<ModelDescriptor> find(String modelKey) {
        if (modelKey == null) return Optional.empty();
        String key = modelKey.trim();
        return PROFILES.stream().filter(p -> p.modelKey().equals(key)).findFirst();
    }
}
