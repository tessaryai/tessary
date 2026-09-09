// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.config.MantleProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.StructuredOutput.Spec;
import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.bedrock.BedrockCachePointPlacement;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.auth.scheme.BedrockRuntimeAuthSchemeProvider;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

/**
 * Resolves the {@link ChatModel} to judge with for a given project. A run picks a
 * {@code (provider, modelName, effort)} explicitly (in the run modal); we look up the
 * project's {@link ProviderCredential} for that provider and build the model from the
 * credential plus the {@link ModelCatalog} entry. When no selection is supplied (or it's
 * dangling) it falls back to {@link ModelLane#RCA}'s own resolution (see the 4-arg
 * {@link #resolve(String, ModelProvider, String, String)}'s no-catalog-entry branch).
 *
 * <p>Built models are cached by {@code orgId|provider|modelName|effort}: credentials are
 * org-scoped, so the cache is too (see {@link #invalidate}), which avoids rebuilding the underlying
 * HTTP client on every call. Mutating a provider's credential must call
 * {@link #invalidate(String, ModelProvider)} so the next call rebuilds, for every project in the org.
 */
@Component
public class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    /**
     * Per-call ceiling on every judge LLM request. A hung upstream socket otherwise pins a grading
     * thread indefinitely (the worker pools are small), and the 429 pacer/retry only bounds responses
     * that arrive, not a socket that never returns. Generous enough not to clip a legitimately slow
     * high-effort reasoning judge; it only fires on a genuine hang.
     *
     * <p>Every job lease that brackets a judge call must stay strictly greater than this (see
     * {@code GraderRunProperties#leaseSeconds}): a lease equal to this timeout is fully consumed by one
     * hung call, so the job is reclaimed and re-graded concurrently before its next lease renewal,
     * duplicated LLM spend that the idempotent verdict inserts hide.
     */
    private static final Duration LLM_CALL_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Stand-in for the {@code apiKey} langchain4j's Responses builder requires. Mantle calls are
     * SigV4-signed, and {@link MantleHttpClient} replaces the bearer header this value would produce.
     * Named rather than inlined so it reads as "deliberately not a credential" at the call site.
     */
    private static final String SIGNED_PLACEHOLDER_KEY = "sigv4";

    private final ProviderCredentialRepository repo;
    private final SecretBox secretBox;

    /** The shared, cached {@code projectId → orgId} lookup; see its own javadoc. */
    private final ProjectOrgResolver orgResolver;

    /** A project's per-lane model + tier choices; empty for a project that has set none. */
    private final ProjectModelSettings settings;

    /**
     * The live, per-(org, provider) model catalog: consulted, cache-only, as a fallback in
     * {@link #resolve(String, ModelProvider, String, String)} when a pinned selection misses
     * {@link ModelCatalog}'s static table, so a model that exists only in a provider's live listing
     * still resolves instead of 400ing as unknown.
     */
    private final ModelCatalogFetchService catalogFetchService;

    private final ConcurrentMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * Bedrock model keys already warned as running on {@link BedrockModelProfile}'s safe-unknown
     * defaults; see {@link #cacheParamsFor}. Latched per key, so a live-fetched model with no profile
     * entry logs once per process the first time it resolves rather than once per grading call for
     * as long as it keeps getting used.
     */
    private final Set<String> warnedUnprofiledBedrockModels = ConcurrentHashMap.newKeySet();

    /**
     * Bedrock prompt-cache TTL, bound to {@code tessary.grader.cache.ttl} (default {@code 5m}).
     * Bedrock supports {@code 5m} and {@code 1h}; an unrecognised value falls back to the
     * 5-minute default (see {@link #resolveCacheTtl}). Resolved once at construction so the
     * per-request {@link #cacheParamsFor} factory stays allocation-free.
     */
    private final CacheTTL cacheTtl;

    public ChatModelFactory(
            ProviderCredentialRepository repo,
            SecretBox secretBox,
            ProjectOrgResolver orgResolver,
            ProjectModelSettings settings,
            ModelCatalogFetchService catalogFetchService,
            @Value("${tessary.grader.cache.ttl:5m}") String cacheTtl) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.orgResolver = orgResolver;
        this.settings = settings;
        this.catalogFetchService = catalogFetchService;
        this.cacheTtl = resolveCacheTtl(cacheTtl);
    }

    /**
     * Parse the configured TTL string to a {@link CacheTTL}. Defaults to the 5-minute
     * value for blank/unknown input so a typo can never silently disable caching or
     * push an unsupported TTL into the Bedrock request.
     */
    static CacheTTL resolveCacheTtl(String value) {
        if (value == null || value.isBlank()) return CacheTTL.VALUE_5_M;
        CacheTTL parsed = CacheTTL.fromValue(value.trim());
        if (parsed == null || parsed == CacheTTL.UNKNOWN_TO_SDK_VERSION) {
            log.warn("unrecognised tessary.grader.cache.ttl={}; falling back to 5m", value);
            return CacheTTL.VALUE_5_M;
        }
        return parsed;
    }

    /**
     * Bedrock request parameters. With {@code cachePrefix} true, a cache breakpoint is placed after
     * the first user message (the one a caller fills with its shared, fenced context) at the
     * configured {@link #cacheTtl} (default 5-minute). Per-request instructions ride in the second
     * user message, after this point, so the cached prefix stays byte-identical across a batch of
     * requests that share a context. The mechanism is caller-agnostic. The JSON-schema
     * {@code responseFormat} is folded in here because setting it via
     * {@code ChatRequest.responseFormat(...)} alongside {@code parameters(...)} is rejected by the
     * request builder, so it's applied on both branches.
     *
     * <p>Caching is opt-out: a cache write costs 1.25x the input rate and a read 0.1x, so across N
     * graders on a unit caching bills {@code 1.15 + 0.1N} versus {@code N} uncached, break-even at
     * N≈1.28. From two graders up it's a large win, but a unit with exactly one grader pays a 25%
     * surcharge for a cache nobody ever reads. Callers that know the unit's grader count pass false
     * in that case.
     */
    private BiFunction<Spec<?>, Boolean, ChatRequestParameters> cacheParamsFor(String modelId, ServiceTier tier) {
        String key = BedrockModelProfile.normalizeKey(modelId);
        warnIfUnprofiled(modelId, key);
        // Resolve BOTH per-model capabilities once per resolve, not per request. tessary.grader.cache.ttl
        // is a single global but whether the ttl field may be sent at all is per model, and Bedrock
        // rejects a request carrying it against a non-Anthropic model outright rather than degrading.
        // A null ttl here means "omit the field"; see BedrockModelProfile.clampTtl.
        CacheTTL ttl = BedrockModelProfile.clampTtl(key, cacheTtl);
        StructuredOutput.Mode mode = BedrockModelProfile.structuredMode(key);
        boolean forceTool = BedrockModelProfile.forcedToolChoice(key);
        return (spec, cachePrefix) -> bedrockParams(spec, mode, forceTool, cachePrefix, ttl, tier);
    }

    /**
     * A Bedrock/mantle model can resolve from a live-fetched listing ({@link #liveEntry}) with no
     * matching {@link BedrockModelProfile#PROFILES} entry. It still runs correctly, on
     * {@link BedrockModelProfile}'s documented safe-unknown defaults (STANDARD tier only, native
     * structured output, cache ttl passed through unclamped, not agentic), but a future 400 from a
     * model that actually needed special handling would otherwise be debugged cold, with nothing in
     * the logs pointing at the missing profile entry as the first thing to check. Logs once per
     * model key, not once per call; see {@link #warnedUnprofiledBedrockModels}.
     */
    private void warnIfUnprofiled(String modelId, String key) {
        if (BedrockModelProfile.find(key).isEmpty() && warnedUnprofiledBedrockModels.add(key)) {
            log.warn(
                    "Bedrock/mantle model '{}' (normalized key '{}') has no BedrockModelProfile entry —"
                            + " running on safe-unknown defaults (STANDARD tier only, native structured"
                            + " output, cache ttl unclamped, not agentic); if it needs special handling a"
                            + " request will 400 rather than degrade",
                    modelId,
                    key);
        }
    }

    /** {@link #cacheParamsFor} native + Standard tier: the shape the pre-capability callers and tests use. */
    static BedrockChatRequestParameters bedrockParams(Spec<?> spec, boolean cachePrefix, @Nullable CacheTTL ttl) {
        return bedrockParams(spec, StructuredOutput.Mode.NATIVE, true, cachePrefix, ttl, ServiceTier.STANDARD);
    }

    /**
     * The Bedrock request parameters, with every per-model decision passed in: package-private so the
     * cache-point, structured-output and service-tier choices are all testable without building a real
     * Bedrock client.
     *
     * <p>{@code ttl} null means send the cache point with no {@code ttl} field at all: langchain4j
     * branches on null to reach its ttl-less {@code DEFAULT_CACHE_POINT}, which is the only encoding a
     * non-Anthropic model accepts.
     *
     * <p>{@code tier} is emitted only when it has a wire form. {@link ServiceTier#STANDARD} maps to
     * {@code DEFAULT}, which is also what Bedrock assumes when the field is absent, so sending it is
     * a no-op that keeps the request self-describing. {@link ServiceTier#BATCH} has no wire form at
     * all and must never reach here (the settings validator rejects it), so a null wire is treated
     * as "omit" rather than silently downgrading a paid-for tier.
     */
    static BedrockChatRequestParameters bedrockParams(
            @Nullable Spec<?> spec,
            StructuredOutput.Mode mode,
            boolean forcedToolChoice,
            boolean cachePrefix,
            @Nullable CacheTTL ttl,
            ServiceTier tier) {
        BedrockChatRequestParameters.Builder b = BedrockChatRequestParameters.builder();
        if (cachePrefix) {
            b.promptCaching(BedrockCachePointPlacement.AFTER_USER_MESSAGE, ttl);
        }
        if (tier != null && BedrockTiers.wireOf(tier) != null) {
            b.serviceTier(BedrockTiers.wireOf(tier));
        }
        // responseFormat / toolSpecifications / toolChoice are applied as statements, not chained: all
        // three are declared on the parent builder and return that type, which would erase the
        // Bedrock builder's covariant build() and force an unchecked cast.
        if (spec != null) {
            switch (mode) {
                case NATIVE -> b.responseFormat(spec.responseFormat());
                case TOOL_CALL -> {
                    b.toolSpecifications(List.of(spec.toolSpecification()));
                    // REQUIRED maps to Bedrock's toolChoice {any:{}}; with exactly one tool offered
                    // that forces this tool. A model that rejects `any` takes AUTO and is nudged by
                    // the tool description instead.
                    b.toolChoice(forcedToolChoice ? ToolChoice.REQUIRED : ToolChoice.AUTO);
                }
            }
        }
        return b.build();
    }

    /**
     * Resolved model + its display label (for log lines and {@code verdict.model}),
     * whether judge calls should go through the {@link LlmPacer} ({@code paced}, true
     * for the rate-limited OpenAI-compatible tiers, false for the caching providers and
     * OpenAI direct, which rely on sequential dispatch + 429 backoff), and a {@code cacheParams}
     * factory (non-null only when the provider needs per-request parameters, i.e. Bedrock;
     * Anthropic and OpenAI cache without request params).
     *
     * <p>{@code cacheParams} takes {@code (structuredSpec, cachePrefix)}: the caller passes false for
     * {@code cachePrefix} when the shared prefix will not be re-read (a unit with a single grader),
     * so the request skips the cache breakpoint and its 1.25x write premium. See
     * {@link #cacheParamsFor}.
     *
     * <p>{@code structuredMode} is how this model must be asked for a strictly-shaped answer. It's
     * decided here, once, rather than at each call site, so a caller declares its
     * {@link StructuredOutput.Spec} and never branches on the provider, and pointing a lane at a
     * model with different capabilities changes no caller code.
     *
     * <p>{@code tier} is the Bedrock {@link ServiceTier} the call was requested at; it rides here
     * so {@code LlmCaller} can price the response without re-deriving it. Bedrock's response doesn't
     * report the tier that actually served the request, so this is a record of intent, not an
     * observation. Non-Bedrock providers are always {@link ServiceTier#STANDARD}.
     *
     * <p>{@code lane} is the job this model was resolved for, carried so {@code LlmCaller} can attribute
     * the call's tokens and cost to a product section without every call site passing the lane a second
     * time. Every production build path passes it explicitly; there is no lane-less constructor to fall
     * back on. RCA/TRIAGE resolve their model through {@code ProjectModelSettings#resolveAgenticModel}
     * + {@code AgenticCredentialResolver} instead, not through this class.
     */
    public record Resolved(
            ChatModel model,
            String modelName,
            boolean paced,
            @Nullable BiFunction<Spec<?>, Boolean, ChatRequestParameters> cacheParams,
            ServiceTier tier,
            boolean platformFunded,
            StructuredOutput.Mode structuredMode,
            ModelLane lane) {}

    /**
     * The id this call is priced and reported under: the catalog's {@code modelName} unchanged, except
     * on {@link ModelProvider#BEDROCK_MANTLE} where the wire id ({@code entry.modelName()}, always bare)
     * is not what LiteLLM prices it under. Mirrors {@link BedrockModelProfile.ModelDescriptor
     * #inferenceProfileId}'s split for the platform-lane path, for the same reason.
     */
    private static String pricingId(ModelProvider provider, String modelName) {
        return provider == ModelProvider.BEDROCK_MANTLE
                ? BedrockModelProfile.MANTLE_ROUTE_PREFIX + modelName
                : modelName;
    }

    /**
     * Resolve the model to judge with for an explicit {@code (provider, modelName, effort)}
     * selection (chosen in the run modal). The provider's {@link ProviderCredential} supplies
     * the key; the {@link ModelCatalog} entry supplies per-model build settings.
     *
     * <p>Throws {@link ModelConfigError#UNKNOWN_MODEL} for a dangling selection (a model not in the
     * catalog, static or live, see below) rather than falling back to a platform default: the caller
     * named a specific model, and it does not exist, so there's no other honest default.
     */
    public Resolved resolve(String projectId, ModelProvider provider, String modelName, String effort) {
        String orgId = orgResolver.orgIdFor(projectId);
        ModelCatalog.CatalogEntry entry = ModelCatalog.find(provider, modelName)
                .or(() -> liveEntry(orgId, provider, modelName))
                .orElse(null);
        if (entry == null) {
            throw new TessaryException(ModelConfigError.UNKNOWN_MODEL, modelName, provider);
        }
        ProviderCredential cred = orgId == null
                ? null
                : repo.findByOrgAndProvider(orgId, provider).orElse(null);
        // Every provider requires an org credential now, so a pinned selection with no credential
        // fails closed unconditionally.
        if (cred == null) {
            throw new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider);
        }
        String key = cacheKey(orgId, provider, modelName, effort);
        ChatModel m = cache.computeIfAbsent(key, k -> build(cred, entry, effort));
        // Pacing is independent of caching: only Bedrock needs per-request cacheParams (its cache
        // point + response format ride in parameters). Anthropic bakes caching into the model at
        // build time (cacheSystemMessages) and OpenAI caches prefixes automatically, so both need
        // null cacheParams.
        //
        // An explicitly pinned selection always runs at the STANDARD tier. The per-lane tier setting
        // governs the platform-funded lanes (whose bill we control); a user's own Bedrock account has
        // its own quotas and commitments, so silently moving their calls to Flex, with its longer
        // latency, off a setting they set for our default judge would be surprising.
        boolean bedrock = provider == ModelProvider.BEDROCK;
        // Who pays: always the customer, now that every provider requires an org credential; see the
        // MISSING_CREDENTIALS throw above, which makes cred non-null on every path that reaches here.
        return new Resolved(
                m,
                pricingId(provider, modelName),
                paced(provider),
                bedrock ? cacheParamsFor(modelName, ServiceTier.STANDARD) : null,
                ServiceTier.STANDARD,
                false,
                // Only Bedrock has models that refuse native structured output; every other provider
                // we build (OpenAI json_schema, Anthropic, the compat tiers) supports it.
                bedrock
                        ? BedrockModelProfile.structuredMode(BedrockModelProfile.normalizeKey(modelName))
                        : StructuredOutput.Mode.NATIVE,
                // See the no-catalog-entry branch above: this overload has no production caller, so the
                // lane attributed here is never read for a real request.
                ModelLane.RCA);
    }

    /**
     * A model that exists only in the live-fetched catalog, not the static
     * {@link ModelCatalog#entries()} table. Uses {@link ModelCatalogFetchService#cachedRead}, never
     * {@code refreshingRead}: this method runs on the hot per-judge-call path, so a cold or stale
     * live cache must degrade to "not found" here rather than adding a live fetch's latency to every
     * grading call. A caller that wants the freshest live listing (the settings page) reads
     * {@code ModelCatalogFetchService} directly instead of through this class.
     */
    private Optional<ModelCatalog.CatalogEntry> liveEntry(
            @Nullable String orgId, ModelProvider provider, String modelName) {
        if (orgId == null) return Optional.empty();
        return ModelCatalog.mergeLive(provider, catalogFetchService.cachedRead(orgId, provider)).stream()
                .filter(e -> e.modelName().equals(modelName))
                .findFirst();
    }

    private static String cacheKey(String orgId, ModelProvider provider, String modelName, String effort) {
        return orgId + "|" + provider.name() + "|" + modelName + "|" + (effort == null ? "" : effort);
    }

    /**
     * Whether the provider performs prompt caching. Drives cache-token capture and
     * cost accounting (not dispatch). Bedrock and Anthropic cache via explicit
     * breakpoints; OpenAI direct caches prefixes ≥ ~1024 tokens automatically.
     */
    static boolean cachesPrompts(ModelProvider provider) {
        return provider == ModelProvider.BEDROCK
                || provider == ModelProvider.BEDROCK_MANTLE
                || provider == ModelProvider.ANTHROPIC
                || provider == ModelProvider.OPENAI;
    }

    /**
     * Whether judge calls go through the {@link LlmPacer} (sequential, single-in-flight,
     * 429 backoff). OpenRouter/Ollama/Moonshot stay paced under the same single-flight
     * assumption their rate limits require. The caching providers (Bedrock, Anthropic) and
     * OpenAI direct are unpaced: their throughput is bounded by the worker pools rather than by
     * a per-call min-interval, and 429 backoff plus retry covers the rest.
     */
    static boolean paced(ModelProvider provider) {
        return switch (provider) {
            // GEMINI/GLM/GROK/CUSTOM join the rate-limit-unknown third-party tier: each is a
            // single-key endpoint we have no throughput contract with, same reasoning as OpenRouter.
            case OPENROUTER, MOONSHOT, GEMINI, GLM, GROK, CUSTOM -> true;
            // Mantle schedules and queues rather than hard-rejecting (a brief server-side wait is its
            // documented behaviour under load), so a per-call min-interval would only add latency to a
            // path that already pays a cross-region round trip.
            case OPENAI, ANTHROPIC, BEDROCK, BEDROCK_MANTLE -> false;
        };
    }

    /**
     * Drop every cached model built for an {@code (orgId, provider)}: across all models/efforts, and
     * across every project in the org, since the cache is keyed by {@code orgId} rather than
     * {@code projectId}. One org-level credential write therefore invalidates the one shared entry
     * every project's resolve reads, rather than leaving sibling projects serving a stale client.
     */
    public void invalidate(String orgId, ModelProvider provider) {
        String prefix = orgId + "|" + provider.name() + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void invalidateAll() {
        cache.clear();
    }

    private ChatModel build(ProviderCredential cred, ModelCatalog.CatalogEntry entry, String effort) {
        return switch (entry.provider()) {
            // GEMINI/GLM/GROK/CUSTOM all speak the OpenAI Chat Completions wire against their own
            // base URL (see PlatformCatalog); no new build method, same as OpenRouter/Ollama/Moonshot.
            case OPENAI, OPENROUTER, MOONSHOT, GEMINI, GLM, GROK, CUSTOM -> buildOpenAiCompat(cred, entry, effort);
            case ANTHROPIC -> buildAnthropic(cred, entry);
            case BEDROCK -> buildBedrock(cred, entry);
            case BEDROCK_MANTLE -> buildMantle(cred, entry, effort);
        };
    }

    /**
     * A customer's own Bedrock credential pointed at the mantle endpoint. Mirrors {@link #buildBedrock}
     * (same required fields, same refusal to fall back to the ambient identity so a user's run can
     * never silently bill the platform), but reaches the model over the OpenAI Responses wire, so the
     * AWS credential is applied by {@link MantleHttpClient} rather than by an SDK client.
     *
     * <p>The project id is deliberately blank: inference on a customer's credential is attributed to
     * their account's default project. Ours is a resource in our account and would not resolve under
     * their identity.
     */
    private ChatModel buildMantle(ProviderCredential cred, ModelCatalog.CatalogEntry entry, String effort) {
        if (cred == null || cred.awsRegion() == null || cred.awsRegion().isBlank()) {
            throw new TessaryException(ModelConfigError.BEDROCK_MISSING_REGION, entry.provider());
        }
        AwsCredentialsProvider creds = byoOrIamAwsCredentials(cred, entry.provider());
        return mantleModel(
                creds,
                cred.awsRegion().trim(),
                "",
                entry.modelName(),
                MantleProperties.OPENAI_API_PATH,
                effort,
                entry.strictJsonSchema());
    }

    /**
     * The one place a mantle {@link ChatModel} is constructed, shared by the platform-funded lanes and
     * the customer-credential path: they differ only in which identity signs and which project the
     * call is attributed to.
     *
     * <p>Three settings here are not defaults and must not be dropped:
     *
     * <ul>
     *   <li><b>{@code store(false)}</b>. The Responses API defaults it to true, which has Bedrock retain
     *       the full input and output for 30 days in the request's region. We send customer trace text
     *       to the judge, so inheriting that default would be a data-retention change nobody asked for.
     *   <li><b>{@code httpClientBuilder}</b>. It carries the SigV4 signing and the per-call read
     *       timeout: unlike {@code OpenAiChatModel}, this builder exposes no {@code timeout()}, so
     *       without it a hung mantle socket would pin a grading thread indefinitely.
     *   <li><b>{@code apiKey}</b>. Required by the builder and immediately overwritten by the signed
     *       {@code Authorization} header, see {@link MantleHttpClient}. There is no Bedrock API key in
     *       the deployment; this is a placeholder, not a credential.
     * </ul>
     */
    private ChatModel mantleModel(
            AwsCredentialsProvider credentials,
            String region,
            String projectId,
            String modelName,
            String apiPath,
            @Nullable String effort,
            boolean strictJsonSchema) {
        var b = OpenAiResponsesChatModel.builder()
                .baseUrl(MantleProperties.baseUrl(region, apiPath))
                .apiKey(SIGNED_PLACEHOLDER_KEY)
                .modelName(modelName)
                .httpClientBuilder(new MantleHttpClientBuilder(credentials, region, projectId, LLM_CALL_TIMEOUT))
                .store(false)
                .strictJsonSchema(strictJsonSchema);
        if (effort != null && !effort.isBlank()) {
            b.reasoningEffort(effort);
        }
        return b.build();
    }

    /**
     * Anthropic's native API. {@code cacheSystemMessages} adds a {@code cache_control} breakpoint to
     * the system block. Known limitation: the judge layout carries the shared trace in the first
     * <em>user</em> message (so multimodal grading is first-class), and langchain4j's
     * {@code AnthropicChatModel} exposes no user-message cache breakpoint, only
     * {@code cacheSystemMessages}, which covers just the small static preamble (below the
     * ~1024-token minimum). So Anthropic-direct judge calls do not cache the trace across a unit's
     * graders; Bedrock (AFTER_USER_MESSAGE) and OpenAI (automatic prefix) still do. Acceptable since
     * Anthropic-direct is not the production path; revisit when langchain4j adds a message cache
     * point. {@code maxTokens} is set explicitly because Anthropic requires it and the library
     * default is too small for a JSON rationale. The JSON response format is applied per request via
     * {@code ChatRequest.responseFormat}, like the OpenAI path.
     */
    private ChatModel buildAnthropic(ProviderCredential cred, ModelCatalog.CatalogEntry entry) {
        String apiKey = resolveApiKey(cred, entry.provider());
        AnthropicChatModel.AnthropicChatModelBuilder b = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(entry.modelName())
                .maxTokens(4096)
                .timeout(LLM_CALL_TIMEOUT)
                .cacheSystemMessages(true);
        String baseUrl = cred == null ? null : cred.baseUrlOverride();
        if (baseUrl != null && !baseUrl.isBlank()) {
            b.baseUrl(baseUrl);
        }
        return b.build();
    }

    /**
     * The OpenAI-wire-compatible build path (OpenAI direct + the rate-limited compat tiers).
     * OpenAI direct caches prompt prefixes ≥ ~1024 tokens automatically: there are no
     * request params to set, so its {@code Resolved.cacheParams} stays null and
     * {@code ChatJudgeRunner.buildRequest} keeps applying {@code responseFormat(fmt)} as
     * usual. The context-first layout already produces the stable, byte-identical prefix
     * OpenAI matches on; cache reads then surface via {@code OpenAiTokenUsage.cachedTokens}.
     *
     * <p>{@code effort} (low/medium/high) is baked into the model when the catalog entry
     * supports it (OpenAI reasoning models); the cache key carries the effort so distinct
     * efforts don't collide on one cached client.
     */
    private ChatModel buildOpenAiCompat(ProviderCredential cred, ModelCatalog.CatalogEntry entry, String effort) {
        String baseUrl = cred == null ? null : cred.baseUrlOverride();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = entry.defaultBaseUrl();
        }
        String apiKey = resolveApiKey(cred, entry.provider());
        // CUSTOM has no real catalog: one representative entry stands in for "any model this
        // endpoint serves", so a user's own credential carries the actual model id they want. Every
        // other OpenAI-compat provider keeps the catalog entry's own name; only CUSTOM has a field
        // for this at all (ProviderCredential#customModelName's javadoc).
        String modelName = entry.provider() == ModelProvider.CUSTOM
                        && cred != null
                        && cred.customModelName() != null
                        && !cred.customModelName().isBlank()
                ? cred.customModelName().trim()
                : entry.modelName();

        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(LLM_CALL_TIMEOUT)
                .strictJsonSchema(entry.strictJsonSchema());
        if (entry.strictJsonSchema()) {
            b.supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
            b.responseFormat("json_schema");
        }
        if (effort != null && !effort.isBlank() && entry.supportsEffort()) {
            b.reasoningEffort(effort);
        }
        return b.build();
    }

    private ChatModel buildBedrock(ProviderCredential cred, ModelCatalog.CatalogEntry entry) {
        // Bedrock requires a credential (region + AWS keys, or an explicit IAM-role opt-in).
        if (cred == null || cred.awsRegion() == null || cred.awsRegion().isBlank()) {
            throw new TessaryException(ModelConfigError.BEDROCK_MISSING_REGION, entry.provider());
        }
        String modelId =
                cred.bedrockModelArn() != null && !cred.bedrockModelArn().isBlank()
                        ? cred.bedrockModelArn()
                        : entry.modelName();
        return byoBedrockModel(cred, modelId, byoOrIamAwsCredentials(cred, entry.provider()));
    }

    /** A Bedrock model on the customer's own AWS identity (sealed keys or their opted-in IAM role) and region. */
    private ChatModel byoBedrockModel(ProviderCredential cred, String modelId, AwsCredentialsProvider credentials) {
        String region = cred.awsRegion().trim();
        BedrockRuntimeClient client = bedrockClient(Region.of(region), credentials);
        return BedrockChatModel.builder()
                .region(Region.of(region))
                .modelId(modelId)
                .client(client)
                .build();
    }

    /**
     * The AWS identity a customer's Bedrock/{@code BEDROCK_MANTLE} credential builds on: their own
     * sealed access/secret keys by default, or, only when the row explicitly opted in via
     * {@link ProviderCredential#usesIamRole()}, the SDK {@link DefaultCredentialsProvider} chain (an
     * instance/task role on their own infrastructure, since this credential is theirs).
     *
     * <p>We do not fall back to this ambient identity just because the sealed keys are null: doing so
     * would silently bill the platform whenever a project's keys go missing. IAM-role auth is
     * therefore reachable only through an explicit, previously-saved flag on the credential row, never
     * inferred from an absent key, so the null-key refusal below still holds for every row that has
     * not opted in, including every row written before this field existed (it defaults to
     * {@code api_key}).
     */
    private AwsCredentialsProvider byoOrIamAwsCredentials(ProviderCredential cred, ModelProvider provider) {
        if (cred.usesIamRole()) {
            return DefaultCredentialsProvider.create();
        }
        if (cred.awsAccessKeySealed() == null || cred.awsSecretKeySealed() == null) {
            throw new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider);
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                secretBox.open(cred.awsAccessKeySealed()), secretBox.open(cred.awsSecretKeySealed())));
    }

    /**
     * A BedrockRuntime client pinned to <b>SigV4</b>. Bedrock supports two auth schemes, SigV4 (AWS
     * keys / role) and an HTTP bearer token ({@code AWS_BEARER_TOKEN_BEDROCK}), and AWS SDK v2 prefers
     * the bearer token whenever that env var is present. Our Bedrock identity is always SigV4 (the
     * user's sealed AWS keys for the judge lane; the platform's ambient keys, the same the agentic
     * launcher forwards to the sandbox, for generation), so an ambient bearer token in the environment
     * would silently hijack every Bedrock call and 403 with "Please make sure your API Key is valid".
     * Forcing the SigV4 preference makes the scheme deterministic regardless of ambient env; it's a
     * no-op when no bearer token is set.
     */
    private static BedrockRuntimeClient bedrockClient(Region region, AwsCredentialsProvider creds) {
        return BedrockRuntimeClient.builder()
                .region(region)
                .credentialsProvider(creds)
                .authSchemeProvider(BedrockRuntimeAuthSchemeProvider.defaultProvider(List.of("aws.auth#sigv4")))
                // Bound a hung Bedrock socket on every lane that shares this client (the judge fanout,
                // a small pool, and the platform-generation lane). Use the per-attempt timeout, not
                // the total apiCallTimeout: it caps a single stalled attempt the way the
                // OpenAI/Anthropic builder .timeout() does, without clipping a legitimate
                // throttle-and-retry sequence (which the SDK's retry policy may span).
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(LLM_CALL_TIMEOUT))
                .build();
    }

    /**
     * The API key to use for a provider credential. Every provider now requires one, so there is no
     * fallback: a row with no sealed key (or no row at all, every non-Bedrock caller of this method is
     * upstream of the unconditional {@code MISSING_CREDENTIALS} throw in
     * {@link #resolve(String, ModelProvider, String, String)}, so {@code cred} is guaranteed non-null
     * here in production; the null check stays defensive) fails loudly rather than reaching for an
     * ambient key that no longer exists.
     */
    private String resolveApiKey(ProviderCredential cred, ModelProvider provider) {
        if (cred != null && cred.apiKeySealed() != null && !cred.apiKeySealed().isBlank()) {
            return secretBox.open(cred.apiKeySealed());
        }
        throw new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider);
    }
}
