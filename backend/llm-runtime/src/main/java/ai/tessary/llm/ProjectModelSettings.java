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
 * <p>{@code ChatModelFactory.resolve(projectId, lane)} runs on the path of every platform-funded LLM
 * call, so the cache avoids hitting Postgres for a row that changes roughly never. It is invalidated
 * explicitly on write ({@link #invalidate}) rather than time-bounded, so a settings change takes
 * effect on the next call.
 *
 * <p>Validation lives here, not only in the controller, because a bad pair can arrive two ways: a
 * fresh PUT, or a row written before a capability changed (a provider retiring a tier for a model, or
 * a model being removed from {@link BedrockModelProfile}). {@link #validate} is the gate for the
 * first; {@link #resolve} degrades safely for the second.
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
 * key ({@code amazon.nova-2-lite}); those never carry a colon. An {@link LaneGroup#AGENT_VM} lane can
 * also reach a non-Bedrock {@link ModelCatalog} entry (GEMINI, GLM, GROK, CUSTOM), encoded as
 * {@code "<PROVIDER>:<model_name>"} ({@link #CATALOG_KEY}), a shape no Bedrock key has ever taken so
 * both forms are unambiguous from the string alone. {@link #parseCatalogKey} and
 * {@link #catalogEntryFor} are the only two places that decode it; every other reader calls
 * {@link BedrockModelProfile#find} first and only falls through to the catalog on a miss.
 */
@Service
public class ProjectModelSettings {

    /**
     * A non-Bedrock catalog key's wire shape, see the class javadoc's "{@code model_key} union"
     * section. Bedrock/mantle keys are dotted ({@code openai.gpt-5.6-luna}) and never match this.
     */
    private static final Pattern CATALOG_KEY = Pattern.compile("^([A-Z_]+):(.+)$");

    private final ProjectModelSettingRepository repo;

    /** Read to gate an explicit save against the org's configured providers, see {@link #set}. */
    private final ProviderCredentialRepository credentials;

    /**
     * {@code projectId -> orgId}, so {@link #resolve(String, ModelLane)} can consult the live
     * catalog for a non-Bedrock row without every caller having to pass an orgId it may not have
     * on hand.
     */
    private final ProjectOrgResolver orgResolver;

    /**
     * The live, per-(org, provider) model catalog, consulted cache-only (see
     * {@link #catalogEntryFor}'s own javadoc for why never {@code refreshingRead} here), so a
     * non-Bedrock model that exists only in a provider's live listing still resolves/validates
     * instead of being treated as unknown.
     */
    private final ModelCatalogFetchService catalogFetchService;

    /** projectId → its explicitly-set lanes. Absent lane = inherit the platform default. */
    private final ConcurrentMap<String, Map<ModelLane, ProjectModelSetting>> cache = new ConcurrentHashMap<>();

    public ProjectModelSettings(
            ProjectModelSettingRepository repo,
            ProviderCredentialRepository credentials,
            ProjectOrgResolver orgResolver,
            ModelCatalogFetchService catalogFetchService) {
        this.repo = repo;
        this.credentials = credentials;
        this.orgResolver = orgResolver;
        this.catalogFetchService = catalogFetchService;
    }

    /** Every lane this project has explicitly set, unset lanes omitted. */
    public List<ProjectModelSetting> list(String projectId) {
        return List.copyOf(forProject(projectId).values());
    }

    /**
     * What {@code lane} runs on for this project, or empty when nothing can run it.
     *
     * <p>Two sources, in order. An explicit row the org can still serve wins. Otherwise the lane is
     * resolved automatically from {@link LanePriority}: the best model in that lane's order whose
     * provider the org holds a credential for. Empty means the org has configured no provider that
     * serves any model this lane offers, which is the state a brand-new org is in and the reason the
     * settings page asks for a provider before it asks for a model.
     *
     * <p>An explicit row is dropped back to the automatic answer, rather than served, whenever it has
     * stopped being runnable: its provider's credential was deleted, its (model, tier) pair is no
     * longer supported, it names a model that has left this lane's offer list, or it points a sandbox
     * lane at a model that cannot drive one. {@link #validate} refuses all four on write, but a row
     * written before a capability moved would otherwise fail every call in the lane. The row is left
     * in the table, so re-adding the credential brings the choice back.
     *
     * <p>An effort the model no longer accepts degrades to "no effort" instead, keeping the chosen
     * model: unlike a tier, which changes what the call costs, effort is a quality dial, and running
     * the chosen model at its own default is much closer to the intent than moving to another model.
     */
    public Optional<LaneSelection> resolve(String projectId, ModelLane lane) {
        String orgId = orgResolver.orgIdFor(projectId);
        Set<ModelProvider> configured = configuredProviders(orgId);
        ProjectModelSetting row = forProject(projectId).get(lane);
        Optional<LaneSelection> chosen = row == null ? Optional.empty() : usable(orgId, configured, lane, row);
        return chosen.isPresent() ? chosen : autoSelect(configured, lane);
    }

    /**
     * One lane's answer: the model to run, how, and whether the project picked it or
     * {@link LanePriority} did. {@code automatic} is what lets the settings page say which of the two
     * it is showing without asking a second question the server would have to answer consistently.
     */
    public record LaneSelection(
            @JsonProperty("model_key") String modelKey,
            @JsonProperty("service_tier") ServiceTier serviceTier,
            @JsonProperty("reasoning_effort") @Nullable String reasoningEffort,
            boolean automatic) {}

    /** The explicit row, if this org can still run it. See {@link #resolve} for each rejection. */
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
        if (bedrock.isPresent() && !bedrock.get().supportedTiers().contains(row.serviceTier())) {
            return Optional.empty();
        }
        // A catalog (non-Bedrock) row carries no tier concept at all: set() always clamps a
        // non-tiered lane's tier to STANDARD, and every AGENT_VM lane (the only place a catalog key
        // can land, see #validate) is non-tiered, so there is nothing further to check here.
        boolean agentic = bedrock.map(BedrockModelProfile.ModelDescriptor::agentic)
                .orElseGet(() -> catalog.map(ModelCatalog.CatalogEntry::agentic).orElse(false));
        if (lane.agentic() && !agentic) {
            return Optional.empty();
        }
        if (!isOfferedOn(lane, row.modelKey())) {
            return Optional.empty();
        }
        boolean supportsEffort = bedrock.map(
                        d -> BedrockModelProfile.supportsEffort(row.modelKey(), row.reasoningEffort()))
                .orElseGet(() -> catalogSupportsEffort(catalog.get(), row.reasoningEffort()));
        return Optional.of(new LaneSelection(
                row.modelKey(), row.serviceTier(), supportsEffort ? row.reasoningEffort() : null, false));
    }

    /**
     * Provider first, model second: the first provider in this lane's {@link LanePriority} order that
     * the org holds a credential for, running that provider's default model for the lane.
     *
     * <p>Standard tier and no reasoning effort, always: both lanes that exist are
     * {@link LaneGroup#AGENT_VM}, which has neither control, and an automatic choice is not the place
     * to invent a preference the project never expressed. A project that wants either sets the lane
     * explicitly, which is exactly what {@link #set} writes.
     */
    private Optional<LaneSelection> autoSelect(Set<ModelProvider> configured, ModelLane lane) {
        return LanePriority.of(lane).stream()
                .filter(o -> configured.contains(o.provider()))
                .findFirst()
                .map(o -> new LaneSelection(o.defaultModelKey(), ServiceTier.STANDARD, null, true));
    }

    /**
     * Whether {@code lane} offers {@code modelKey} at all: the lane's own list, not its group's. The
     * two differ on purpose: TRIAGE carries only the models under its price ceiling, so a group-level
     * check would let a raw PUT put a frontier model on the lane the ceiling exists to protect.
     *
     * <p>{@link ModelProvider#CUSTOM} is matched by provider rather than by model name: the catalog
     * holds one representative entry for an arbitrary OpenAI-compatible endpoint, and the real model
     * id is whatever the customer named on their own credential (see
     * {@link ProviderCredential#customModelName()}), so what a lane can offer there is the provider.
     */
    private static boolean isOfferedOn(ModelLane lane, String modelKey) {
        return LanePriority.modelKeys(lane).contains(modelKey)
                || parseCatalogKey(modelKey)
                        .filter(k -> k.provider() == ModelProvider.CUSTOM)
                        .flatMap(k -> LanePriority.forProvider(lane, k.provider()))
                        .isPresent();
    }

    /** The providers this org holds a credential for; empty for an org that has configured none. */
    private Set<ModelProvider> configuredProviders(@Nullable String orgId) {
        if (orgId == null) return EnumSet.noneOf(ModelProvider.class);
        Set<ModelProvider> configured = EnumSet.noneOf(ModelProvider.class);
        for (ProviderCredential c : credentials.findByOrg(orgId)) {
            configured.add(c.provider());
        }
        return configured;
    }

    /**
     * The full Bedrock inference-profile id this project has chosen for {@code lane}, or empty to
     * inherit whatever default that lane's caller holds. Bedrock rows only: a project pointed at a
     * non-Bedrock catalog entry resolves to empty here, exactly like an unset lane; use
     * {@link #resolveAgenticModel} for the provider-aware form the sandbox launcher needs.
     *
     * <p>For the lanes that build their own Bedrock request, {@link ChatModelFactory} already does
     * this internally. This exists for the sandbox lanes, whose model is handed to the agent inside a
     * microVM rather than to a client we construct, so it needs the id as a string, and it must be the
     * full profile id, since the launcher qualifies it with a provider and passes it straight
     * through, and Bedrock rejects a short alias with a 400.
     */
    public Optional<String> inferenceProfileId(String projectId, ModelLane lane) {
        return resolve(projectId, lane)
                .flatMap(s -> BedrockModelProfile.find(s.modelKey()))
                .map(BedrockModelProfile.ModelDescriptor::inferenceProfileId);
    }

    /**
     * The {@code (provider, modelId)} pair the sandbox launcher needs to run this project's chosen
     * model for {@code lane}: a Bedrock/{@code BEDROCK_MANTLE} inference-profile id, or a bare
     * {@link ModelCatalog} model name for one of the other providers. Empty means the org has no
     * credential for any provider this lane can run on, and the run fails on credentials.
     */
    public Optional<ResolvedAgenticModel> resolveAgenticModel(String projectId, ModelLane lane) {
        return resolve(projectId, lane).map(selection -> {
            Optional<BedrockModelProfile.ModelDescriptor> bedrock = BedrockModelProfile.find(selection.modelKey());
            if (bedrock.isPresent()) {
                BedrockModelProfile.ModelDescriptor d = bedrock.get();
                ModelProvider provider = d.endpoint() == BedrockModelProfile.Endpoint.MANTLE
                        ? ModelProvider.BEDROCK_MANTLE
                        : ModelProvider.BEDROCK;
                return new ResolvedAgenticModel(provider, d.inferenceProfileId());
            }
            CatalogKey key = parseCatalogKey(selection.modelKey()).orElseThrow();
            return new ResolvedAgenticModel(key.provider(), key.modelName());
        });
    }

    /** See {@link #resolveAgenticModel}. */
    public record ResolvedAgenticModel(
            ModelProvider provider,
            @JsonProperty("model_id") String modelId) {}

    /**
     * Parsed form of a non-Bedrock {@code "<PROVIDER>:<model_name>"} {@code model_key}, see the
     * class javadoc's "the {@code model_key} union" section.
     */
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

    private static boolean catalogSupportsEffort(ModelCatalog.CatalogEntry entry, @Nullable String level) {
        if (level == null || level.isBlank()) return true;
        return entry.effortLevels().contains(level.trim());
    }

    /**
     * Point a lane at a model + tier, after checking the org can actually run it.
     *
     * <p>The provider gate comes first: a {@code (lane, modelKey)} whose provider the org holds no
     * credential for is refused with {@link ModelConfigError#PROVIDER_NOT_CONFIGURED}. The picker
     * only offers models the org has a key for, so reaching this is a bug in the picker's filter
     * rather than a normal user path, but it is also the boundary a raw PUT crosses, and a row
     * written past it would be a stored choice the lane could never serve.
     *
     * <p>A non-{@link ModelLane#tiered} lane is stored at Standard whatever the caller asked for: it
     * has no Bedrock request for a tier to ride on, so persisting one would be a stored preference
     * that never takes effect (and would then block the model on {@link #resolve}'s support check if
     * the tier were later withdrawn). A lane whose group is not {@link LaneGroup#effortTunable} loses
     * its reasoning effort for the same reason and it is not hypothetical: GPT-5.6 Terra is offered on
     * the microVM lanes and accepts six effort levels, but those lanes hand the agent a model id and
     * nothing else, so a stored effort there would read back on the settings page as a setting that
     * had taken effect when in fact no request ever carried it.
     */
    public void set(
            String projectId,
            String orgId,
            ModelLane lane,
            String modelKey,
            ServiceTier tier,
            @Nullable String reasoningEffort) {
        requireConfiguredProvider(orgId, modelKey);
        ServiceTier effective = lane.tiered() ? tier : ServiceTier.STANDARD;
        // Blank and null both mean "no reasoning parameter". Normalising here keeps the DB from
        // carrying two spellings of the same absence, which the CHECK constraint would reject anyway.
        String requested = lane.group().effortTunable() ? reasoningEffort : null;
        String effort = requested == null || requested.isBlank() ? null : requested.trim();
        validate(orgId, lane, modelKey, effective, effort);
        repo.upsert(projectId, lane, modelKey, effective, effort);
        invalidate(projectId);
    }

    /**
     * The {@link ModelProvider} a {@code model_key} resolves to, Bedrock/mantle keys included: the
     * union {@link #validate} already decodes, reused here so the credential gate asks the same
     * question the picker's option list was built from. Returns empty for a key that is neither a
     * known Bedrock descriptor nor a known catalog entry; {@link #requireConfiguredProvider} treats
     * that as "not this check's problem" and leaves it to {@link #validate}'s own
     * {@link ModelConfigError#UNKNOWN_PLATFORM_MODEL}.
     */
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
        if (credentials.findByOrgAndProvider(orgId, provider.get()).isEmpty()) {
            throw new TessaryException(ModelConfigError.PROVIDER_NOT_CONFIGURED, modelKey, provider.get());
        }
    }

    /**
     * Drop a project's explicit choice for one lane, returning it to the automatic answer.
     *
     * <p>A delete, not a rewrite of some default: there is no default to rewrite. The lane goes back
     * to {@link LanePriority} against the org's configured providers, which is what it ran before
     * anyone touched it and what it will keep running as providers are added or removed.
     */
    public void clear(String projectId, ModelLane lane) {
        repo.delete(projectId, lane);
        invalidate(projectId);
    }

    /**
     * Reject a (lane, model, tier) combination we cannot actually serve, in order of specificity so
     * the message names the real problem:
     *
     * <ol>
     *   <li>the model isn't one of ours: nothing else is worth checking;
     *   <li>the tier has no online wire form ({@link ServiceTier#BATCH}): invalid for every model,
     *       so blaming the model would misdirect;
     *   <li>the pair is unsupported, e.g. Flex on Haiku 4.5, where both halves are individually
     *       fine. This is the check that stops a Bedrock 400 from surfacing mid-grade, hours after
     *       the setting was saved.
     *   <li>the lane needs an agentic model and this one isn't. An {@link LaneGroup#AGENT_VM} lane
     *       hands its inference profile id to the agent inside a microVM, which asks the model to
     *       sustain a long tool-use loop; Nova 2 Lite is a perfectly good grading model and a sandbox
     *       that never starts.
     *   <li>the model is not offered for this lane's {@link LaneGroup}. Checked after the capability
     *       above, because "this model cannot do that work" is a more useful thing to be told than
     *       "this model is not on the list" whenever both are true, and the two are not the same
     *       check: Claude Haiku 4.5 is agentic, so only the offer list keeps it off a microVM lane.
     *   <li>the model does not accept this reasoning effort, either because it takes no effort at all
     *       (every Converse model here) or because the level is not in its set. Checked last because it
     *       is the narrowest condition, and because naming the model is only useful once the model
     *       itself is known to be valid.
     * </ol>
     */
    public void validate(ModelLane lane, String modelKey, ServiceTier tier, @Nullable String reasoningEffort) {
        validate(null, lane, modelKey, tier, reasoningEffort);
    }

    /**
     * {@link #validate(ModelLane, String, ServiceTier, String)}, with an orgId so a non-Bedrock
     * {@code modelKey} that exists only in that org's live-fetched catalog still passes rather than
     * throwing {@link ModelConfigError#UNKNOWN_PLATFORM_MODEL}. The org-gated {@link #set} is the one
     * caller that has an orgId to give; the 4-arg overload above passes null and validates against the
     * static table only.
     */
    public void validate(
            @Nullable String orgId,
            ModelLane lane,
            String modelKey,
            ServiceTier tier,
            @Nullable String reasoningEffort) {
        Optional<BedrockModelProfile.ModelDescriptor> bedrock = BedrockModelProfile.find(modelKey);
        if (bedrock.isPresent()) {
            validateBedrock(lane, modelKey, tier, reasoningEffort);
            return;
        }
        // The non-Bedrock half of the union (see the class javadoc). A miss on both halves is
        // the original UNKNOWN_PLATFORM_MODEL: the model simply isn't ours.
        Optional<ModelCatalog.CatalogEntry> catalog = catalogEntryFor(orgId, modelKey);
        if (catalog.isEmpty()) {
            throw new TessaryException(ModelConfigError.UNKNOWN_PLATFORM_MODEL, modelKey);
        }
        validateCatalog(lane, modelKey, catalog.get(), tier, reasoningEffort);
    }

    private static void validateBedrock(
            ModelLane lane, String modelKey, ServiceTier tier, @Nullable String reasoningEffort) {
        if (tier == null || !tier.isOnline()) {
            throw new TessaryException(ModelConfigError.TIER_NOT_ONLINE, tier == null ? "null" : tier.wireName());
        }
        if (!BedrockModelProfile.supports(modelKey, tier)) {
            throw new TessaryException(ModelConfigError.TIER_UNSUPPORTED_BY_MODEL, modelKey, tier.wireName());
        }
        if (lane != null && lane.agentic() && !BedrockModelProfile.isAgentic(modelKey)) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_AGENTIC, modelKey, lane.label());
        }
        // The lane's own list, not its group's: this is what enforces TRIAGE's price ceiling against a
        // raw PUT. Claude Sonnet 5 is agentic, offered for AGENT_VM, and valid at Standard, so nothing
        // above this line stops it landing on the lane that runs unattended once per cause.
        if (lane != null && !isOfferedOn(lane, modelKey)) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, modelKey, lane.label());
        }
        if (!BedrockModelProfile.supportsEffort(modelKey, reasoningEffort)) {
            throw new TessaryException(ModelConfigError.EFFORT_UNSUPPORTED_BY_MODEL, reasoningEffort, modelKey);
        }
    }

    /**
     * The catalog (non-Bedrock) half of {@link #validate}. Only reachable for an
     * {@link LaneGroup#AGENT_VM} lane today; a catalog key on any other lane shape is rejected with
     * {@code MODEL_NOT_OFFERED_FOR_LANE} rather than accepted and silently unreachable.
     */
    private static void validateCatalog(
            ModelLane lane,
            String modelKey,
            ModelCatalog.CatalogEntry entry,
            ServiceTier tier,
            @Nullable String reasoningEffort) {
        if (tier == null || !tier.isOnline()) {
            throw new TessaryException(ModelConfigError.TIER_NOT_ONLINE, tier == null ? "null" : tier.wireName());
        }
        if (lane == null || lane.group() != LaneGroup.AGENT_VM) {
            throw new TessaryException(
                    ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, modelKey, lane == null ? "null" : lane.label());
        }
        if (!entry.agentic()) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_AGENTIC, modelKey, lane.label());
        }
        // Same lane-scoped gate as the Bedrock half above: TRIAGE's ceiling leaves each provider's
        // flagship agentic and permitted by the group, but not offered on this lane.
        if (!isOfferedOn(lane, modelKey)) {
            throw new TessaryException(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, modelKey, lane.label());
        }
        if (!catalogSupportsEffort(entry, reasoningEffort)) {
            throw new TessaryException(ModelConfigError.EFFORT_UNSUPPORTED_BY_MODEL, reasoningEffort, modelKey);
        }
    }

    /** Drop a project's cached settings so the next resolve re-reads. */
    public void invalidate(@Nullable String projectId) {
        if (projectId == null) cache.clear();
        else cache.remove(projectId);
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
