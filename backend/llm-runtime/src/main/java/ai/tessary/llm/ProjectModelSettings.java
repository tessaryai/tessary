// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Reads a project's per-lane model choices, with a small write-through cache.
 *
 * <p>{@link #resolve} runs on the path of every sandbox run and decision call, so the cache avoids
 * hitting Postgres for a row that changes roughly never. It is invalidated explicitly on write
 * ({@link #invalidate}) rather than time-bounded, so a settings change takes effect on the next call.
 *
 * <p>Validation lives here, not only in the controller, because a bad choice can arrive two ways: a
 * fresh PUT, or a row written before a capability changed (a model being removed from
 * {@link BedrockModelProfile}). {@link #validate} is the gate for the first; {@link #resolve}
 * degrades safely for the second.
 *
 * <p>A row is an override, never a starting point: nothing writes one but an explicit choice on the
 * settings page. A lane with no row (or with one the org can no longer serve) resolves through
 * {@link LanePriority} against the providers the org has (see {@link #resolve}), so configuring the
 * first provider gives every lane a model, and configuring a better one later moves the lanes that
 * were never pinned by hand.
 *
 * <h2>The {@code model_key} union</h2>
 *
 * <p>{@code project_model_setting.model_key} started as a plain-text {@link BedrockModelProfile}
 * key ({@code amazon.nova-2-lite}); those never carry a colon. An {@link LaneGroup#AGENT_VM} lane
 * can also reach a non-Bedrock {@link ModelCatalog} entry (GEMINI, GLM, GROK, CUSTOM), and a
 * {@link LaneGroup#DECISION_CALLS} lane reaches only decision entries (TYPESAFE, OPENROUTER's Jev),
 * both encoded as {@code "<PROVIDER>:<model_name>"} ({@link #CATALOG_KEY}), a shape no Bedrock key
 * has ever taken so both forms are unambiguous from the string alone. {@link #parseCatalogKey} and
 * {@link #catalogEntryFor} are the only two places that decode it; every other reader calls
 * {@link BedrockModelProfile#find} first and only falls through to the catalog on a miss.
 */
@Service
public class ProjectModelSettings {

    /** Bedrock and mantle keys are dotted and never match this. */
    private static final Pattern CATALOG_KEY = Pattern.compile("^([A-Z_]+):(.+)$");

    private final ProjectModelSettingRepository repo;

    private final ProviderCredentialRepository credentials;

    /** So callers need not pass an orgId they may not have. */
    private final ProjectOrgResolver orgResolver;

    /**
     * Consulted cache-only (see {@link #catalogEntryFor}), so a model only in a provider's live listing still resolves.
     */
    private final ModelCatalogFetchService catalogFetchService;

    private final PlatformProviderSupplier platformSupplier;

    /** An absent lane inherits. */
    private final ConcurrentMap<String, Map<ModelLane, ProjectModelSetting>> cache = new ConcurrentHashMap<>();

    public ProjectModelSettings(
            ProjectModelSettingRepository repo,
            ProviderCredentialRepository credentials,
            ProjectOrgResolver orgResolver,
            ModelCatalogFetchService catalogFetchService,
            PlatformProviderSupplier platformSupplier) {
        this.repo = repo;
        this.credentials = credentials;
        this.orgResolver = orgResolver;
        this.catalogFetchService = catalogFetchService;
        this.platformSupplier = platformSupplier;
    }

    public List<ProjectModelSetting> list(String projectId) {
        return List.copyOf(forProject(projectId).values());
    }

    /**
     * An explicit row the org can still run wins; otherwise {@link LanePriority} picks the best model whose provider
     * the org holds a credential for. An explicit row that stopped being runnable falls back to the automatic answer
     * but stays in the table, so re-adding the credential restores it.
     */
    public Optional<LaneSelection> resolve(String projectId, ModelLane lane) {
        String orgId = orgResolver.orgIdFor(projectId);
        Set<ModelProvider> configured = configuredProviders(orgId);
        ProjectModelSetting row = forProject(projectId).get(lane);
        Optional<LaneSelection> chosen = row == null ? Optional.empty() : usable(orgId, configured, lane, row);
        return chosen.isPresent() ? chosen : autoSelect(configured, lane);
    }

    /** {@code automatic} lets the settings page say which of the two it is showing. */
    public record LaneSelection(
            @JsonProperty("model_key") String modelKey,
            @JsonProperty("service_tier") ServiceTier serviceTier,
            @JsonProperty("reasoning_effort") @Nullable String reasoningEffort,
            boolean automatic) {}

    private Optional<LaneSelection> usable(
            @Nullable String orgId, Set<ModelProvider> configured, ModelLane lane, ProjectModelSetting row) {
        Optional<BedrockModelProfile.ModelDescriptor> bedrock = BedrockModelProfile.find(row.modelKey());
        Optional<ModelCatalog.CatalogEntry> catalog =
                bedrock.isPresent() ? Optional.empty() : catalogEntryFor(orgId, row.modelKey());
        if (bedrock.isEmpty() && catalog.isEmpty()) {
            return Optional.empty();
        }
        if (providerFor(row.modelKey()).filter(configured::contains).isEmpty()) {
            return Optional.empty();
        }
        // Every platform Bedrock model runs at Standard only.
        if (bedrock.isPresent() && row.serviceTier() != ServiceTier.STANDARD) {
            return Optional.empty();
        }
        // A catalog row has no tier. Wrong-kind rows fail isOfferedOn below.
        boolean agentic = bedrock.map(BedrockModelProfile.ModelDescriptor::agentic)
                .orElseGet(() -> catalog.map(ModelCatalog.CatalogEntry::agentic).orElse(false));
        if (lane.agentic() && !agentic) {
            return Optional.empty();
        }
        if (!isOfferedOn(lane, row.modelKey())) {
            return Optional.empty();
        }
        return Optional.of(new LaneSelection(row.modelKey(), row.serviceTier(), null, false));
    }

    /**
     * The first provider in the lane's {@link LanePriority} the org holds a credential for. No lane has a tier or
     * effort control.
     */
    private Optional<LaneSelection> autoSelect(Set<ModelProvider> configured, ModelLane lane) {
        return LanePriority.of(lane).stream()
                .filter(o -> configured.contains(o.provider()))
                .findFirst()
                .map(o -> new LaneSelection(o.defaultModelKey(), ServiceTier.STANDARD, null, true));
    }

    /**
     * Every lane names exactly the static models its group permits, so what the page shows a PUT can save.
     * {@link ModelProvider#CUSTOM} matches by provider: the real model id is on the customer's credential.
     */
    private static boolean isOfferedOn(ModelLane lane, String modelKey) {
        return LanePriority.modelKeys(lane).contains(modelKey)
                || parseCatalogKey(modelKey)
                        .filter(k -> k.provider() == ModelProvider.CUSTOM)
                        .flatMap(k -> LanePriority.forProvider(lane, k.provider()))
                        .isPresent();
    }

    /**
     * The one answer to "is this provider set up". A stored {@code PLATFORM} row never counts, since a build that
     * supplies it to no one must not offer it.
     */
    public Set<ModelProvider> configuredProviders(@Nullable String orgId) {
        if (orgId == null) return EnumSet.noneOf(ModelProvider.class);
        Set<ModelProvider> configured = EnumSet.noneOf(ModelProvider.class);
        for (ProviderCredential c : credentials.findByOrg(orgId)) {
            if (c.provider() != ModelProvider.PLATFORM) configured.add(c.provider());
        }
        if (platformSupplier.available(orgId)) configured.add(ModelProvider.PLATFORM);
        return configured;
    }

    /** Empty means the org has no credential for any provider this lane runs on. */
    public Optional<ResolvedAgenticModel> resolveAgenticModel(String projectId, ModelLane lane) {
        return resolve(projectId, lane).map(selection -> {
            Optional<BedrockModelProfile.ModelDescriptor> bedrock = BedrockModelProfile.find(selection.modelKey());
            if (bedrock.isPresent()) {
                BedrockModelProfile.ModelDescriptor d = bedrock.get();
                ModelProvider provider = d.endpoint() == BedrockModelProfile.Endpoint.MANTLE
                        ? ModelProvider.BEDROCK_MANTLE
                        : ModelProvider.BEDROCK;
                return new ResolvedAgenticModel(
                        provider,
                        d.inferenceProfileId(),
                        ModelCatalog.modelsDevId(provider, d.inferenceProfileId())
                                .orElse(null));
            }
            CatalogKey key = parseCatalogKey(selection.modelKey()).orElseThrow();
            return new ResolvedAgenticModel(
                    key.provider(),
                    key.modelName(),
                    ModelCatalog.modelsDevId(key.provider(), key.modelName()).orElse(null));
        });
    }

    /**
     * {@code modelId} is the name a person chose, what the agent runs and {@code llm_call.model} records.
     * {@code modelsDevId} prices the run; null leaves it unpriced.
     */
    public record ResolvedAgenticModel(
            ModelProvider provider,
            @JsonProperty("model_id") String modelId,
            @JsonProperty("models_dev_id") @Nullable String modelsDevId) {}

    /** Empty when the org holds a key for no provider the lane offers. */
    public Optional<ResolvedDecisionModel> resolveDecisionModel(String projectId, ModelLane lane) {
        return resolve(projectId, lane)
                .flatMap(selection -> parseCatalogKey(selection.modelKey()))
                .map(key -> new ResolvedDecisionModel(key.provider(), key.modelName()));
    }

    public record ResolvedDecisionModel(ModelProvider provider, String modelId) {}

    private record CatalogKey(ModelProvider provider, String modelName) {}

    private static Optional<CatalogKey> parseCatalogKey(@Nullable String modelKey) {
        if (modelKey == null) return Optional.empty();
        var m = CATALOG_KEY.matcher(modelKey.trim());
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(new CatalogKey(ModelProvider.valueOf(m.group(1)), m.group(2)));
        } catch (IllegalArgumentException notAProvider) {
            return Optional.empty();
        }
    }

    /**
     * The {@link ModelCatalog.CatalogEntry} a non-Bedrock {@code model_key} names, or empty when the
     * key isn't one of ours. {@link ModelProvider#CUSTOM} has no real per-model catalog: one
     * representative entry stands for "any model this endpoint serves" (see {@link ModelCatalog}'s
     * own CUSTOM comment), so any non-blank suffix under {@code CUSTOM:} resolves to that one entry
     * rather than requiring an exact name match.
     *
     * <p>A miss against the static table falls through to the live catalog before giving up, so a
     * model that exists only in a provider's live listing still resolves/validates, via
     * {@link ModelCatalogFetchService#cachedRead}, deliberately never {@code refreshingRead}: this
     * method is shared by {@link #resolve}, which runs on paths several judge/agentic call sites hit
     * per lane, so it must add no fetch latency of its own. The settings page's own catalog read
     * ({@code ProviderCredentialController#catalog}) is what warms the cache with
     * {@code refreshingRead}, and in the normal list-then-save UI flow that happens first.
     *
     * @param orgId null when the caller has no org context (a project whose row was deleted out from
     *     under it, via {@link #resolve}); degrades to static-only lookup.
     */
    private Optional<ModelCatalog.CatalogEntry> catalogEntryFor(@Nullable String orgId, @Nullable String modelKey) {
        Optional<CatalogKey> parsed = parseCatalogKey(modelKey);
        if (parsed.isEmpty()) return Optional.empty();
        CatalogKey key = parsed.get();
        if (key.provider() == ModelProvider.CUSTOM) {
            return ModelCatalog.entries().stream()
                    .filter(e -> e.provider() == ModelProvider.CUSTOM)
                    .findFirst();
        }
        Optional<ModelCatalog.CatalogEntry> staticEntry = ModelCatalog.find(key.provider(), key.modelName());
        if (staticEntry.isPresent() || orgId == null) return staticEntry;
        return ModelCatalog.mergeLive(key.provider(), catalogFetchService.cachedRead(orgId, key.provider())).stream()
                .filter(e -> e.modelName().equals(key.modelName()))
                .findFirst();
    }

    /**
     * Refused with {@link ModelConfigError#PROVIDER_NOT_CONFIGURED} when the org cannot run the provider: unreachable
     * from the picker, but a raw PUT crosses this boundary. Stored at Standard with no effort, since no lane uses
     * either.
     */
    public void set(String projectId, String orgId, ModelLane lane, String modelKey) {
        requireConfiguredProvider(orgId, modelKey);
        validate(orgId, lane, modelKey);
        repo.upsert(projectId, lane, modelKey, ServiceTier.STANDARD, null);
        invalidate(projectId);
    }

    /** Empty for an unknown key, which {@link #validate} rejects itself. */
    private static Optional<ModelProvider> providerFor(String modelKey) {
        Optional<BedrockModelProfile.ModelDescriptor> bedrock = BedrockModelProfile.find(modelKey);
        if (bedrock.isPresent()) {
            return Optional.of(
                    bedrock.get().endpoint() == BedrockModelProfile.Endpoint.MANTLE
                            ? ModelProvider.BEDROCK_MANTLE
                            : ModelProvider.BEDROCK);
        }
        return parseCatalogKey(modelKey).map(CatalogKey::provider);
    }

    private void requireConfiguredProvider(String orgId, String modelKey) {
        Optional<ModelProvider> provider = providerFor(modelKey);
        if (provider.isEmpty()) return;
        if (!configuredProviders(orgId).contains(provider.get())) {
            throw new TessaryException(ModelConfigError.PROVIDER_NOT_CONFIGURED, modelKey, provider.get());
        }
    }

    /** A delete: the lane returns to {@link LanePriority}. */
    public void clear(String projectId, ModelLane lane) {
        repo.delete(projectId, lane);
        invalidate(projectId);
    }

    /**
     * Reject what we cannot serve, most specific first: an unknown model, then a non-agentic model on an agent lane
     * (the sandbox would never start), then a model not offered on the lane's group (Haiku is agentic, so only the
     * offer list keeps it off).
     */
    private void validate(String orgId, ModelLane lane, String modelKey) {
        Optional<BedrockModelProfile.ModelDescriptor> bedrock = BedrockModelProfile.find(modelKey);
        if (bedrock.isPresent()) {
            requireServable(lane, modelKey, bedrock.get().agentic());
            return;
        }
        // A miss on both halves: the model is not ours.
        Optional<ModelCatalog.CatalogEntry> catalog = catalogEntryFor(orgId, modelKey);
        if (catalog.isEmpty()) {
            throw new TessaryException(ModelConfigError.UNKNOWN_PLATFORM_MODEL, modelKey);
        }
        validateCatalog(lane, modelKey, catalog.get());
    }

    /** Wrong-kind entries are rejected rather than accepted and silently unreachable. */
    private static void validateCatalog(ModelLane lane, String modelKey, ModelCatalog.CatalogEntry entry) {
        if (lane.decision() != entry.decision() || (!lane.decision() && !lane.agentic())) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, modelKey, lane.label());
        }
        requireServable(lane, modelKey, entry.agentic());
    }

    private static void requireServable(ModelLane lane, String modelKey, boolean agentic) {
        if (lane.agentic() && !agentic) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_AGENTIC, modelKey, lane.label());
        }
        if (!isOfferedOn(lane, modelKey)) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, modelKey, lane.label());
        }
    }

    private void invalidate(String projectId) {
        cache.remove(projectId);
    }

    private Map<ModelLane, ProjectModelSetting> forProject(String projectId) {
        return cache.computeIfAbsent(projectId, pid -> {
            Map<ModelLane, ProjectModelSetting> byLane = new EnumMap<>(ModelLane.class);
            for (ProjectModelSetting row : repo.findByProject(pid)) {
                byLane.put(row.lane(), row);
            }
            return Map.copyOf(byLane);
        });
    }
}
