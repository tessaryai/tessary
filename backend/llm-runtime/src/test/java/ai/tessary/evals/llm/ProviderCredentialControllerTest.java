// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.llm.catalog.ModelCatalogFetchService;
import ai.tessary.evals.open.errors.CapabilityError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.tenant.Organization;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * First test coverage for {@link ProviderCredentialController} (issue #861 — confirmed absent
 * before this file: {@code ls backend/llm-runtime/src/test/java/ai/tessary/evals/llm/} had no
 * {@code ProviderCredentialControllerTest.java}).
 *
 * <p>#939 D1 moved credentials off {@code (project, provider)} to {@code (org, provider)} — the
 * route dropped {@code {projectSlug}} and every collaborator call here is keyed by {@code ORG_ID},
 * not {@code PROJECT_ID}.
 *
 * <p>No Spring context, no Testcontainers: {@link ProviderCredentialRepository} is a plain
 * {@code JdbcClient} wrapper (not Spring Data), so every collaborator here is a Mockito mock and
 * the controller is constructed and called directly, {@code TenantContext} passed as an ordinary
 * method argument the way {@code TenantArgumentResolver} would hand it in at request time.
 */
@ExtendWith(MockitoExtension.class)
class ProviderCredentialControllerTest {

    private static final String ORG_SLUG = "acme";
    private static final String ORG_ID = "org_1";

    @Mock
    private ProviderCredentialRepository repo;

    @Mock
    private ChatModelFactory factory;

    @Mock
    private SecretBox secretBox;

    @Mock
    private TenantPathResolver resolver;

    @Mock
    private CapabilityService capabilities;

    /** #939 TASK 2: unstubbed here means every provider's {@code refreshingRead} returns empty,
     *  which {@code catalog()} falls back to the static entries for — the same catalog shape every
     *  test in this file that touches {@code CatalogView} already expects. */
    @Mock
    private ModelCatalogFetchService catalogFetchService;

    private ProviderCredentialController controller;
    private TenantContext ctx;
    private TenantPathResolver.OrgResolved resolved;

    @BeforeEach
    void setUp() {
        controller =
                new ProviderCredentialController(repo, factory, secretBox, resolver, capabilities, catalogFetchService);
        ctx = new TenantContext("user_1", "user@example.com", ORG_ID, null, "owner", null);
        Organization org = new Organization(ORG_ID, null, ORG_SLUG, "Acme", "2026-01-01T00:00:00Z", null, null);
        resolved = new TenantPathResolver.OrgResolved(org, "owner");
        when(resolver.requireOrg(ctx, ORG_SLUG)).thenReturn(resolved);
    }

    // ---- capability gate: list/upsert/delete all 403 the same way the doc says every verb does ----

    @Test
    void listThrowsDisabledWhenCapabilityGateThrows() {
        doThrow(new EvalsException(CapabilityError.DISABLED, Capability.BYO_PROVIDER_KEYS.wire()))
                .when(capabilities)
                .require(ORG_ID, Capability.BYO_PROVIDER_KEYS);

        EvalsException e = assertThrows(EvalsException.class, () -> controller.list(ctx, ORG_SLUG));
        assertEquals(CapabilityError.DISABLED, e.error());
    }

    @Test
    void upsertThrowsDisabledWhenCapabilityGateThrows() {
        doThrow(new EvalsException(CapabilityError.DISABLED, Capability.BYO_PROVIDER_KEYS.wire()))
                .when(capabilities)
                .require(ORG_ID, Capability.BYO_PROVIDER_KEYS);
        var req = new ProviderCredentialController.UpsertRequest(
                null, "sk-live-abc123", null, null, null, null, null, null);

        EvalsException e =
                assertThrows(EvalsException.class, () -> controller.upsert(ctx, ORG_SLUG, ModelProvider.OPENAI, req));
        assertEquals(CapabilityError.DISABLED, e.error());
    }

    @Test
    void deleteThrowsDisabledWhenCapabilityGateThrows() {
        doThrow(new EvalsException(CapabilityError.DISABLED, Capability.BYO_PROVIDER_KEYS.wire()))
                .when(capabilities)
                .require(ORG_ID, Capability.BYO_PROVIDER_KEYS);

        assertThrows(EvalsException.class, () -> controller.delete(ctx, ORG_SLUG, ModelProvider.OPENAI));
    }

    // ---- "never echoed back in full" — issue #861's already-verified-true criterion, pinned here ----

    @Test
    void upsertResponseNeverCarriesTheRawSecretOnlyBooleans() {
        when(secretBox.isConfigured()).thenReturn(true);
        when(secretBox.seal("sk-live-abc123")).thenReturn("sealed-ciphertext");
        when(repo.findByOrgAndProvider(ORG_ID, ModelProvider.OPENAI)).thenReturn(Optional.empty());
        var req = new ProviderCredentialController.UpsertRequest(
                null, "sk-live-abc123", null, null, null, null, null, null);

        var response = controller.upsert(ctx, ORG_SLUG, ModelProvider.OPENAI, req);

        ProviderCredentialController.View view = response.data();
        assertTrue(view.hasApiKey());
        assertFalse(view.hasAwsCredentials());
        // The View record's only fields are hasApiKey/hasAwsCredentials booleans plus non-secret
        // metadata (id, provider, timestamps, base_url_override, bedrock_model_arn, aws_region) —
        // there is no field a raw key could even be assigned to, but assert the ciphertext we sealed
        // never leaks into the response's string form either, in case a future field adds one.
        assertFalse(view.toString().contains("sk-live-abc123"));
        assertFalse(view.toString().contains("sealed-ciphertext"));
    }

    // ---- keep-vs-replace semantics ----

    @Test
    void blankFieldsOnUpsertKeepTheExistingCredentialRatherThanClearingIt() {
        ProviderCredential existing = new ProviderCredential(
                "cred_1",
                ORG_ID,
                null,
                ModelProvider.OPENAI,
                "https://existing.example.com",
                "existing-sealed-key",
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
        when(repo.findByOrgAndProvider(ORG_ID, ModelProvider.OPENAI)).thenReturn(Optional.of(existing));
        // Every field null/blank: "leave the stored value untouched" per the record's own javadoc.
        var req = new ProviderCredentialController.UpsertRequest(null, null, null, null, null, null, null, null);

        var response = controller.upsert(ctx, ORG_SLUG, ModelProvider.OPENAI, req);

        assertTrue(response.data().hasApiKey(), "a blank api_key must not clear an existing sealed key");
        assertEquals("https://existing.example.com", response.data().baseUrlOverride());
        verify(repo).update(any());
        verify(secretBox, never()).seal(any());
    }

    @Test
    void aNonBlankFieldOnUpsertReplacesTheExistingCredential() {
        ProviderCredential existing = new ProviderCredential(
                "cred_1",
                ORG_ID,
                null,
                ModelProvider.OPENAI,
                null,
                "old-sealed-key",
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
        when(repo.findByOrgAndProvider(ORG_ID, ModelProvider.OPENAI)).thenReturn(Optional.of(existing));
        when(secretBox.isConfigured()).thenReturn(true);
        when(secretBox.seal("sk-new-key")).thenReturn("new-sealed-key");
        var req =
                new ProviderCredentialController.UpsertRequest(null, "sk-new-key", null, null, null, null, null, null);

        controller.upsert(ctx, ORG_SLUG, ModelProvider.OPENAI, req);

        verify(secretBox).seal("sk-new-key");
        verify(repo).update(any());
    }

    // ---- delete invalidates the factory's cached client (org-wide, #939 D1) so the next call rebuilds ----

    @Test
    void deleteInvokesFactoryInvalidate() {
        when(repo.deleteByOrgAndProvider(ORG_ID, ModelProvider.OPENAI)).thenReturn(true);

        var response = controller.delete(ctx, ORG_SLUG, ModelProvider.OPENAI);

        assertTrue(response.data().deleted());
        verify(factory, times(1)).invalidate(ORG_ID, ModelProvider.OPENAI);
    }

    // ---- catalog() fans refreshingRead out across providers instead of blocking sequentially ----

    /**
     * Coverage for the concurrent fan-out added to {@code catalog()}: every {@code ModelProvider} gets
     * its own {@code refreshingRead} call, and results merge back onto the static table exactly like
     * the old sequential loop did — a live listing for one provider must not affect any other's static
     * fallback entries.
     */
    @Test
    void catalogMergesALiveListingForOneProviderOntoStaticEntriesForEveryOther() {
        // catalog() invokes refreshingRead for all ten providers concurrently on virtual threads.
        // Every provider gets an explicit lenient stub (rather than leaning on the mock's default
        // empty-list answer for the other nine) so Mockito's strict-stub argument matching — which is
        // not documented as safe under concurrent invocation of the same mocked method — has one
        // unambiguous stubbing per provider to satisfy instead of racing to decide whether a call with
        // different arguments than the one explicit stub below is a mismatch.
        for (ModelProvider provider : ModelProvider.values()) {
            org.mockito.Mockito.lenient()
                    .when(catalogFetchService.refreshingRead(ORG_ID, provider))
                    .thenReturn(java.util.List.of());
        }
        when(catalogFetchService.refreshingRead(ORG_ID, ModelProvider.ANTHROPIC))
                .thenReturn(java.util.List.of(
                        new ai.tessary.evals.llm.catalog.ProviderModel("claude-live-9", "Claude Live 9", "Anthropic")));

        var response = controller.catalog(ctx, ORG_SLUG);

        var models = response.data().models();
        assertTrue(
                models.stream().anyMatch(e -> "claude-live-9".equals(e.modelName())),
                "the live-fetched Anthropic model must appear in the merged catalog");
        assertTrue(
                models.stream().anyMatch(e -> e.provider() == ModelProvider.OPENAI),
                "an unrelated provider's static entries must still be present");
        // Every provider is asked — the fan-out covers all ten, not just the one stubbed above.
        for (ModelProvider provider : ModelProvider.values()) {
            verify(catalogFetchService).refreshingRead(ORG_ID, provider);
        }
    }

    /**
     * A provider whose fetch fails outright (as opposed to {@code refreshingRead}'s own internal
     * degrade-to-empty-list on a {@code ModelListingException}) must not fail the whole request — it
     * degrades to that provider's static entries, the same as every other failure path in this class's
     * javadoc promises.
     */
    @Test
    void catalogDegradesToStaticEntriesWhenOneProvidersFetchThrowsUnexpectedly() {
        // Same rationale as the test above: an explicit lenient stub per provider avoids Mockito's
        // strict-stub argument-mismatch check racing across concurrent virtual-thread invocations.
        for (ModelProvider provider : ModelProvider.values()) {
            org.mockito.Mockito.lenient()
                    .when(catalogFetchService.refreshingRead(ORG_ID, provider))
                    .thenReturn(java.util.List.of());
        }
        when(catalogFetchService.refreshingRead(ORG_ID, ModelProvider.OPENAI))
                .thenThrow(new RuntimeException("unexpected failure"));

        var response = controller.catalog(ctx, ORG_SLUG);

        assertTrue(
                response.data().models().stream().anyMatch(e -> e.provider() == ModelProvider.OPENAI),
                "OpenAI's static entries must still be present despite its fetch throwing");
        assertTrue(
                response.data().models().stream().anyMatch(e -> e.provider() == ModelProvider.ANTHROPIC),
                "an unrelated provider must be entirely unaffected by OpenAI's failure");
    }
}
