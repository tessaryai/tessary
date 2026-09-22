// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which provider each {@link ModelLane} reaches for, in order, and which model it runs there.
 *
 * <p><b>Provider first, model second.</b> A key is the thing an org either has or does not have, so
 * it is the first question: walk this lane's providers in order and take the first one the org holds
 * a credential for. The model is the second question, answered inside that provider by its
 * {@link ProviderOption#defaultModelKey()}. Doing it the other way — one flat list of models — let a
 * provider's second-best model outrank another provider's best, which is not a choice anyone would
 * make deliberately.
 *
 * <p>There is no per-lane default model, and that is the point. A default named one model, which
 * named one provider, which the org may have no key for — so the settings page's first option was a
 * model the project could not run, followed by an instruction to go and buy the key that would make
 * the option true. Configure Bedrock and RCA lands on Claude Sonnet 5; configure only xAI and it
 * lands on Grok 4.6; configure nothing and the lane has no model at all, which is the honest answer
 * rather than a model id that will fail on the first call.
 *
 * <p><b>Each provider carries every model we support for that lane</b>, with one of them the default.
 * The default is what automatic selection takes; the rest are what the dropdown offers once someone
 * opens it. So a project that wants Haiku on RCA does not have to leave Bedrock to get it.
 *
 * <p><b>TRIAGE is exactly RCA</b>: the same providers, in the same order, with the same defaults.
 * TRIAGE used to carry its own price-ceiling'd list and cheaper defaults; that ceiling is gone. A
 * project that never chose a triage model now runs whatever RCA would, because there is no honest
 * argument that an unattended ruling deserves a worse model than the same finding would get if a
 * person asked RCA about it — the ceiling existed to bound an unattended bill, not because triage's
 * job needs less. Picking a model priced above its own provider's default is still allowed on
 * either lane; the settings page warns before saving one on TRIAGE, reading live rates rather than a
 * fixed threshold, since a project running triage unattended, once per cause, is the one that pays
 * for that choice on every finding rather than once.
 *
 * <p><b>Every chat provider appears on both agent lanes. All ten of them.</b> That is the coverage
 * rule, and it is literal rather than qualified: whichever single chat key an org happens to hold,
 * both lanes resolve to something rather than to nothing. Most providers also carry a smaller
 * current-generation model alongside their flagship — not because either lane defaults to it, but
 * because it stays selectable for a project that wants to point either lane, and especially
 * unattended TRIAGE, at something cheaper than the default on purpose.
 *
 * <p>{@link ModelProvider#ANTHROPIC}, {@link ModelProvider#OPENROUTER} and
 * {@link ModelProvider#MOONSHOT} were absent from both lanes until this change, for a reason that
 * was never a decision about the models: {@code sandbox-runner/launcher/server.js} had no provider
 * mode for them, so a lane pointed at one would have failed at launch. Since
 * {@link LaneGroup#LLM_CALLS} has had no members, that left an org holding only one of
 * those three keys unable to run anything at all. The launcher now has a mode for each — Anthropic
 * on its own wire, the other two as OpenAI-compat — so the coverage rule reaches every provider the
 * Providers page will sell you. Adding an eleventh still means adding it to the launcher first.
 *
 * <p><b>FRUSTRATION is a {@link LaneGroup#DECISION_CALLS} lane</b> and stands outside the coverage rule:
 * only TypeSafe and OpenRouter serve TypeSafe's Jev, one model each. TypeSafe leads because it is the
 * model's own endpoint; OpenRouter is the same model one hop further away.
 *
 * <p>Model keys are the two spellings {@link ProjectModelSettings} decodes: a dotted
 * {@link BedrockModelProfile} key, or {@code "<PROVIDER>:<model_name>"} for a {@link ModelCatalog}
 * entry. {@link ModelCatalog}'s class-load check refuses to start if what a lane here names and what
 * its group offers are not the same set, so a model can neither go missing from a lane nor be ordered
 * into existence.
 */
public final class LanePriority {

    /**
     * One provider's standing on one lane: every model we support there, and which of them automatic
     * selection takes. {@code defaultModelKey} is always a member of {@code modelKeys} — checked at
     * class load below, because a default outside its own list would be selectable automatically and
     * unpickable by hand.
     */
    public record ProviderOption(ModelProvider provider, List<String> modelKeys, String defaultModelKey) {}

    // Bedrock Converse.
    private static final String SONNET_5 = "anthropic.claude-sonnet-5";
    private static final String HAIKU_4_5 = "anthropic.claude-haiku-4-5";
    // Bedrock's mantle endpoint — the only place the GPT-5.6 line is Bedrock-hosted.
    private static final String TERRA = "openai.gpt-5.6-terra";
    private static final String MANTLE_LUNA = "openai.gpt-5.6-luna";
    // Anthropic direct — the same two models Bedrock serves, on Anthropic's own wire and price book.
    private static final String ANTHROPIC_SONNET_5 = "ANTHROPIC:claude-sonnet-5";
    private static final String ANTHROPIC_HAIKU_4_5 = "ANTHROPIC:claude-haiku-4-5";
    // The OpenAI-compatible providers, current generation, one model at each of the two sizes.
    // GPT-6 Sol ($2/$10) and not the $4/$20 flagship is what RCA takes here.
    private static final String GPT_6_SOL = "OPENAI:gpt-6-sol";
    private static final String GPT_5_6 = "OPENAI:gpt-5.6";
    private static final String GPT_6_LUNA = "OPENAI:gpt-6-luna";
    // The same GPT-6 pair over OpenRouter. Its model NAMES carry a slash of their own
    // ("openai/gpt-6-sol" is one name, not provider + model) — the launcher's toProviderModel
    // has an OpenRouter branch for exactly that.
    private static final String OR_SOL = "OPENROUTER:openai/gpt-6-sol";
    private static final String OR_LUNA = "OPENROUTER:openai/gpt-6-luna";
    // Moonshot direct. One model at one size, so it is both this provider's RCA and TRIAGE answer.
    private static final String KIMI_K2_6 = "MOONSHOT:kimi-k2.6";
    private static final String GEMINI_3_1_PRO = "GEMINI:gemini-3.1-pro-preview";
    private static final String GEMINI_3_7_FLASH = "GEMINI:gemini-3.7-flash";
    private static final String GROK_4_6 = "GROK:grok-4.6";
    private static final String GROK_CODE_FAST = "GROK:grok-code-fast-1";
    private static final String GLM_5_3 = "GLM:glm-5.3";
    private static final String GLM_5_3_FLASH = "GLM:glm-5.3-flash";
    // TypeSafe's Jev decision model, direct and over OpenRouter.
    private static final String JEV = "TYPESAFE:jev-latest";
    private static final String OR_JEV = "OPENROUTER:typesafe/jev-latest";

    /**
     * Last on every lane, because the entry stands for "whatever model this endpoint serves" rather
     * than for a model — see {@link ModelCatalog}'s CUSTOM comment. Auto-selecting an unknown model
     * over a known one is never the better guess; an org that configures only a custom endpoint still
     * gets it, since by then it is the only provider left. A customer's own endpoint also carries no
     * rate we can read, so the settings page has no price to warn on for it.
     */
    private static final String CUSTOM_MODEL = "CUSTOM:custom-model";

    private static final Map<ModelLane, List<ProviderOption>> BY_LANE = byLane();

    private static Map<ModelLane, List<ProviderOption>> byLane() {
        Map<ModelLane, List<ProviderOption>> m = new EnumMap<>(ModelLane.class);
        // Providers ordered by their flagship's fitness for a long tool loop over a repository.
        // Bedrock leads because Sonnet 5 is the model this lane has actually been run on. TRIAGE
        // shares this list verbatim (see the class javadoc's "TRIAGE is exactly RCA"): both lanes run
        // the same agent shape, so there is no second ordering to maintain.
        List<ProviderOption> agentVmOrder = List.of(
                new ProviderOption(ModelProvider.BEDROCK, List.of(SONNET_5, HAIKU_4_5), SONNET_5),
                // Directly after Bedrock, because it is the SAME model on a different
                // transport — if Sonnet 5 is what this lane has been run on, the route to it
                // is not what should decide second place.
                new ProviderOption(
                        ModelProvider.ANTHROPIC, List.of(ANTHROPIC_SONNET_5, ANTHROPIC_HAIKU_4_5), ANTHROPIC_SONNET_5),
                new ProviderOption(ModelProvider.OPENAI, List.of(GPT_6_SOL, GPT_5_6, GPT_6_LUNA), GPT_6_SOL),
                new ProviderOption(ModelProvider.BEDROCK_MANTLE, List.of(TERRA, MANTLE_LUNA), TERRA),
                new ProviderOption(ModelProvider.GEMINI, List.of(GEMINI_3_1_PRO, GEMINI_3_7_FLASH), GEMINI_3_1_PRO),
                new ProviderOption(ModelProvider.GROK, List.of(GROK_4_6, GROK_CODE_FAST), GROK_4_6),
                new ProviderOption(ModelProvider.GLM, List.of(GLM_5_3, GLM_5_3_FLASH), GLM_5_3),
                // Both after the direct routes to the models they resolve to: an aggregator
                // adds a hop and a second price book to the same weights, and Kimi K2.6 is a
                // smaller model than every flagship above it.
                new ProviderOption(ModelProvider.OPENROUTER, List.of(OR_SOL, OR_LUNA), OR_SOL),
                new ProviderOption(ModelProvider.MOONSHOT, List.of(KIMI_K2_6), KIMI_K2_6),
                new ProviderOption(ModelProvider.CUSTOM, List.of(CUSTOM_MODEL), CUSTOM_MODEL));
        m.put(ModelLane.RCA, agentVmOrder);
        m.put(ModelLane.TRIAGE, agentVmOrder);
        m.put(
                ModelLane.FRUSTRATION,
                List.of(
                        new ProviderOption(ModelProvider.TYPESAFE, List.of(JEV), JEV),
                        new ProviderOption(ModelProvider.OPENROUTER, List.of(OR_JEV), OR_JEV)));
        for (ModelLane lane : ModelLane.values()) {
            List<ProviderOption> options = m.get(lane);
            if (options == null || options.isEmpty()) {
                throw new IllegalStateException("no provider order declared for lane " + lane);
            }
            for (ProviderOption o : options) {
                if (!o.modelKeys().contains(o.defaultModelKey())) {
                    throw new IllegalStateException("lane " + lane + " defaults " + o.provider() + " to "
                            + o.defaultModelKey() + ", which is not one of its own models");
                }
            }
        }
        return Collections.unmodifiableMap(m);
    }

    private LanePriority() {}

    /** One lane's providers, best first. Never empty — {@link #byLane} refuses to build a partial map. */
    public static List<ProviderOption> of(ModelLane lane) {
        return BY_LANE.get(lane);
    }

    /** What {@code provider} runs on {@code lane}, or empty when this lane does not reach it. */
    public static Optional<ProviderOption> forProvider(ModelLane lane, ModelProvider provider) {
        return of(lane).stream().filter(o -> o.provider() == provider).findFirst();
    }

    /** Every model {@code lane} offers, in provider order then each provider's own order. */
    public static List<String> modelKeys(ModelLane lane) {
        return of(lane).stream().flatMap(o -> o.modelKeys().stream()).toList();
    }

    /** Every lane's provider ordering, in {@link ModelLane} declaration order. */
    public static Map<ModelLane, List<ProviderOption>> all() {
        return BY_LANE;
    }
}
