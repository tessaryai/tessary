// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import ai.tessary.config.ModelCatalogProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.PlatformCatalog;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The live model catalog, fetched from each configured provider's own API and cached — the piece of
 * #939 D6 the earlier pass of this issue did not build. {@link ai.tessary.llm.ModelCatalog}
 * stays the static per-model CAPABILITY table (agentic, effort levels, strict JSON schema); this
 * service answers the orthogonal question, "what models does this org's credential actually see
 * right now" — {@code ModelCatalog#mergeLive} is where the two are reconciled into what the settings
 * page and {@code ChatModelFactory} actually use.
 *
 * <h2>Cache key: {@code (provider, region)}, never {@code (org, provider)}</h2>
 *
 * <p>A provider's model list does not vary by which org's key asks — two orgs with their own OpenAI
 * keys see the same OpenAI catalog — so keying by org would mean N orgs on the same provider paying
 * for N identical fetches. The one place "region" is not a formality is Bedrock/mantle, where two
 * credentials can legitimately point at different AWS regions serving different models; {@link
 * ModelProvider#CUSTOM} is the other exception, where two orgs' custom endpoints are unrelated data
 * sources and the credential's own {@code baseUrlOverride} stands in for "region". Every other
 * provider collapses to {@link #SINGLE_REGION}, a fixed sentinel, so all orgs on (say) Anthropic share
 * one cache entry.
 *
 * <h2>Failure handling (mandatory property iii)</h2>
 *
 * <p>A vendor outage must never empty the picker for every OTHER provider, and ideally not even for
 * the failing one if there is anything to fall back to. {@link #fetch} therefore serves the existing
 * cached entry regardless of its age on a failure, and only falls through to an empty, short-TTL
 * negative cache entry when there was nothing cached yet at all — see {@link #NEGATIVE_TTL}.
 *
 * <h2>Two read paths</h2>
 *
 * <p>{@link #refreshingRead} blocks on a fetch when the entry is missing or past its TTL — the
 * settings page and catalog endpoints use this, where a slower response is an acceptable price for
 * fresher data. {@link #cachedRead} never fetches — {@code ChatModelFactory}'s hot judge-call path
 * uses this, so a vendor being slow or down adds no latency to grading; a cold entry there simply
 * contributes nothing until some other reader has warmed it.
 */
@Component
public class ModelCatalogFetchService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogFetchService.class);

    /** Cache-key region for every provider whose model list is not credential-region-scoped. */
    private static final String SINGLE_REGION = "-";

    /**
     * How long an empty, failure-caused cache entry is trusted before the next read retries the fetch.
     * Deliberately much shorter than {@link ModelCatalogProperties#getRefreshInterval()}: a healthy
     * fetch earns the long TTL, but a fetch that just failed with nothing to fall back to should not
     * leave a provider looking permanently empty for a quarter of an hour over one bad request.
     */
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    private final ProviderCredentialRepository credentials;
    private final SecretBox secretBox;
    private final ModelCatalogProperties properties;
    private final Map<ModelProvider, ProviderModelLister> listers;

    private final ConcurrentMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public ModelCatalogFetchService(
            ProviderCredentialRepository credentials,
            SecretBox secretBox,
            ModelCatalogProperties properties,
            ObjectMapper mapper) {
        this(credentials, secretBox, properties, defaultListers(mapper, properties.getFetchTimeout()));
    }

    /** Test seam — a fake {@link ProviderModelLister} per provider, no real HTTP/AWS calls. */
    ModelCatalogFetchService(
            ProviderCredentialRepository credentials,
            SecretBox secretBox,
            ModelCatalogProperties properties,
            Map<ModelProvider, ProviderModelLister> listers) {
        this.credentials = credentials;
        this.secretBox = secretBox;
        this.properties = properties;
        this.listers = listers;
    }

    private static Map<ModelProvider, ProviderModelLister> defaultListers(ObjectMapper mapper, Duration fetchTimeout) {
        // Deliberately never closed: this HttpClient is shared across every OpenAI-compat/Anthropic/
        // OpenRouter lister for the lifetime of the service (retained inside the Map this method
        // returns), the same pattern every other long-lived HttpClient field in this codebase uses
        // (WorkOsClient, GithubClient, HttpJson, ...). PMD's CloseResource check cannot trace the
        // reference escaping into eight separate constructor calls in a fan-out like this one.
        //
        // connectTimeout stays a fixed 5s rather than tessary.model-catalog.fetch-timeout: that property
        // bounds a single models-endpoint REQUEST (wired per-lister below via the request/apiCallTimeout
        // builder), not the shared client's TCP handshake, which is a distinct, much smaller concern.
        HttpClient http = // NOPMD - CloseResource: intentionally long-lived, see comment above
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Map<ModelProvider, ProviderModelLister> m = new EnumMap<>(ModelProvider.class);
        m.put(ModelProvider.OPENAI, new OpenAiCompatModelLister(http, mapper, "OpenAI", fetchTimeout));
        m.put(ModelProvider.MOONSHOT, new OpenAiCompatModelLister(http, mapper, "Moonshot", fetchTimeout));
        m.put(ModelProvider.GEMINI, new OpenAiCompatModelLister(http, mapper, "Google", fetchTimeout));
        m.put(ModelProvider.GLM, new OpenAiCompatModelLister(http, mapper, "Zhipu", fetchTimeout));
        m.put(ModelProvider.GROK, new OpenAiCompatModelLister(http, mapper, "xAI", fetchTimeout));
        m.put(ModelProvider.CUSTOM, new OpenAiCompatModelLister(http, mapper, "Custom", fetchTimeout));
        m.put(ModelProvider.ANTHROPIC, new AnthropicModelLister(http, mapper, fetchTimeout));
        m.put(ModelProvider.OPENROUTER, new OpenRouterModelLister(http, mapper, fetchTimeout));
        m.put(ModelProvider.BEDROCK, new BedrockModelLister(fetchTimeout));
        m.put(ModelProvider.BEDROCK_MANTLE, new BedrockMantleModelLister());
        return Collections.unmodifiableMap(m);
    }

    /** See the class javadoc's "Two read paths" section. */
    public List<ProviderModel> refreshingRead(String orgId, ModelProvider provider) {
        ProviderCredential cred =
                credentials.findByOrgAndProvider(orgId, provider).orElse(null);
        // Mandatory property (iv): no credential contributes nothing, not an error — same as an
        // unconfigured provider is treated everywhere else in #939 (D3's disabled-option pattern).
        if (cred == null) return List.of();
        CacheKey key = new CacheKey(provider, regionFor(provider, cred));
        CacheEntry entry = cache.get(key);
        if (entry != null && !expired(entry)) {
            return entry.models();
        }
        return fetch(key, provider, cred);
    }

    /** See the class javadoc's "Two read paths" section. */
    public List<ProviderModel> cachedRead(String orgId, ModelProvider provider) {
        ProviderCredential cred =
                credentials.findByOrgAndProvider(orgId, provider).orElse(null);
        if (cred == null) return List.of();
        CacheEntry entry = cache.get(new CacheKey(provider, regionFor(provider, cred)));
        return entry == null ? List.of() : entry.models();
    }

    private List<ProviderModel> fetch(CacheKey key, ModelProvider provider, ProviderCredential cred) {
        ProviderModelLister lister = listers.get(provider);
        if (lister == null) {
            // No lister wired for this provider (should not happen for a real ModelProvider value —
            // defaultListers() covers all ten — but a test-injected map may be intentionally partial).
            // Contribute nothing rather than throw, matching the no-credential case above.
            return List.of();
        }
        try {
            List<ProviderModel> models = lister.list(resolveCredential(provider, cred));
            cache.put(key, new CacheEntry(Instant.now(), models, false));
            return models;
        } catch (ModelListingException e) {
            log.warn("model listing failed for provider={} region={}: {}", provider, key.region(), e.getMessage());
            CacheEntry stale = cache.get(key);
            if (stale != null) {
                // Mandatory property (iii): a vendor outage serves the last-known-good list rather than
                // emptying this provider's options, however old that list has become.
                return stale.models();
            }
            cache.put(key, new CacheEntry(Instant.now(), List.of(), true));
            return List.of();
        }
    }

    private boolean expired(CacheEntry entry) {
        Duration ttl = entry.negative() ? NEGATIVE_TTL : properties.getRefreshInterval();
        return Instant.now().isAfter(entry.fetchedAt().plus(ttl));
    }

    /**
     * {@code (provider, region)} — mandatory property (i). Bedrock/mantle key on the credential's own
     * AWS region (never an env var — {@code MANTLE_REGION} and friends are gone under D4); CUSTOM
     * keys on the credential's endpoint, since two orgs' custom endpoints are unrelated data sources;
     * every other provider collapses to {@link #SINGLE_REGION}.
     */
    private static String regionFor(ModelProvider provider, ProviderCredential cred) {
        return switch (provider) {
            case BEDROCK, BEDROCK_MANTLE ->
                cred.awsRegion() == null || cred.awsRegion().isBlank()
                        ? SINGLE_REGION
                        : cred.awsRegion().trim();
            case CUSTOM ->
                cred.baseUrlOverride() == null || cred.baseUrlOverride().isBlank()
                        ? SINGLE_REGION
                        : cred.baseUrlOverride().trim();
            default -> SINGLE_REGION;
        };
    }

    /** Decrypt {@code cred} into the shape {@link ProviderModelLister} needs — see {@link
     *  ResolvedCredential}'s own javadoc for why this is not {@code AgenticCredentialResolver.Credential}. */
    private ResolvedCredential resolveCredential(ModelProvider provider, ProviderCredential cred) {
        boolean bedrock = provider == ModelProvider.BEDROCK || provider == ModelProvider.BEDROCK_MANTLE;
        if (bedrock) {
            return new ResolvedCredential(
                    null,
                    null,
                    cred.awsRegion(),
                    cred.usesIamRole() ? null : open(cred.awsAccessKeySealed()),
                    cred.usesIamRole() ? null : open(cred.awsSecretKeySealed()),
                    cred.usesIamRole());
        }
        String baseUrl =
                cred.baseUrlOverride() != null && !cred.baseUrlOverride().isBlank()
                        ? cred.baseUrlOverride()
                        : PlatformCatalog.find(provider)
                                .map(PlatformCatalog.PlatformDescriptor::defaultBaseUrl)
                                .orElse(null);
        return new ResolvedCredential(open(cred.apiKeySealed()), baseUrl, null, null, null, false);
    }

    private @Nullable String open(@Nullable String sealed) {
        return sealed == null || sealed.isBlank() ? null : secretBox.open(sealed);
    }

    private record CacheKey(ModelProvider provider, String region) {}

    /** @param negative true for a failure-caused empty entry — see {@link #NEGATIVE_TTL}. */
    private record CacheEntry(Instant fetchedAt, List<ProviderModel> models, boolean negative) {}
}
