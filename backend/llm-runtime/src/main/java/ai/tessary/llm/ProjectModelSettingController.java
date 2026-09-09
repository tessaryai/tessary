// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which model each platform-funded {@link ModelLane} runs on for a project, and at which
 * {@link ServiceTier}.
 *
 * <p>Sibling of {@link ProviderCredentialController} and deliberately separate from it: that one is
 * "keys for models you bring and pay for", this one is "which of the platform's own models each of
 * our lanes uses". Mixing them would blur who is being billed.
 *
 * <p>The {@code GET} returns the capability matrix alongside the current settings, so the UI can
 * render tier options per model generically, the same shape as
 * {@link ProviderCredentialController#catalog}, and the reason the frontend needs no hardcoded
 * knowledge of which models support Flex.
 *
 * <p>That claim is meant literally and is the constraint on this payload: the settings page must be
 * renderable with no lane, group or model knowledge of its own. So the sections it draws, the copy
 * under each heading, which controls a section shows, which models a lane offers and what it falls
 * back to all ship from here. A field dropped from this view does not simplify the wire, it moves a
 * product decision into a React component where it cannot be validated on write.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/model-settings")
public class ProjectModelSettingController {

    private final ProjectModelSettings settings;
    private final ChatModelFactory factory;
    private final TenantPathResolver resolver;
    private final ModelResolver priceModels;
    private final PriceBookRepository priceBooks;
    /** Which providers the org has a credential for; drives the picker's disabled options. */
    private final ProviderCredentialRepository providerCredentials;

    public ProjectModelSettingController(
            ProjectModelSettings settings,
            ChatModelFactory factory,
            TenantPathResolver resolver,
            ModelResolver priceModels,
            PriceBookRepository priceBooks,
            ProviderCredentialRepository providerCredentials) {
        this.settings = settings;
        this.factory = factory;
        this.resolver = resolver;
        this.priceModels = priceModels;
        this.priceBooks = priceBooks;
        this.providerCredentials = providerCredentials;
    }

    /**
     * One section of the settings page: a {@link LaneGroup}, its heading, the line of copy under it,
     * and which of the two per-request controls its rows should show at all.
     *
     * <p>Sent as its own list rather than folded into each lane because a section is drawn once:
     * repeating the heading and copy on all five lanes would invite a client to render whichever copy
     * it saw last and would make the section order an accident of lane order.
     *
     * <p>{@code tiered} and {@code effortTunable} are both false for {@link LaneGroup#AGENT_VM}: those
     * lanes hand a model id to an agent inside a microVM and the agent composes every request, so
     * neither control has anything to attach to. They are two fields rather than one because they are
     * two controls; today they agree, and {@link LaneGroup} is where that stops being true if it ever
     * does.
     */
    public record GroupView(
            LaneGroup id,
            String label,
            String description,
            boolean tiered,
            @JsonProperty("effort_tunable") boolean effortTunable) {}

    /**
     * One selectable lane, so the UI renders its row without hardcoding labels, copy, options or
     * defaults.
     *
     * <p>{@code group} is the section this row belongs under, as the id of a {@link GroupView} above.
     * It replaced a bare {@code tiered} boolean, which was the same fact told one control at a time:
     * the group answers the tier question, the effort question and the section question together, and
     * it is the grouping the page is actually built from.
     *
     * <p>{@code providerOptions} is this lane's option list, provider first and best first; see
     * {@link LanePriority}. Each entry names a provider, the models this lane offers on it (indexing
     * into {@code models}, and for an {@link LaneGroup#AGENT_VM} lane into {@code catalog_models} too),
     * and which of them automatic selection takes. Grouped by provider rather than sent flat because
     * that is the shape of the choice: a key is what an org has or does not have, so the page asks for
     * a provider and then for one of its models. The client narrows the list to
     * {@code configured_providers} and shows nothing else; it never re-orders it, because the order is
     * the fallback rule.
     *
     * <p>{@code effectiveModelKey} is what the lane runs right now, and {@code automatic} says which
     * of the two ways it got there: the project's own row, or the priority order resolved against the
     * providers the org has keys for. Null means the org has no key for any model this lane offers, so
     * the lane runs nothing and the page asks for a provider instead of a model. There is no default
     * model to name here: a default named a provider the org might not have, which is how this page
     * came to offer an option that could not be chosen and an instruction to go and buy the key that
     * would make it true.
     */
    public record LaneView(
            ModelLane id,
            String label,
            String description,
            LaneGroup group,
            @JsonProperty("provider_options") List<ProviderOptionView> providerOptions,
            @JsonProperty("effective_model_key") @Nullable String effectiveModelKey,
            boolean automatic) {}

    /**
     * One provider's standing on one lane: its human label, the models this lane offers on it, and the
     * one automatic selection takes.
     *
     * <p>The label ships from here rather than being spelled in the client for the same reason every
     * other string on this page does: it is {@link PlatformCatalog}'s, and a second copy in a React
     * component is a second answer that drifts.
     */
    public record ProviderOptionView(
            ModelProvider provider,
            String label,
            @JsonProperty("model_keys") List<String> modelKeys,
            @JsonProperty("default_model_key") String defaultModelKey) {}

    /**
     * One model's live per-MTok rate, read from the same {@code price_book}
     * {@link ai.tessary.pricing.PlatformCallPricer} prices a completed sandbox run from: not a static
     * capability, so it does not belong on {@link BedrockModelProfile.ModelDescriptor}. Powers the
     * settings page's price-gated warning on the TRIAGE lane: a project must never hardcode "$1 / $5"
     * as Haiku's rate, because the book can move.
     *
     * <p>Either field null means unpriced (no book in force carries a rate for this model), which the
     * UI must read as "unknown", never as free, the same convention
     * {@link ai.tessary.pricing.ModelRates} documents.
     */
    public record ModelRateView(
            @JsonProperty("model_key") String modelKey,

            @JsonProperty("input_rate_per_million") @Nullable
            BigDecimal inputRatePerMillion,

            @JsonProperty("output_rate_per_million") @Nullable
            BigDecimal outputRatePerMillion) {}

    /**
     * The settings page's whole payload: the sections to draw, the lanes to render under them (each
     * carrying its own options, in order, and what it currently runs), the full model catalogue the
     * keys index into, each model's live rate, and the project's own explicit choices.
     * {@code settings} holds only lanes someone has pinned by hand; every other lane's model is in its
     * {@link LaneView#effectiveModelKey}, resolved from the priority order and the org's keys.
     *
     * <p>{@code catalogModels} is the non-Bedrock half of the {@code model_key} union (see
     * {@link ProjectModelSettings}'s class javadoc): the agentic {@link ModelCatalog} entries
     * (GEMINI/GLM/GROK/CUSTOM) an {@link LaneGroup#AGENT_VM} lane may also be pointed at, keyed the
     * same {@code "<PROVIDER>:<model_name>"} way {@link ProjectModelSettings#set} accepts. Kept as
     * its own list rather than folded into {@code models} because the two are genuinely different
     * shapes (a catalog entry carries no Bedrock capability fields: tiers, cache TTLs, endpoint), and
     * because {@link ProviderCredentialController#catalog} already exposes this exact record over the
     * wire, so reusing it here adds no new schema.
     */
    public record ModelSettingsView(
            List<GroupView> groups,
            List<LaneView> lanes,
            List<BedrockModelProfile.ModelDescriptor> models,
            @JsonProperty("catalog_models") List<ModelCatalog.CatalogEntry> catalogModels,
            List<ModelRateView> rates,
            List<ProjectModelSetting> settings,
            /**
             * The providers the org has a credential for: the picker disables any option whose
             * provider is absent here, with a link to Settings → Providers, rather than letting
             * the pair be selected and only failing at save (PUT, {@link ModelConfigError
             * #PROVIDER_NOT_CONFIGURED}) or at run time (resolve, {@link ModelConfigError
             * #MISSING_CREDENTIALS}). A model whose descriptor names no {@link ModelProvider} at all
             * (there is none today, but the field is optional per model, not derived) is never
             * disabled by this check.
             */
            @JsonProperty("configured_providers") Set<ModelProvider> configuredProviders) {}

    /**
     * {@code model_key} + {@code service_tier} + {@code reasoning_effort}; the tier defaults to
     * Standard when omitted, and an omitted/blank effort means "no reasoning parameter", which is the
     * only valid value for a model that takes none. The levels a model accepts ride on its
     * {@code effort_levels} in the {@code models} array, so the client never hardcodes them.
     */
    public record SetLaneModelRequest(
            @JsonProperty("model_key") @NotBlank String modelKey,
            @JsonProperty("service_tier") ServiceTier serviceTier,
            @JsonProperty("reasoning_effort") @Nullable String reasoningEffort) {}

    @GetMapping
    public ApiResponse<ModelSettingsView> get(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        List<GroupView> groups = Arrays.stream(LaneGroup.values())
                .map(g -> new GroupView(g, g.label(), g.description(), g.tiered(), g.effortTunable()))
                .toList();
        // The agentic ModelCatalog entries (GEMINI/GLM/GROK/CUSTOM) an AGENT_VM lane may also be
        // pointed at; see ModelSettingsView#catalogModels's javadoc. Non-agentic entries (OpenAI,
        // Anthropic direct, OpenRouter, Ollama, Moonshot, the Bedrock entries already covered by
        // BedrockModelProfile) are never offered on any lane today, so they are filtered out here
        // rather than left for the client to skip.
        List<ModelCatalog.CatalogEntry> agenticCatalog = ModelCatalog.entries().stream()
                .filter(ModelCatalog.CatalogEntry::agentic)
                .toList();
        List<LaneView> lanes = Arrays.stream(ModelLane.values())
                .map(l -> {
                    Optional<ProjectModelSettings.LaneSelection> effective =
                            settings.resolve(r.project().id(), l);
                    return new LaneView(
                            l,
                            l.label(),
                            l.description(),
                            l.group(),
                            LanePriority.of(l).stream()
                                    .map(o -> new ProviderOptionView(
                                            o.provider(),
                                            PlatformCatalog.find(o.provider())
                                                    .map(PlatformCatalog.PlatformDescriptor::label)
                                                    .orElseGet(
                                                            () -> o.provider().name()),
                                            o.modelKeys(),
                                            o.defaultModelKey()))
                                    .toList(),
                            effective
                                    .map(ProjectModelSettings.LaneSelection::modelKey)
                                    .orElse(null),
                            effective
                                    .map(ProjectModelSettings.LaneSelection::automatic)
                                    .orElse(true));
                })
                .toList();
        List<BedrockModelProfile.ModelDescriptor> models = BedrockModelProfile.platformModels();
        Set<ModelProvider> configured = providerCredentials.findByOrg(r.org().id()).stream()
                .map(ProviderCredential::provider)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ModelProvider.class)));
        return ApiResponse.ok(new ModelSettingsView(
                groups,
                lanes,
                models,
                agenticCatalog,
                models.stream().map(this::rateView).toList(),
                settings.list(r.project().id()),
                configured));
    }

    /**
     * One model's rate, resolved the same way {@link ai.tessary.pricing.PlatformCallPricer}
     * resolves a completed call's: {@link ModelResolver#resolve} on the {@code inference_profile_id}
     * (the id the model is priced under, on either endpoint, see that field's javadoc), then a book
     * lookup. Best-effort: an unresolvable or unpriced model reports null rates rather than failing the
     * whole settings page over one row.
     */
    private ModelRateView rateView(BedrockModelProfile.ModelDescriptor d) {
        Optional<ai.tessary.pricing.ModelRate> rate =
                priceModels.resolve(d.inferenceProfileId()).flatMap(priceBooks::rateFor);
        return new ModelRateView(
                d.modelKey(),
                rate.map(r -> r.rates().inputPerMtok()).orElse(null),
                rate.map(r -> r.rates().outputPerMtok()).orElse(null));
    }

    /**
     * Point one lane at a model + tier. The (model, tier) pair is validated against the capability
     * matrix here rather than deferred to Bedrock: an unsupported pair (Flex on Haiku 4.5) would
     * otherwise be accepted silently and then fail every call in that lane.
     */
    @PutMapping("/{lane}")
    public ApiResponse<ModelSettingsView> put(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String lane,
            @RequestBody SetLaneModelRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        // Parsed via fromWire, not Spring's default enum binding: the path segment is the lowercase
        // wire name ("grading"), and an unknown one must surface as our typed 400, not a 500.
        ModelLane parsed = ModelLane.fromWire(lane);
        ServiceTier tier = req.serviceTier() == null ? ServiceTier.STANDARD : req.serviceTier();
        // The org-gated overload: this is an explicit user choice from the picker, which should
        // already have disabled any provider the org has no credential for.
        settings.set(r.project().id(), r.org().id(), parsed, req.modelKey(), tier, req.reasoningEffort());
        // The model cache keys on (model, tier, effort), so a lane that just changed any of them would
        // keep serving the previous client until something else evicted it.
        factory.invalidateAll();
        return get(ctx, orgSlug, projectSlug);
    }

    /**
     * Drop this project's choice for one lane, returning it to the automatic answer: the best model
     * in the lane's priority order that the org's configured providers can serve.
     *
     * <p>Returns the refreshed view, like the {@code PUT}, rather than a boolean ack: the lane it just
     * cleared may now be running a different model, and the caller needs to be told which.
     */
    @DeleteMapping("/{lane}")
    public ApiResponse<ModelSettingsView> reset(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String lane) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        settings.clear(r.project().id(), ModelLane.fromWire(lane));
        // Same reason as the PUT: the model cache keys on (model, tier, effort), so a lane that just
        // moved back to automatic would keep serving the previous client until something evicted it.
        factory.invalidateAll();
        return get(ctx, orgSlug, projectSlug);
    }
}
