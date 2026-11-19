// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider first, model second: walk a lane's providers in order and take the first the org holds a key for, then that
 * provider's default. A flat model list let one provider's second-best outrank another's best. There is no per-lane
 * default model, since it would name a provider the org may not have. TRIAGE and AGENT_RUN share RCA's list. Every chat
 * provider appears on every agent lane, which requires a launcher mode for each. Model keys use the two spellings
 * {@link ProjectModelSettings} decodes.
 */
public final class LanePriority {

    /**
     * {@code defaultModelKey} is always in {@code modelKeys}, or it would be auto-selectable but unpickable by hand.
     */
    public record ProviderOption(ModelProvider provider, List<String> modelKeys, String defaultModelKey) {}

    private static final String SONNET_5_5 = "anthropic.claude-sonnet-5-5";
    private static final String HAIKU_5_5 = "anthropic.claude-haiku-5-5";
    private static final String OPUS_5_5 = "anthropic.claude-opus-5-5";
    private static final String MANTLE_SOL = "openai.gpt-6.1-sol";
    private static final String MANTLE_LUNA = "openai.gpt-6-luna";
    private static final String ANTHROPIC_SONNET_5_5 = "ANTHROPIC:claude-sonnet-5-5";
    private static final String ANTHROPIC_HAIKU_5_5 = "ANTHROPIC:claude-haiku-5-5";
    private static final String ANTHROPIC_OPUS_5_5 = "ANTHROPIC:claude-opus-5-5";
    private static final String GPT_6_1_SOL = "OPENAI:gpt-6.1-sol";
    private static final String GPT_6_1_TERRA = "OPENAI:gpt-6.1-terra";
    private static final String GPT_6_LUNA = "OPENAI:gpt-6-luna";
    // OpenRouter names carry their own slash ("openai/gpt-6.1-sol" is one name); the launcher's toProviderModel handles
    // that.
    private static final String OR_SOL = "OPENROUTER:openai/gpt-6.1-sol";
    private static final String OR_LUNA = "OPENROUTER:openai/gpt-6-luna";
    private static final String KIMI_K3_1_PRO = "MOONSHOT:kimi-k3.1-pro";
    private static final String GEMINI_4_HYDROGEN = "GEMINI:gemini-4-hydrogen";
    private static final String GEMINI_4_CARBON = "GEMINI:gemini-4-carbon";
    private static final String GEMINI_3_8_FLASH = "GEMINI:gemini-3.8-flash";
    private static final String GROK_4_7 = "GROK:grok-4.7";
    private static final String GLM_5_3 = "GLM:glm-5.3";
    private static final String GLM_5_3_FLASH = "GLM:glm-5.3-flash";
    private static final String JEV = "TYPESAFE:jev-latest";
    private static final String OR_JEV = "OPENROUTER:~typesafe/jev-latest";

    /**
     * Last on every lane: "whatever this endpoint serves" is never a better guess than a known model, and it carries no
     * rate to warn on.
     */
    private static final String CUSTOM_MODEL = "CUSTOM:custom-model";

    /** After every org-supplied provider, so adding a key of its own moves every unpinned lane onto it. */
    private static final String PLATFORM_SONNET_5_5 = "PLATFORM:claude-sonnet-5-5";

    private static final Map<ModelLane, List<ProviderOption>> BY_LANE = byLane();

    private static Map<ModelLane, List<ProviderOption>> byLane() {
        Map<ModelLane, List<ProviderOption>> m = new EnumMap<>(ModelLane.class);
        // Ordered by fitness for a long tool loop over a repository; Bedrock leads because Sonnet is what this lane has
        // run on.
        List<ProviderOption> agentVmOrder = List.of(
                new ProviderOption(ModelProvider.BEDROCK, List.of(SONNET_5_5, HAIKU_5_5, OPUS_5_5), SONNET_5_5),
                // The same model on a different transport, so it comes directly after Bedrock.
                new ProviderOption(
                        ModelProvider.ANTHROPIC,
                        List.of(ANTHROPIC_SONNET_5_5, ANTHROPIC_HAIKU_5_5, ANTHROPIC_OPUS_5_5),
                        ANTHROPIC_SONNET_5_5),
                new ProviderOption(ModelProvider.OPENAI, List.of(GPT_6_1_SOL, GPT_6_1_TERRA, GPT_6_LUNA), GPT_6_1_SOL),
                new ProviderOption(ModelProvider.BEDROCK_MANTLE, List.of(MANTLE_SOL, MANTLE_LUNA), MANTLE_SOL),
                new ProviderOption(
                        ModelProvider.GEMINI,
                        List.of(GEMINI_4_HYDROGEN, GEMINI_4_CARBON, GEMINI_3_8_FLASH),
                        GEMINI_4_HYDROGEN),
                new ProviderOption(ModelProvider.GROK, List.of(GROK_4_7), GROK_4_7),
                new ProviderOption(ModelProvider.GLM, List.of(GLM_5_3, GLM_5_3_FLASH), GLM_5_3),
                new ProviderOption(ModelProvider.MOONSHOT, List.of(KIMI_K3_1_PRO), KIMI_K3_1_PRO),
                // Aggregators add a hop and a second price book, so they follow the direct routes.
                new ProviderOption(ModelProvider.OPENROUTER, List.of(OR_SOL, OR_LUNA), OR_SOL),
                new ProviderOption(ModelProvider.CUSTOM, List.of(CUSTOM_MODEL), CUSTOM_MODEL),
                new ProviderOption(ModelProvider.PLATFORM, List.of(PLATFORM_SONNET_5_5), PLATFORM_SONNET_5_5));
        m.put(ModelLane.RCA, agentVmOrder);
        m.put(ModelLane.TRIAGE, agentVmOrder);
        m.put(ModelLane.AGENT_RUN, agentVmOrder);
        m.put(
                ModelLane.FRUSTRATION,
                List.of(
                        new ProviderOption(ModelProvider.TYPESAFE, List.of(JEV), JEV),
                        new ProviderOption(ModelProvider.OPENROUTER, List.of(OR_JEV), OR_JEV)));
        return Collections.unmodifiableMap(m);
    }

    private LanePriority() {}

    public static List<ProviderOption> of(ModelLane lane) {
        return BY_LANE.get(lane);
    }

    public static Optional<ProviderOption> forProvider(ModelLane lane, ModelProvider provider) {
        return of(lane).stream().filter(o -> o.provider() == provider).findFirst();
    }

    public static List<String> modelKeys(ModelLane lane) {
        return of(lane).stream().flatMap(o -> o.modelKeys().stream()).toList();
    }
}
