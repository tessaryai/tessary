// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.crypto.SecretBox;
import ai.tessary.ingest.UrlGuard;
import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.llm.catalog.ProviderModel;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Ids;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Secrets are sealed via {@link SecretBox} and never returned. Every verb is gated on
 * {@link Capability#BYO_PROVIDER_KEYS}, including reads, because an API caller could otherwise store a key;
 * {@code /catalog} stays open since other surfaces read it.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/providers")
public class ProviderCredentialController {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialController.class);

    private final ProviderCredentialRepository repo;
    private final SecretBox secretBox;
    private final TenantPathResolver resolver;
    private final CapabilityService capabilities;

    private final ModelCatalogFetchService catalogFetchService;

    private final ApplicationEventPublisher events;

    private final PlatformProviderSupplier platformSupplier;

    private final Duration fetchDeadline;

    @Autowired
    public ProviderCredentialController(
            ProviderCredentialRepository repo,
            SecretBox secretBox,
            TenantPathResolver resolver,
            CapabilityService capabilities,
            ModelCatalogFetchService catalogFetchService,
            ApplicationEventPublisher events,
            PlatformProviderSupplier platformSupplier) {
        this(
                repo,
                secretBox,
                resolver,
                capabilities,
                catalogFetchService,
                events,
                platformSupplier,
                PER_PROVIDER_FETCH_DEADLINE);
    }

    ProviderCredentialController(
            ProviderCredentialRepository repo,
            SecretBox secretBox,
            TenantPathResolver resolver,
            CapabilityService capabilities,
            ModelCatalogFetchService catalogFetchService,
            ApplicationEventPublisher events,
            PlatformProviderSupplier platformSupplier,
            Duration fetchDeadline) {
        this.fetchDeadline = fetchDeadline;
        this.repo = repo;
        this.events = events;
        this.secretBox = secretBox;
        this.resolver = resolver;
        this.capabilities = capabilities;
        this.catalogFetchService = catalogFetchService;
        this.platformSupplier = platformSupplier;
    }

    /** Every field is optional: null leaves the stored value; a non-blank secret replaces and re-seals it. */
    public record UpsertRequest(
            @JsonProperty("base_url_override") String baseUrlOverride,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("aws_region") String awsRegion,
            @JsonProperty("aws_access_key") String awsAccessKey,
            @JsonProperty("aws_secret_key") String awsSecretKey,
            @JsonProperty("bedrock_model_arn") String bedrockModelArn,
            @JsonProperty("custom_model_name") String customModelName,
            /** A fresh credential with no auth_mode defaults to {@code api_key}. */
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

    public record CatalogView(
            List<PlatformCatalog.PlatformDescriptor> platforms, List<ModelCatalog.CatalogEntry> models) {}

    public record CredentialListView(List<View> credentials) {}

    public record DeleteResponse(boolean deleted) {}

    /** A backstop for hangs the per-request timeout does not cover (e.g. DNS), not the primary latency control. */
    private static final Duration PER_PROVIDER_FETCH_DEADLINE = Duration.ofSeconds(10);

    /**
     * An unconfigured or failing provider contributes its static entries, so it still shows as a disabled option.
     * Fetches run concurrently because providers configured together expire together.
     */
    @GetMapping("/catalog")
    public ApiResponse<CatalogView> catalog(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        Optional<PlatformProviderSupplier.SuppliedProvider> supplied =
                platformSupplier.describe(r.org().id());
        Map<ModelProvider, List<ProviderModel>> live = fetchAllProviders(r.org().id());
        List<ModelCatalog.CatalogEntry> models = new ArrayList<>();
        for (ModelProvider provider : ModelProvider.values()) {
            if (provider == ModelProvider.PLATFORM && supplied.isEmpty()) continue;
            List<ProviderModel> providerLive = live.getOrDefault(provider, List.of());
            List<ModelCatalog.CatalogEntry> providerEntries = providerLive.isEmpty()
                    ? ModelCatalog.entries().stream()
                            .filter(e -> e.provider() == provider)
                            .toList()
                    : ModelCatalog.mergeLive(provider, providerLive);
            models.addAll(providerEntries);
        }
        return ApiResponse.ok(new CatalogView(platforms(supplied), List.copyOf(models)));
    }

    private static List<PlatformCatalog.PlatformDescriptor> platforms(
            Optional<PlatformProviderSupplier.SuppliedProvider> supplied) {
        List<PlatformCatalog.PlatformDescriptor> out = new ArrayList<>();
        for (PlatformCatalog.PlatformDescriptor p : PlatformCatalog.platforms()) {
            if (p.id() != ModelProvider.PLATFORM) {
                out.add(p);
            } else {
                supplied.ifPresent(s -> out.add(new PlatformCatalog.PlatformDescriptor(
                        p.id(), s.label(), p.auth(), p.supportsBaseUrl(), p.defaultBaseUrl(), p.usedBy(), s.detail())));
            }
        }
        return List.copyOf(out);
    }

    /**
     * A provider that misses the deadline or fails is absent from the map, which the caller treats as "fall back to
     * static entries".
     */
    private Map<ModelProvider, List<ProviderModel>> fetchAllProviders(String orgId) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<ModelProvider, Future<List<ProviderModel>>> futures = new EnumMap<>(ModelProvider.class);
            for (ModelProvider provider : ModelProvider.values()) {
                if (provider == ModelProvider.PLATFORM) continue;
                futures.put(provider, executor.submit(() -> catalogFetchService.refreshingRead(orgId, provider)));
            }
            Map<ModelProvider, List<ProviderModel>> results = new EnumMap<>(ModelProvider.class);
            for (Map.Entry<ModelProvider, Future<List<ProviderModel>>> entry : futures.entrySet()) {
                try {
                    results.put(entry.getKey(), entry.getValue().get(fetchDeadline.toMillis(), TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                } catch (ExecutionException e) {
                    // refreshingRead already degrades ModelListingException; reaching here is unexpected, but gets the
                    // same fallback.
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
        requireEditable(provider);
        capabilities.require(r.org().id(), Capability.BYO_PROVIDER_KEYS);

        // SSRF guard: a base-URL override becomes a server-side outbound target, so reject
        // loopback/link-local/RFC1918/CGNAT/IMDS hosts at write time.
        if (req.baseUrlOverride() != null && !req.baseUrlOverride().isBlank()) {
            UrlGuard.requirePublicHttp(req.baseUrlOverride());
        }

        boolean settingApiKey = req.apiKey() != null && !req.apiKey().isBlank();
        boolean settingAwsKeys = req.awsAccessKey() != null
                && !req.awsAccessKey().isBlank()
                && req.awsSecretKey() != null
                && !req.awsSecretKey().isBlank();
        if ((settingApiKey || settingAwsKeys) && !secretBox.isConfigured()) {
            throw new TessaryException(ModelConfigError.SECRET_KEY_NOT_CONFIGURED);
        }

        ProviderCredential existing =
                repo.findByOrgAndProvider(r.org().id(), provider).orElse(null);
        String now = Instant.now().toString();
        ProviderCredential row = new ProviderCredential(
                existing != null ? existing.id() : Ids.ulid(),
                r.org().id(),
                // project_id is historical only; a fresh row names no project.
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
        events.publishEvent(new ProviderCredentialSavedEvent(r.org().id(), provider));
        return ApiResponse.ok(toView(row));
    }

    @DeleteMapping("/{provider}")
    public ApiResponse<DeleteResponse> delete(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable ModelProvider provider) {
        var r = resolver.requireOrg(ctx, orgSlug);
        requireEditable(provider);
        capabilities.require(r.org().id(), Capability.BYO_PROVIDER_KEYS);
        boolean deleted = repo.deleteByOrgAndProvider(r.org().id(), provider);
        return ApiResponse.ok(new DeleteResponse(deleted));
    }

    /** A stored PLATFORM row would be a key nothing reads. */
    private static void requireEditable(ModelProvider provider) {
        if (provider == ModelProvider.PLATFORM) {
            throw new TessaryException(ModelConfigError.PROVIDER_NOT_EDITABLE, provider);
        }
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }

    /**
     * A credential may be saved without a key and fail at run time with {@code MISSING_CREDENTIALS}. {@code iam_role}
     * is rejected off AWS platforms, where it could never take effect.
     */
    private static void validateAuth(ProviderCredential row) {
        boolean awsAuth = PlatformCatalog.AUTH_AWS.equals(PlatformCatalog.authOf(row.provider()));
        if (awsAuth && row.awsRegion() == null) {
            throw new TessaryException(ModelConfigError.BEDROCK_MISSING_REGION, row.provider());
        }
        String mode = row.authMode();
        if (mode != null
                && !ProviderCredential.AUTH_MODE_API_KEY.equals(mode)
                && !ProviderCredential.AUTH_MODE_IAM_ROLE.equals(mode)) {
            throw new TessaryException(ModelConfigError.UNKNOWN_PROVIDER, "auth_mode " + mode);
        }
        if (!awsAuth && ProviderCredential.AUTH_MODE_IAM_ROLE.equals(mode)) {
            throw new TessaryException(ModelConfigError.UNKNOWN_PROVIDER, "auth_mode iam_role on " + row.provider());
        }
    }

    private static View toView(ProviderCredential c) {
        return new View(
                c.id(),
                c.provider(),
                c.baseUrlOverride(),
                c.apiKeySealed() != null,
                c.awsRegion(),
                // An IAM-role credential has no sealed keys but is fully configured, so it must not read as "missing".
                c.usesIamRole() || (c.awsAccessKeySealed() != null && c.awsSecretKeySealed() != null),
                c.bedrockModelArn(),
                c.customModelName(),
                c.authMode(),
                c.createdAt(),
                c.updatedAt());
    }
}
