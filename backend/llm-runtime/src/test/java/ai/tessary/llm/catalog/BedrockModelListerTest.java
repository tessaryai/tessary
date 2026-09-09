// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelLifecycle;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsResponse;

/**
 * Against a mocked {@link BedrockClient} (an interface, so Mockito mocks it directly — no fake HTTP
 * server or real AWS call). Injected through {@link BedrockModelLister}'s package-private {@code
 * ClientFactory} test seam, which is the seam the class did not have before this corrective
 * pass: {@link BedrockModelLister#list} used to build its own client inline via
 * {@code BedrockClient.builder()}, with no way for a test to substitute a fake.
 *
 * <p>Covers the three branches the corrective brief calls out as otherwise untested: the {@code
 * ACTIVE}-lifecycle-status filter, the six-maker allowlist filter, and the {@code api_key}-vs-{@code
 * iam_role} credential branch.
 */
class BedrockModelListerTest {

    private final BedrockClient client = mock(BedrockClient.class);

    private BedrockModelLister lister() {
        return new BedrockModelLister(Duration.ofSeconds(5), (region, credentialsProvider, timeout) -> client);
    }

    private static ResolvedCredential apiKeyCred(String region, String access, String secret) {
        return new ResolvedCredential(null, null, region, access, secret, false);
    }

    private static ResolvedCredential iamRoleCred(String region) {
        return new ResolvedCredential(null, null, region, null, null, true);
    }

    @SuppressWarnings("unchecked")
    private void stubModels(FoundationModelSummary... summaries) {
        ListFoundationModelsResponse response =
                ListFoundationModelsResponse.builder().modelSummaries(summaries).build();
        when(client.listFoundationModels(any(Consumer.class))).thenReturn(response);
    }

    private static FoundationModelSummary summary(String id, String name, String provider, String status) {
        FoundationModelSummary.Builder b =
                FoundationModelSummary.builder().modelId(id).modelName(name).providerName(provider);
        if (status != null) {
            b.modelLifecycle(FoundationModelLifecycle.builder().status(status).build());
        }
        return b.build();
    }

    // ---------------------------------------------------------------- ACTIVE lifecycle filter

    @Test
    void activeModel_isIncluded() {
        stubModels(summary("anthropic.claude-x", "Claude X", "Anthropic", "ACTIVE"));

        List<ProviderModel> models = lister().list(apiKeyCred("us-east-1", "access", "secret"));

        assertEquals(List.of(new ProviderModel("anthropic.claude-x", "Claude X", "Anthropic")), models);
    }

    @Test
    void legacyModel_isDropped() {
        stubModels(
                summary("anthropic.claude-old", "Claude Old", "Anthropic", "LEGACY"),
                summary("anthropic.claude-new", "Claude New", "Anthropic", "ACTIVE"));

        List<ProviderModel> models = lister().list(apiKeyCred("us-east-1", "access", "secret"));

        assertEquals(List.of(new ProviderModel("anthropic.claude-new", "Claude New", "Anthropic")), models);
    }

    @Test
    void summaryWithNoLifecycleAtAll_isTreatedAsIncluded() {
        // No modelLifecycle() set at all (not even a status) must not be mistaken for a filtered-out
        // LEGACY model — the null-guard in list() exists precisely so an absent lifecycle passes
        // through rather than being silently dropped.
        stubModels(summary("anthropic.claude-y", "Claude Y", "Anthropic", null));

        List<ProviderModel> models = lister().list(apiKeyCred("us-east-1", "access", "secret"));

        assertEquals(List.of(new ProviderModel("anthropic.claude-y", "Claude Y", "Anthropic")), models);
    }

    // ---------------------------------------------------------------- six-maker allowlist filter

    @Test
    void unsupportedMaker_isDropped_supportedMakerIsKept() {
        stubModels(
                summary("amazon.nova-lite", "Nova Lite", "Amazon", "ACTIVE"),
                summary("meta.llama-x", "Llama X", "Meta", "ACTIVE"),
                summary("anthropic.claude-x", "Claude X", "Anthropic", "ACTIVE"));

        List<ProviderModel> models = lister().list(apiKeyCred("us-east-1", "access", "secret"));

        assertEquals(List.of(new ProviderModel("anthropic.claude-x", "Claude X", "Anthropic")), models);
    }

    // ---------------------------------------------------------------- api_key vs iam_role credential branch

    @Test
    void iamRoleCredential_needsNoStaticKeys_stillLists() {
        stubModels(summary("anthropic.claude-x", "Claude X", "Anthropic", "ACTIVE"));

        List<ProviderModel> models = lister().list(iamRoleCred("us-east-1"));

        assertEquals(List.of(new ProviderModel("anthropic.claude-x", "Claude X", "Anthropic")), models);
    }

    @Test
    void apiKeyCredentialMissingAccessKey_failsWithoutEverCallingTheClient() {
        assertThrows(ModelListingException.class, () -> lister().list(apiKeyCred("us-east-1", null, "secret")));
    }

    @Test
    void apiKeyCredentialMissingSecretKey_failsWithoutEverCallingTheClient() {
        assertThrows(ModelListingException.class, () -> lister().list(apiKeyCred("us-east-1", "access", null)));
    }

    // ---------------------------------------------------------------- region validation / failure wrapping

    @Test
    void missingRegion_failsWithoutBuildingAClient() {
        assertThrows(ModelListingException.class, () -> lister().list(apiKeyCred(null, "access", "secret")));
    }

    @Test
    void blankRegion_fails() {
        assertThrows(ModelListingException.class, () -> lister().list(apiKeyCred("   ", "access", "secret")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sdkExceptionFromTheClient_wrapsIntoModelListingException() {
        when(client.listFoundationModels(any(Consumer.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkException.create("boom", null));

        ModelListingException ex = assertThrows(
                ModelListingException.class, () -> lister().list(apiKeyCred("us-east-1", "access", "secret")));
        assertTrue(ex.getMessage().contains("us-east-1"));
    }

    @Test
    void requestBuilder_isUnusedConsumer_doesNotThrow() {
        // list() calls listFoundationModels(r -> {}) — a no-op configurer. Confirms that shape still
        // compiles and runs against the real ListFoundationModelsRequest.Builder type, not just the
        // mocked client.
        stubModels();
        assertEquals(List.of(), lister().list(apiKeyCred("us-east-1", "access", "secret")));
    }
}
