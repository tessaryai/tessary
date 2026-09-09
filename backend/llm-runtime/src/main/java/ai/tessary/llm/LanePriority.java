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
 * <p><b>This is per lane, not per {@link LaneGroup}, and that is load-bearing.</b> RCA and TRIAGE
 * share {@link LaneGroup#AGENT_VM}, so the group permits the same models to both; what each lane
 * actually offers is decided here. RCA runs once per mover over a whole repository and takes every
 * model the group permits, its providers ordered by how well their flagship is expected to hold a
 * long tool loop.
 *
 * <p><b>TRIAGE offers only models at or under $1 per million input tokens and $5 per million output
 * tokens</b>, its providers ordered cheapest first. It runs unattended, once per distinct cause, over
 * every finding that clears the recurrence bar, so an expensive model there is a bill that arrives
 * without anyone choosing it. The ceiling is a hard offer-list rule and not advice: a frontier model
 * is not in the dropdown, and a raw PUT naming one is refused by
 * {@link ProjectModelSettings#validate}. Prices come from the vendored LiteLLM book
 * ({@code substrate/pricing/litellm-model-prices.json}), and because a static list cannot notice the
 * book moving under it, the settings page still warns at the same threshold before a save.
 *
 * <p><b>Every provider appears on both lanes. All ten of them.</b> That is the coverage rule, and it
 * is now literal rather than qualified: whichever single key an org happens to hold, both lanes
 * resolve to something rather than to nothing. It is why each provider also contributes a small
 * model — the ceiling below leaves TRIAGE nothing to run otherwise. xAI is the case that shows the
 * rule has teeth: every {@code grok-4.x} chat model starts at $1.25 input, so TRIAGE reaches xAI
 * through its coding tier or not at all.
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
 * <p>Model keys are the two spellings {@link ProjectModelSettings} decodes: a dotted
 * {@link BedrockModelProfile} key, or {@code "<PROVIDER>:<model_name>"} for a {@link ModelCatalog}
 * entry. {@link ModelCatalog}'s class-load check refuses to start if what the lanes here name and
 * what their group offers are not the same set, so a model can neither go missing from the ordering
 * nor be ordered into existence.
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
    // Terra is the third route to the same model as the mantle TERRA above, at the lowest of the
    // three prices ($2/$12), which is why it and not the $4/$20 flagship is what RCA takes here.
    private static final String GPT_5_6_TERRA = "OPENAI:gpt-5.6-terra";
    private static final String GPT_5_6 = "OPENAI:gpt-5.6";
    private static final String GPT_5_6_LUNA = "OPENAI:gpt-5.6-luna";
    // The same GPT-5.6 pair over OpenRouter. Its model NAMES carry a slash of their own
    // ("openai/gpt-5.6-terra" is one name, not provider + model) — the launcher's toProviderModel
    // has an OpenRouter branch for exactly that.
    private static final String OR_TERRA = "OPENROUTER:openai/gpt-5.6-terra";
    private static final String OR_LUNA = "OPENROUTER:openai/gpt-5.6-luna";
    // Moonshot direct. One model at one size, so it is both this provider's RCA and TRIAGE answer.
    private static final String KIMI_K2_6 = "MOONSHOT:kimi-k2.6";
    private static final String GEMINI_3_1_PRO = "GEMINI:gemini-3.1-pro-preview";
    private static final String GEMINI_3_7_FLASH = "GEMINI:gemini-3.7-flash";
    private static final String GROK_4_6 = "GROK:grok-4.6";
    private static final String GROK_CODE_FAST = "GROK:grok-code-fast-1";
    private static final String GLM_5_3 = "GLM:glm-5.3";
    private static final String GLM_5_3_FLASH = "GLM:glm-5.3-flash";

    /**
     * Last on every lane, because the entry stands for "whatever model this endpoint serves" rather
     * than for a model — see {@link ModelCatalog}'s CUSTOM comment. Auto-selecting an unknown model
     * over a known one is never the better guess; an org that configures only a custom endpoint still
     * gets it, since by then it is the only provider left. It is also the one model on TRIAGE whose
     * price this codebase cannot check: a customer's own endpoint carries no rate we can read, so the
     * ceiling is enforced on every priced model and taken on trust for this one.
     */
    private static final String CUSTOM_MODEL = "CUSTOM:custom-model";

    private static final Map<ModelLane, List<ProviderOption>> BY_LANE = byLane();

    private static Map<ModelLane, List<ProviderOption>> byLane() {
        Map<ModelLane, List<ProviderOption>> m = new EnumMap<>(ModelLane.class);
        // RCA: providers ordered by their flagship's fitness for a long tool loop over a repository.
        // Bedrock leads because Sonnet 5 is the model this lane has actually been run on.
        m.put(
                ModelLane.RCA,
                List.of(
                        new ProviderOption(ModelProvider.BEDROCK, List.of(SONNET_5, HAIKU_4_5), SONNET_5),
                        // Directly after Bedrock, because it is the SAME model on a different
                        // transport — if Sonnet 5 is what this lane has been run on, the route to it
                        // is not what should decide second place.
                        new ProviderOption(
                                ModelProvider.ANTHROPIC,
                                List.of(ANTHROPIC_SONNET_5, ANTHROPIC_HAIKU_4_5),
                                ANTHROPIC_SONNET_5),
                        new ProviderOption(
                                ModelProvider.OPENAI, List.of(GPT_5_6_TERRA, GPT_5_6, GPT_5_6_LUNA), GPT_5_6_TERRA),
                        new ProviderOption(ModelProvider.BEDROCK_MANTLE, List.of(TERRA, MANTLE_LUNA), TERRA),
                        new ProviderOption(
                                ModelProvider.GEMINI, List.of(GEMINI_3_1_PRO, GEMINI_3_7_FLASH), GEMINI_3_1_PRO),
                        new ProviderOption(ModelProvider.GROK, List.of(GROK_4_6, GROK_CODE_FAST), GROK_4_6),
                        new ProviderOption(ModelProvider.GLM, List.of(GLM_5_3, GLM_5_3_FLASH), GLM_5_3),
                        // Both after the direct routes to the models they resolve to: an aggregator
                        // adds a hop and a second price book to the same weights, and Kimi K2.6 is a
                        // smaller model than every flagship above it.
                        new ProviderOption(ModelProvider.OPENROUTER, List.of(OR_TERRA, OR_LUNA), OR_TERRA),
                        new ProviderOption(ModelProvider.MOONSHOT, List.of(KIMI_K2_6), KIMI_K2_6),
                        new ProviderOption(ModelProvider.CUSTOM, List.of(CUSTOM_MODEL), CUSTOM_MODEL)));
        // TRIAGE: providers ordered by the price of the model each one runs here, cheapest first, and
        // nothing above $1 in / $5 out per MTok. Per MTok in/out, from the vendored LiteLLM book:
        // GLM-5.3 Flash 0.15/0.50, Luna direct 0.20/1.20, Luna over mantle 0.22/1.32, Gemini 3.7 Flash
        // 0.75/3.75, Kimi K2.6 0.95/4.00, Grok Code Fast 1 1.00/2.00, Claude Haiku 4.5 1.00/5.00 on
        // both of the two routes that serve it. Haiku sits exactly on the ceiling, which is why it is
        // last of the priced eight rather than excluded — the bound is inclusive. Every provider's
        // flagship is over the ceiling and so is absent here entirely.
        //
        // The two UNPRICED entries sort last, after every model the book can actually check. CUSTOM
        // has always been one (a customer's own endpoint carries no rate we can read). OpenRouter's
        // Luna is the new one, and for a duller reason: the vendored book's OpenRouter OpenAI rows
        // stop at gpt-5.2, so there is no `openrouter/openai/gpt-5.6-luna` row to price it by. Both
        // are taken on trust the same way, and putting them behind everything priced is what keeps
        // automatic selection from ever preferring an unknown cost to a known one.
        m.put(
                ModelLane.TRIAGE,
                List.of(
                        new ProviderOption(ModelProvider.GLM, List.of(GLM_5_3_FLASH), GLM_5_3_FLASH),
                        new ProviderOption(ModelProvider.OPENAI, List.of(GPT_5_6_LUNA), GPT_5_6_LUNA),
                        new ProviderOption(ModelProvider.BEDROCK_MANTLE, List.of(MANTLE_LUNA), MANTLE_LUNA),
                        new ProviderOption(ModelProvider.GEMINI, List.of(GEMINI_3_7_FLASH), GEMINI_3_7_FLASH),
                        new ProviderOption(ModelProvider.MOONSHOT, List.of(KIMI_K2_6), KIMI_K2_6),
                        new ProviderOption(ModelProvider.GROK, List.of(GROK_CODE_FAST), GROK_CODE_FAST),
                        // Tied with Bedrock's Haiku at exactly 1.00/5.00 — the same model, the same
                        // rate, two routes. Direct first, on the same reasoning as RCA's ordering.
                        new ProviderOption(ModelProvider.ANTHROPIC, List.of(ANTHROPIC_HAIKU_4_5), ANTHROPIC_HAIKU_4_5),
                        new ProviderOption(ModelProvider.BEDROCK, List.of(HAIKU_4_5), HAIKU_4_5),
                        new ProviderOption(ModelProvider.OPENROUTER, List.of(OR_LUNA), OR_LUNA),
                        new ProviderOption(ModelProvider.CUSTOM, List.of(CUSTOM_MODEL), CUSTOM_MODEL)));
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
