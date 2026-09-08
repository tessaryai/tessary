// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.evals.config.ModelCatalogProperties;
import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.llm.ModelProvider;
import ai.tessary.evals.llm.ProviderCredential;
import ai.tessary.evals.llm.ProviderCredentialRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The corrective brief's own four required cases: cache/TTL, stale-fallback on a failed refetch,
 * no-credential-contributes-nothing, and one provider's failure not emptying another's cache — plus
 * the (provider, region) key shape (mandatory property i) and the two read paths' blocking contract.
 */
class ModelCatalogFetchServiceTest {

    private static final String ORG = "org_1";

    /** A {@link ProviderModelLister} whose behavior is scripted call-by-call, with a call counter so
     *  tests can assert exactly how many times the network/AWS call would actually have happened. */
    private static final class ScriptedLister implements ProviderModelLister {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Object> script; // each element is List<ProviderModel> or RuntimeException

        ScriptedLister(List<Object> script) {
            this.script = script;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<ProviderModel> list(ResolvedCredential credential) {
            int i = calls.getAndIncrement();
            Object step = script.get(Math.min(i, script.size() - 1));
            if (step instanceof RuntimeException e) throw e;
            return (List<ProviderModel>) step;
        }

        int callCount() {
            return calls.get();
        }
    }

    private static ProviderCredential apiKeyCred(ModelProvider provider) {
        return new ProviderCredential(
                "cred_1", ORG, null, provider, null, "sealed-key", null, null, null, null, null, "api_key", "t0", "t0");
    }

    private static ProviderCredential bedrockCred(ModelProvider provider, String region) {
        return new ProviderCredential(
                "cred_1",
                ORG,
                null,
                provider,
                null,
                null,
                region,
                "sealed-access",
                "sealed-secret",
                null,
                null,
                "api_key",
                "t0",
                "t0");
    }

    private static ModelCatalogFetchService service(
            ProviderCredentialRepository repo, ModelProvider provider, ProviderModelLister lister) {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setRefreshInterval(Duration.ofHours(1)); // long enough that no test's cache expires by accident
        SecretBox secretBox = mock(SecretBox.class);
        when(secretBox.open(anyString())).thenReturn("decrypted");
        return new ModelCatalogFetchService(repo, secretBox, props, java.util.Map.of(provider, lister));
    }

    private static final List<ProviderModel> ONE_MODEL = List.of(new ProviderModel("m1", "Model 1", "OpenAI"));
    private static final List<ProviderModel> OTHER_MODEL = List.of(new ProviderModel("m2", "Model 2", "OpenAI"));

    @Test
    void noCredential_contributesNothingAndNeverCallsTheLister() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI)).thenReturn(Optional.empty());
        ScriptedLister lister = new ScriptedLister(List.of(ONE_MODEL));
        ModelCatalogFetchService svc = service(repo, ModelProvider.OPENAI, lister);

        assertEquals(List.of(), svc.refreshingRead(ORG, ModelProvider.OPENAI));
        assertEquals(List.of(), svc.cachedRead(ORG, ModelProvider.OPENAI));
        assertEquals(0, lister.callCount(), "no credential means no fetch attempt at all");
    }

    @Test
    void refreshingReadFetchesOnceThenServesTheCacheWithinTheTtl() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        ScriptedLister lister = new ScriptedLister(List.of(ONE_MODEL));
        ModelCatalogFetchService svc = service(repo, ModelProvider.OPENAI, lister);

        assertEquals(ONE_MODEL, svc.refreshingRead(ORG, ModelProvider.OPENAI));
        assertEquals(ONE_MODEL, svc.refreshingRead(ORG, ModelProvider.OPENAI));
        assertEquals(ONE_MODEL, svc.refreshingRead(ORG, ModelProvider.OPENAI));
        assertEquals(1, lister.callCount(), "a fresh cache entry must not trigger a refetch");
    }

    @Test
    void cachedReadNeverFetches_returnsEmptyOnAColdCache() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        ScriptedLister lister = new ScriptedLister(List.of(ONE_MODEL));
        ModelCatalogFetchService svc = service(repo, ModelProvider.OPENAI, lister);

        assertEquals(List.of(), svc.cachedRead(ORG, ModelProvider.OPENAI), "nothing cached yet");
        assertEquals(0, lister.callCount(), "cachedRead must never itself trigger a fetch");

        svc.refreshingRead(ORG, ModelProvider.OPENAI); // warms the cache
        assertEquals(ONE_MODEL, svc.cachedRead(ORG, ModelProvider.OPENAI));
        assertEquals(1, lister.callCount(), "the warm-up fetch, and only that one");
    }

    @Test
    void aFailedRefetchServesTheStaleEntryRegardlessOfAge() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        ScriptedLister lister = new ScriptedLister(List.of(ONE_MODEL, new ModelListingException("vendor down", null)));
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setRefreshInterval(Duration.ofMillis(1)); // expires almost immediately
        SecretBox secretBox = mock(SecretBox.class);
        when(secretBox.open(anyString())).thenReturn("decrypted");
        ModelCatalogFetchService svc =
                new ModelCatalogFetchService(repo, secretBox, props, java.util.Map.of(ModelProvider.OPENAI, lister));

        assertEquals(ONE_MODEL, svc.refreshingRead(ORG, ModelProvider.OPENAI), "first fetch succeeds");
        sleepPastTtl();
        List<ProviderModel> result = svc.refreshingRead(ORG, ModelProvider.OPENAI);

        assertEquals(ONE_MODEL, result, "mandatory property (iii): a failed refetch serves the stale entry");
        assertEquals(2, lister.callCount(), "the refetch was attempted, not skipped");
    }

    @Test
    void aFailureWithNothingCachedYet_returnsEmptyRatherThanThrowing() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        ScriptedLister lister = new ScriptedLister(List.of(new ModelListingException("vendor down", null)));
        ModelCatalogFetchService svc = service(repo, ModelProvider.OPENAI, lister);

        assertEquals(List.of(), svc.refreshingRead(ORG, ModelProvider.OPENAI));
    }

    @Test
    void aNegativeCacheEntry_isNotRefetchedOnTheVeryNextRead() {
        // Within the negative TTL, a second read must still not hammer a vendor that just failed.
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        ScriptedLister lister = new ScriptedLister(List.of(new ModelListingException("vendor down", null)));
        ModelCatalogFetchService svc = service(repo, ModelProvider.OPENAI, lister);

        svc.refreshingRead(ORG, ModelProvider.OPENAI);
        svc.refreshingRead(ORG, ModelProvider.OPENAI);

        assertEquals(1, lister.callCount(), "the negative-TTL entry must be served, not refetched immediately");
    }

    @Test
    void oneProvidersFailureDoesNotEmptyAnotherProvidersCache() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        when(repo.findByOrgAndProvider(ORG, ModelProvider.ANTHROPIC))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.ANTHROPIC)));
        ScriptedLister openai = new ScriptedLister(List.of(new ModelListingException("vendor down", null)));
        ScriptedLister anthropic = new ScriptedLister(List.of(OTHER_MODEL));
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setRefreshInterval(Duration.ofHours(1));
        SecretBox secretBox = mock(SecretBox.class);
        when(secretBox.open(anyString())).thenReturn("decrypted");
        ModelCatalogFetchService svc = new ModelCatalogFetchService(
                repo,
                secretBox,
                props,
                java.util.Map.of(ModelProvider.OPENAI, openai, ModelProvider.ANTHROPIC, anthropic));

        assertEquals(List.of(), svc.refreshingRead(ORG, ModelProvider.OPENAI), "OpenAI is down");
        assertEquals(OTHER_MODEL, svc.refreshingRead(ORG, ModelProvider.ANTHROPIC), "Anthropic is unaffected");
    }

    @Test
    void bedrockCacheKeyIsScopedByRegion_twoRegionsFetchIndependently() {
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        // Same org, same provider, but the cred (and therefore the region) differs per read — this
        // simulates the cache being warmed by one org's us-east-1 credential and read by another
        // org's eu-west-1 one, since the corrective brief's mandatory property (i) makes the cache key
        // (provider, region), not (org, provider).
        when(repo.findByOrgAndProvider("org_us", ModelProvider.BEDROCK))
                .thenReturn(Optional.of(bedrockCred(ModelProvider.BEDROCK, "us-east-1")));
        when(repo.findByOrgAndProvider("org_eu", ModelProvider.BEDROCK))
                .thenReturn(Optional.of(bedrockCred(ModelProvider.BEDROCK, "eu-west-1")));
        ScriptedLister lister = new ScriptedLister(List.of(ONE_MODEL, OTHER_MODEL));
        ModelCatalogFetchService svc = service(repo, ModelProvider.BEDROCK, lister);

        assertEquals(ONE_MODEL, svc.refreshingRead("org_us", ModelProvider.BEDROCK));
        assertEquals(OTHER_MODEL, svc.refreshingRead("org_eu", ModelProvider.BEDROCK));
        assertEquals(2, lister.callCount(), "two different regions must not share a cache entry");

        // Re-reading the first region again must hit ITS cache, not the second region's.
        assertEquals(ONE_MODEL, svc.refreshingRead("org_us", ModelProvider.BEDROCK));
        assertEquals(2, lister.callCount(), "the us-east-1 entry was still warm");
    }

    @Test
    void nonBedrockNonCustomProvidersShareOneCacheEntryAcrossOrgs() {
        // Mandatory property (i): a provider's model list does not vary by which org's key asks.
        ProviderCredentialRepository repo = mock(ProviderCredentialRepository.class);
        when(repo.findByOrgAndProvider("org_a", ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        when(repo.findByOrgAndProvider("org_b", ModelProvider.OPENAI))
                .thenReturn(Optional.of(apiKeyCred(ModelProvider.OPENAI)));
        ScriptedLister lister = new ScriptedLister(List.of(ONE_MODEL));
        ModelCatalogFetchService svc = service(repo, ModelProvider.OPENAI, lister);

        assertEquals(ONE_MODEL, svc.refreshingRead("org_a", ModelProvider.OPENAI));
        assertEquals(ONE_MODEL, svc.refreshingRead("org_b", ModelProvider.OPENAI));

        assertEquals(1, lister.callCount(), "org_b's read reused org_a's cache entry — one shared (provider, region)");
    }

    private static void sleepPastTtl() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
