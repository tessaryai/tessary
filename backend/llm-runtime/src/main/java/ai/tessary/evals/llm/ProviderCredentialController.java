// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.ingest.UrlGuard;
import ai.tessary.evals.llm.catalog.ModelCatalogFetchService;
import ai.tessary.evals.llm.catalog.ProviderModel;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.ModelConfigError;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider credentials: one stored credential per {@code (org, provider)} (#939 D1 — moved off
 * {@code (project, provider)}; see {@link ProviderCredential}'s class javadoc). An org adds a key for
 * a provider once; every project in it then offers every {@link ModelCatalog} model that provider
 * hosts. Secrets are sealed via {@link SecretBox} and never returned — the {@link View} exposes only
 * boolean {@code has_*} flags.
 *
 * <p><b>Gated on {@link Capability#BYO_PROVIDER_KEYS}, which is now ON by default</b> — see that
 * constant's javadoc for why it flipped: #939 D4 left every agentic lane resolving the org's own
 * credential, so an org that cannot reach this controller cannot triage a finding at all. The GATE
 * itself is unchanged and still correct, because it was never about hiding a tab: a partner with the
 * URL, or an integration written against the API, would still be able to store a key, so every verb
 * here requires the capability, including the reads — a credential roster an org may not populate is a
 * page about a thing it cannot do. What changed is which answer the capability gives by default, not
 * what it protects. The catalog read is the exception and stays open, because {@code /catalog} is the
 * list of models the PLATFORM offers and is read by surfaces that have nothing to do with BYO keys.
 *
 * <p>The ROUTE drops {@code /projects/{projectSlug}} (#939 D1) — there is no project in scope any
 * more, only an org membership check ({@link TenantPathResolver#requireOrg}). The Settings →
 * Providers PAGE keeps its own URL unchanged (still nested under a project, in the React router) —
 * that is a deliberate, separate decision: renesting Settings' IA is out of scope here, and the page
 * simply calls this org-scoped API from wherever it is mounted. See {@code frontend/src/tenant/TenantContext.tsx}'s
 * {@code useOrgApi} and {@code frontend/src/views/Settings/Providers.tsx}.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/providers")
public class ProviderCredentialController {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialController.class);

    private final ProviderCredentialRepository repo;
    private final ChatModelFactory factory;
    private final SecretBox secretBox;
    private final TenantPathResolver resolver;
    private final CapabilityService capabilities;

    /** The live, per-(org, provider) model catalog (#939 TASK 2) — see {@link #catalog}. */
    private final ModelCatalogFetchService catalogFetchService;

    public ProviderCredentialController(
            ProviderCredentialRepository repo,
            ChatModelFactory factory,
            SecretBox secretBox,
            TenantPathResolver resolver,
            CapabilityService capabilities,
            ModelCatalogFetchService catalogFetchService) {
        this.repo = repo;
        this.factory = factory;
        this.secretBox = secretBox;
        this.resolver = resolver;
        this.capabilities = capabilities;
        this.catalogFetchService = catalogFetchService;
    }

    /**
     * A partial-update upsert: every field is optional. A null field means "leave the stored
     * value untouched"; a non-blank secret field ({@code api_key} / AWS keys) replaces and
     * re-seals it. There are therefore no Jakarta {@code @NotBlank} constraints to apply — the
     * blank-checks in {@link #upsert} drive that keep-vs-replace semantics, not validation.
     */
    public record UpsertRequest(
            @JsonProperty("base_url_override") String baseUrlOverride,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("aws_region") String awsRegion,
            @JsonProperty("aws_access_key") String awsAccessKey,
            @JsonProperty("aws_secret_key") String awsSecretKey,
            @JsonProperty("bedrock_model_arn") String bedrockModelArn,
            /** {@link ModelProvider#CUSTOM} only — the free-text model id to actually call. */
            @JsonProperty("custom_model_name") String customModelName,
            /**
             * {@code "api_key"} or {@code "iam_role"} — Bedrock/{@code BEDROCK_MANTLE} only. Null means
             * "leave the stored value untouched", like every other field here; a fresh credential with
             * no auth_mode sent defaults to {@code api_key} (see {@link #validateAuth}).
             */
            @JsonProperty("auth_mode") String authMode) {}

    public record View(
            String id,
            ModelProvider provider,
            @JsonProperty("base_url_override") String baseUrlOverride,
            @JsonProperty("has_api_key") boolean hasApiKey,
            @JsonProperty("aws_region") String awsRegion,
            @JsonProperty("has_aws_credentials") boolean hasAwsCredentials,
            @JsonProperty("bedrock_model_arn") String bedrockModelArn,
            @JsonProperty("custom_model_name") String customModelName,
            @JsonProperty("auth_mode") String authMode,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    /** Typed catalog envelope (was a raw {@code Map<String,Object>} — see the strong-typing rule). */
    public record CatalogView(
            List<PlatformCatalog.PlatformDescriptor> platforms, List<ModelCatalog.CatalogEntry> models) {}

    /** Typed credential-list envelope. */
    public record CredentialListView(List<View> credentials) {}

    /** Typed delete ack. */
    public record DeleteResponse(boolean deleted) {}

    /**
     * Hard ceiling on how long {@link #catalog} waits for any ONE provider's fetch before giving up on
     * it and falling back to that provider's static entries. Deliberately generous over a single
     * lister's own request timeout (each {@code ProviderModelLister} already bounds its own HTTP/AWS
     * call via {@code evals.model-catalog.fetch-timeout}, 5s by default) rather than tied to it 1:1:
     * this is a belt-and-suspenders bound against a hang the per-request timeout does not cover (e.g.
     * DNS resolution), not the primary latency control.
     */
    private static final Duration PER_PROVIDER_FETCH_DEADLINE = Duration.ofSeconds(10);

    /**
     * #939 TASK 2: {@code models} is now built per-org, live — for each provider, the org's own
     * fetched listing ({@link ModelCatalogFetchService#refreshingRead}, the settings-page path, which
     * may block briefly on a cold or expired cache entry) merged onto {@link ModelCatalog}'s static
     * capability rows. A provider the org has not configured (or whose fetch just failed with nothing
     * cached — mandatory property iii) contributes its static representative entries unchanged, same
     * as this endpoint's entire pre-TASK-2 behavior — so an unconfigured/erroring provider still shows
     * up in the picker as the D3 disabled option it already renders as, rather than vanishing.
     *
     * <p>The ten {@code refreshingRead} calls run CONCURRENTLY on virtual threads rather than in a
     * sequential loop on the request thread: an org's configured providers typically warm together
     * during onboarding, so their cache entries expire in near lock-step, and a sequential loop would
     * make this endpoint's wall time the SUM of up to ten blocking fetches (each up to the fetch
     * timeout) rather than the MAX of one. {@link #PER_PROVIDER_FETCH_DEADLINE} additionally bounds any
     * one call so a single stuck provider cannot hold the whole response open indefinitely — degrading
     * that provider to its static entries, exactly like a fetch failure already does.
     */
    @GetMapping("/catalog")
    public ApiResponse<CatalogView> catalog(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        Map<ModelProvider, List<ProviderModel>> live = fetchAllProviders(r.org().id());
        List<ModelCatalog.CatalogEntry> models = new ArrayList<>();
        for (ModelProvider provider : ModelProvider.values()) {
            List<ProviderModel> providerLive = live.getOrDefault(provider, List.of());
            List<ModelCatalog.CatalogEntry> providerEntries = providerLive.isEmpty()
                    ? ModelCatalog.entries().stream()
                            .filter(e -> e.provider() == provider)
                            .toList()
                    : ModelCatalog.mergeLive(provider, providerLive);
            models.addAll(providerEntries);
        }
        return ApiResponse.ok(new CatalogView(PlatformCatalog.platforms(), List.copyOf(models)));
    }

    /**
     * Fans {@link ModelCatalogFetchService#refreshingRead} out across one virtual thread per provider
     * instead of running the ten calls sequentially on the request thread — see {@link #catalog}'s
     * javadoc. A provider whose fetch does not finish within {@link #PER_PROVIDER_FETCH_DEADLINE}, or
     * that fails outright, is simply absent from the returned map; the caller already treats "no live
     * listing" as "fall back to static entries", the same degrade path a vendor outage takes inside
     * {@code ModelCatalogFetchService} itself.
     */
    private Map<ModelProvider, List<ProviderModel>> fetchAllProviders(String orgId) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<ModelProvider, Future<List<ProviderModel>>> futures = new EnumMap<>(ModelProvider.class);
            for (ModelProvider provider : ModelProvider.values()) {
                futures.put(provider, executor.submit(() -> catalogFetchService.refreshingRead(orgId, provider)));
            }
            Map<ModelProvider, List<ProviderModel>> results = new EnumMap<>(ModelProvider.class);
            for (Map.Entry<ModelProvider, Future<List<ProviderModel>>> entry : futures.entrySet()) {
                try {
                    results.put(
                            entry.getKey(),
                            entry.getValue().get(PER_PROVIDER_FETCH_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                } catch (ExecutionException e) {
                    // refreshingRead already catches ModelListingException internally and degrades to
                    // List.of()/stale cache — reaching here means an unexpected runtime failure, which
                    // gets the same "fall back to static entries" treatment as every other failure mode.
                    log.warn(
                            "catalog fetch for provider={} failed unexpectedly: {}",
                            entry.getKey(),
                            e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    entry.getValue().cancel(true);
                }
            }
            return results;
        }
    }

    @GetMapping
    public ApiResponse<CredentialListView> list(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        capabilities.require(r.org().id(), Capability.BYO_PROVIDER_KEYS);
        List<View> rows = repo.findByOrg(r.org().id()).stream()
                .map(ProviderCredentialController::toView)
                .toList();
        return ApiResponse.ok(new CredentialListView(rows));
    }

    @PutMapping("/{provider}")
    public ApiResponse<View> upsert(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable ModelProvider provider,
            @RequestBody UpsertRequest req) {
        var r = resolver.requireOrg(ctx, orgSlug);
        capabilities.require(r.org().id(), Capability.BYO_PROVIDER_KEYS);

        // SSRF guard: a user-supplied base-URL override becomes a server-side outbound target in
        // ChatModelFactory (its response flows back into the judge verdict), so reject
        // loopback/link-local/RFC1918/CGNAT/IMDS hosts at the write boundary — exactly as the
        // ingestion-source URL is guarded. This is the only credentialed outbound caller that was
        // skipping the guard.
        if (req.baseUrlOverride() != null && !req.baseUrlOverride().isBlank()) {
            UrlGuard.requirePublicHttp(req.baseUrlOverride());
        }

        boolean settingApiKey = req.apiKey() != null && !req.apiKey().isBlank();
        boolean settingAwsKeys = req.awsAccessKey() != null
                && !req.awsAccessKey().isBlank()
                && req.awsSecretKey() != null
                && !req.awsSecretKey().isBlank();
        if ((settingApiKey || settingAwsKeys) && !secretBox.isConfigured()) {
            throw new EvalsException(ModelConfigError.SECRET_KEY_NOT_CONFIGURED);
        }

        ProviderCredential existing =
                repo.findByOrgAndProvider(r.org().id(), provider).orElse(null);
        String now = Instant.now().toString();
        ProviderCredential row = new ProviderCredential(
                existing != null ? existing.id() : Ids.ulid(),
                r.org().id(),
                // project_id is historical only as of #939 D1 — a fresh row written through this
                // org-scoped route names no project at all (see ProviderCredential's class javadoc).
                existing != null ? existing.projectId() : null,
                provider,
                req.baseUrlOverride() != null
                        ? blankToNull(req.baseUrlOverride())
                        : (existing != null ? existing.baseUrlOverride() : null),
                settingApiKey ? secretBox.seal(req.apiKey()) : (existing != null ? existing.apiKeySealed() : null),
                req.awsRegion() != null
                        ? blankToNull(req.awsRegion())
                        : (existing != null ? existing.awsRegion() : null),
                settingAwsKeys
                        ? secretBox.seal(req.awsAccessKey())
                        : (existing != null ? existing.awsAccessKeySealed() : null),
                settingAwsKeys
                        ? secretBox.seal(req.awsSecretKey())
                        : (existing != null ? existing.awsSecretKeySealed() : null),
                req.bedrockModelArn() != null
                        ? blankToNull(req.bedrockModelArn())
                        : (existing != null ? existing.bedrockModelArn() : null),
                req.customModelName() != null
                        ? blankToNull(req.customModelName())
                        : (existing != null ? existing.customModelName() : null),
                req.authMode() != null
                        ? blankToNull(req.authMode())
                        : (existing != null ? existing.authMode() : ProviderCredential.AUTH_MODE_API_KEY),
                existing != null ? existing.createdAt() : now,
                now);
        validateAuth(row);
        if (existing != null) {
            repo.update(row);
        } else {
            repo.insert(row);
        }
        factory.invalidate(r.org().id(), provider);
        return ApiResponse.ok(toView(row));
    }

    @DeleteMapping("/{provider}")
    public ApiResponse<DeleteResponse> delete(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable ModelProvider provider) {
        var r = resolver.requireOrg(ctx, orgSlug);
        capabilities.require(r.org().id(), Capability.BYO_PROVIDER_KEYS);
        boolean deleted = repo.deleteByOrgAndProvider(r.org().id(), provider);
        factory.invalidate(r.org().id(), provider);
        return ApiResponse.ok(new DeleteResponse(deleted));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Bedrock needs a region (AWS auth). {@code api_key}/{@code none} platforms are not
     * checked — a credential may be saved without a key; a paid platform then fails at run
     * time with {@code MISSING_CREDENTIALS} (only Ollama may run keyless).
     *
     * <p>{@code auth_mode} is meaningful only for {@code AUTH_AWS} platforms (Bedrock/mantle) — a
     * non-AWS platform saving {@code iam_role} would be a setting that can never take effect, since
     * {@code ChatModelFactory} only ever reads {@link ProviderCredential#usesIamRole()} on that build
     * path.
     */
    private static void validateAuth(ProviderCredential row) {
        boolean awsAuth = PlatformCatalog.AUTH_AWS.equals(PlatformCatalog.authOf(row.provider()));
        if (awsAuth && row.awsRegion() == null) {
            throw new EvalsException(ModelConfigError.BEDROCK_MISSING_REGION, row.provider());
        }
        String mode = row.authMode();
        if (mode != null
                && !ProviderCredential.AUTH_MODE_API_KEY.equals(mode)
                && !ProviderCredential.AUTH_MODE_IAM_ROLE.equals(mode)) {
            throw new EvalsException(ModelConfigError.UNKNOWN_PROVIDER, "auth_mode " + mode);
        }
        if (!awsAuth && ProviderCredential.AUTH_MODE_IAM_ROLE.equals(mode)) {
            throw new EvalsException(ModelConfigError.UNKNOWN_PROVIDER, "auth_mode iam_role on " + row.provider());
        }
    }

    private static View toView(ProviderCredential c) {
        return new View(
                c.id(),
                c.provider(),
                c.baseUrlOverride(),
                c.apiKeySealed() != null,
                c.awsRegion(),
                // An IAM-role credential has no sealed keys and needs none — it is still a fully
                // configured credential, so it must read as one to the settings page rather than as
                // "missing".
                c.usesIamRole() || (c.awsAccessKeySealed() != null && c.awsSecretKeySealed() != null),
                c.bedrockModelArn(),
                c.customModelName(),
                c.authMode(),
                c.createdAt(),
                c.updatedAt());
    }
}
