// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings page must render with no lane, group or model knowledge of its own, so sections, copy, controls, options
 * and fallbacks all ship from here: a field dropped from this view moves a product decision into a React component
 * where it cannot be validated on write. Kept apart from {@link ProviderCredentialController} so who is billed stays
 * clear.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/model-settings")
public class ProjectModelSettingController {

    private final ProjectModelSettings settings;
    private final TenantPathResolver resolver;
    private final ModelResolver priceModels;
    private final PriceBookRepository priceBooks;
    private final PlatformProviderSupplier platformSupplier;

    public ProjectModelSettingController(
            ProjectModelSettings settings,
            TenantPathResolver resolver,
            ModelResolver priceModels,
            PriceBookRepository priceBooks,
            PlatformProviderSupplier platformSupplier) {
        this.settings = settings;
        this.resolver = resolver;
        this.priceModels = priceModels;
        this.priceBooks = priceBooks;
        this.platformSupplier = platformSupplier;
    }

    /**
     * Sent as its own list so a section is drawn once in a defined order. {@code modelSelectable} is false for
     * {@link LaneGroup#DECISION_CALLS}: each provider serves one decision model there.
     */
    public record GroupView(
            LaneGroup id,
            String label,
            String description,
            @JsonProperty("model_selectable") boolean modelSelectable) {}

    /**
     * {@code providerOptions} is ordered provider first and best first ({@link LanePriority}); the client narrows it to
     * {@code configured_providers} but never re-orders it, because the order is the fallback rule. A null
     * {@code effectiveModelKey} means the org has no key for any model this lane offers.
     */
    public record LaneView(
            ModelLane id,
            String label,
            String description,
            LaneGroup group,
            @JsonProperty("provider_options") List<ProviderOptionView> providerOptions,
            @JsonProperty("effective_model_key") @Nullable String effectiveModelKey,
            boolean automatic) {}

    /** The label ships from here, not the client, so it has one source in {@link PlatformCatalog}. */
    public record ProviderOptionView(
            ModelProvider provider,
            String label,
            @JsonProperty("model_keys") List<String> modelKeys,
            @JsonProperty("default_model_key") String defaultModelKey) {}

    /**
     * Read from the live {@code price_book}, which can move, rather than hardcoded. Either field null means unpriced,
     * which the UI must read as "unknown", never as free.
     */
    public record ModelRateView(
            @JsonProperty("model_key") String modelKey,

            @JsonProperty("input_rate_per_million") @Nullable
            BigDecimal inputRatePerMillion,

            @JsonProperty("output_rate_per_million") @Nullable
            BigDecimal outputRatePerMillion) {}

    /**
     * {@code settings} holds only lanes pinned by hand; every other lane's model is in
     * {@link LaneView#effectiveModelKey}. {@code catalogModels} is the non-Bedrock half of the {@code model_key} union,
     * kept separate because a catalog entry carries no Bedrock endpoint.
     */
    public record ModelSettingsView(
            List<GroupView> groups,
            List<LaneView> lanes,
            List<BedrockModelProfile.ModelDescriptor> models,
            @JsonProperty("catalog_models") List<ModelCatalog.CatalogEntry> catalogModels,
            List<ModelRateView> rates,
            List<ProjectModelSetting> settings,
            /**
             * The picker disables an option whose provider is absent here, rather than letting it fail at save or at
             * run time.
             */
            @JsonProperty("configured_providers") Set<ModelProvider> configuredProviders) {}

    public record SetLaneModelRequest(
            @JsonProperty("model_key") @NotBlank String modelKey) {}

    @GetMapping
    public ApiResponse<ModelSettingsView> get(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        List<GroupView> groups = Arrays.stream(LaneGroup.values())
                .map(g -> new GroupView(g, g.label(), g.description(), g.modelSelectable()))
                .toList();
        // Entries offered on no lane are filtered out here. PLATFORM is left out unless the deployment offers it, since
        // an option the org could never configure would read as a key to go and buy.
        Set<ModelProvider> configured = settings.configuredProviders(r.org().id());
        boolean platformOffered = configured.contains(ModelProvider.PLATFORM);
        List<ModelCatalog.CatalogEntry> offeredCatalog = ModelCatalog.entries().stream()
                .filter(e -> e.agentic() || e.decision())
                .filter(e -> platformOffered || e.provider() != ModelProvider.PLATFORM)
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
                                    .filter(o -> platformOffered || o.provider() != ModelProvider.PLATFORM)
                                    .map(o -> new ProviderOptionView(
                                            o.provider(),
                                            label(r.org().id(), o.provider()),
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
        return ApiResponse.ok(new ModelSettingsView(
                groups,
                lanes,
                models,
                offeredCatalog,
                Stream.concat(
                                models.stream().map(this::rateView),
                                offeredCatalog.stream().map(this::rateView))
                        .toList(),
                settings.list(r.project().id()),
                configured));
    }

    private String label(String orgId, ModelProvider provider) {
        String catalogLabel = PlatformCatalog.find(provider).orElseThrow().label();
        if (provider != ModelProvider.PLATFORM) return catalogLabel;
        return platformSupplier
                .describe(orgId)
                .map(PlatformProviderSupplier.SuppliedProvider::label)
                .orElse(catalogLabel);
    }

    /**
     * Best-effort: an unresolvable or unpriced model reports null rates rather than failing the whole page over one
     * row.
     */
    private ModelRateView rateView(BedrockModelProfile.ModelDescriptor d) {
        return rateView(d.modelKey(), d.inferenceProfileId());
    }

    /** Looked up by {@link ModelCatalog#pricingId}, not the bare model name a sandbox run reports under. */
    private ModelRateView rateView(ModelCatalog.CatalogEntry entry) {
        return rateView(
                ModelCatalog.key(entry),
                entry.decision()
                        ? ModelCatalog.decisionPricingId(entry.provider(), entry.modelName())
                        : ModelCatalog.pricingId(entry.provider(), entry.modelName()));
    }

    private ModelRateView rateView(String modelKey, String pricedName) {
        Optional<ai.tessary.pricing.ModelRate> rate =
                priceModels.resolve(pricedName).flatMap(priceBooks::rateFor);
        return new ModelRateView(
                modelKey,
                rate.map(r -> r.rates().inputPerMtok()).orElse(null),
                rate.map(r -> r.rates().outputPerMtok()).orElse(null));
    }

    /**
     * Validated here because an unrunnable pair would otherwise be accepted silently and then fail every run in that
     * lane.
     */
    @PutMapping("/{lane}")
    public ApiResponse<ModelSettingsView> put(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String lane,
            @RequestBody SetLaneModelRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        // Parsed via fromWire, not Spring's enum binding: the path segment is the lowercase wire name, and an unknown
        // one must be our typed 400, not a 500.
        ModelLane parsed = ModelLane.fromWire(lane);
        settings.set(r.project().id(), r.org().id(), parsed, req.modelKey());
        return get(ctx, orgSlug, projectSlug);
    }

    /** Returns the refreshed view, since the cleared lane may now run a different model. */
    @DeleteMapping("/{lane}")
    public ApiResponse<ModelSettingsView> reset(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String lane) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        settings.clear(r.project().id(), ModelLane.fromWire(lane));
        return get(ctx, orgSlug, projectSlug);
    }
}
