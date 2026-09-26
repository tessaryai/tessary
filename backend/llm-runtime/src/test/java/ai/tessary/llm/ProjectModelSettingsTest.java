// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The validation gate and the automatic-selection semantics, without a database. */
class ProjectModelSettingsTest {

    private static final String PID = "p1";
    private static final String ORG = "org1";
    private static final String HAIKU = "anthropic.claude-haiku-4-5";
    private static final String TERRA = "openai.gpt-5.6-terra";
    private static final String SONNET_5 = "anthropic.claude-sonnet-5";

    private ProjectModelSettingRepository repo;
    private ProviderCredentialRepository credentials;
    private ProjectModelSettings settings;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectModelSettingRepository.class);
        when(repo.findByProject(anyString())).thenReturn(List.of());
        credentials = mock(ProviderCredentialRepository.class);
        ProjectOrgResolver orgs = mock(ProjectOrgResolver.class);
        when(orgs.orgIdFor(PID)).thenReturn(ORG);
        // Every provider is configured by default; tests that care narrow it with configured(...).
        configured(ModelProvider.values());
        settings = new ProjectModelSettings(repo, credentials, orgs, mock(ModelCatalogFetchService.class));
    }

    /** Give the org a credential for exactly these providers, and for none of the others. */
    private void configured(ModelProvider... providers) {
        List<ProviderCredential> rows = new ArrayList<>();
        for (ModelProvider p : providers) {
            ProviderCredential row = credential(p);
            rows.add(row);
            when(credentials.findByOrgAndProvider(ORG, p)).thenReturn(Optional.of(row));
        }
        for (ModelProvider p : ModelProvider.values()) {
            if (!Arrays.asList(providers).contains(p)) {
                when(credentials.findByOrgAndProvider(ORG, p)).thenReturn(Optional.empty());
            }
        }
        when(credentials.findByOrg(ORG)).thenReturn(rows);
    }

    private static ProviderCredential credential(ModelProvider provider) {
        return new ProviderCredential(
                "c-" + provider,
                ORG,
                null,
                provider,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "t0",
                "t0");
    }

    private static ProjectModelSetting row(ModelLane lane, String model, ServiceTier tier) {
        return new ProjectModelSetting(PID, lane, model, tier, null, "t0", "t0");
    }

    @Test
    void providerIsChosenBeforeModel() {
        // Bedrock's flagship would outrank on a flat by-model ranking, but provider order decides: Bedrock is
        // configured, so RCA runs Bedrock's default.
        configured(ModelProvider.BEDROCK, ModelProvider.GEMINI);
        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(SONNET_5, rca.modelKey());
        assertTrue(rca.automatic());
        assertEquals(ServiceTier.STANDARD, rca.serviceTier());
        var triage = settings.resolve(PID, ModelLane.TRIAGE).orElseThrow();
        assertEquals(SONNET_5, triage.modelKey(), "TRIAGE is exactly RCA's list, so it lands on the same default");
        assertTrue(triage.automatic());

        configured(ModelProvider.GEMINI);
        assertEquals(
                "GEMINI:gemini-3.1-pro-preview",
                settings.resolve(PID, ModelLane.RCA).orElseThrow().modelKey(),
                "with Bedrock gone, the next provider in the order supplies its own default");
    }

    @Test
    void triageAcceptsTheSameFrontierModelRcaDoes() {
        // Sonnet 5 is the default on both lanes, and an explicit PUT naming it succeeds on either.
        settings.set(PID, ORG, ModelLane.TRIAGE, SONNET_5);
        verify(repo).upsert(PID, ModelLane.TRIAGE, SONNET_5, ServiceTier.STANDARD, null);
        settings.set(PID, ORG, ModelLane.RCA, SONNET_5);
        verify(repo).upsert(PID, ModelLane.RCA, SONNET_5, ServiceTier.STANDARD, null);
    }

    @Test
    void everyLaneReachesEveryProviderTheSandboxCanRun() {
        // Whichever single key an org holds, both lanes resolve. allOf, not a hand-listed set, so a new ModelProvider
        // fails here until it has a launcher mode and a place on both lanes. TYPESAFE serves decision models only.
        Set<ModelProvider> reachable = EnumSet.complementOf(EnumSet.of(ModelProvider.TYPESAFE));
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            Set<ModelProvider> covered = LanePriority.of(lane).stream()
                    .map(LanePriority.ProviderOption::provider)
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ModelProvider.class)));
            assertEquals(reachable, covered, "lane " + lane);
        }
    }

    @Test
    void theFrustrationLaneOffersOnlyJevAndTypeSafeLeadsIt() {
        assertEquals(
                List.of("TYPESAFE:jev-latest", "OPENROUTER:typesafe/jev-latest"),
                LanePriority.modelKeys(ModelLane.FRUSTRATION));
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            assertTrue(
                    LanePriority.forProvider(lane, ModelProvider.TYPESAFE).isEmpty(),
                    "TypeSafe serves no chat model, so " + lane + " never offers it");
        }

        configured(ModelProvider.OPENROUTER, ModelProvider.TYPESAFE);
        var both = settings.resolve(PID, ModelLane.FRUSTRATION).orElseThrow();
        assertEquals("TYPESAFE:jev-latest", both.modelKey());
        assertTrue(both.automatic());
        assertEquals(ServiceTier.STANDARD, both.serviceTier());

        configured(ModelProvider.OPENROUTER);
        assertEquals(
                new ProjectModelSettings.ResolvedDecisionModel(ModelProvider.OPENROUTER, "typesafe/jev-latest"),
                settings.resolveDecisionModel(PID, ModelLane.FRUSTRATION).orElseThrow());

        configured(ModelProvider.BEDROCK);
        assertTrue(
                settings.resolve(PID, ModelLane.FRUSTRATION).isEmpty(),
                "a chat-only key runs no decision lane, whatever else it serves");
    }

    @Test
    void aPinnedOpenRouterBeatsTypeSafeOnTheFrustrationLane() {
        when(repo.findByProject(PID))
                .thenReturn(
                        List.of(row(ModelLane.FRUSTRATION, "OPENROUTER:typesafe/jev-latest", ServiceTier.STANDARD)));

        assertEquals(
                new ProjectModelSettings.ResolvedDecisionModel(ModelProvider.OPENROUTER, "typesafe/jev-latest"),
                settings.resolveDecisionModel(PID, ModelLane.FRUSTRATION).orElseThrow());
    }

    @Test
    void aDecisionModelIsRefusedOnTheAgentLanes() {
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            for (String jev : List.of("TYPESAFE:jev-latest", "OPENROUTER:typesafe/jev-latest")) {
                TessaryException ex = assertThrows(TessaryException.class, () -> settings.set(PID, ORG, lane, jev));
                assertEquals(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, ex.error(), lane + " " + jev);
            }
        }
    }

    @Test
    void aChatModelIsRefusedOnTheFrustrationLane() {
        for (String chat : List.of(SONNET_5, "OPENROUTER:openai/gpt-6-sol", "GROK:grok-4.6")) {
            TessaryException ex =
                    assertThrows(TessaryException.class, () -> settings.set(PID, ORG, ModelLane.FRUSTRATION, chat));
            assertEquals(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, ex.error(), chat);
        }
    }

    @Test
    void aDecisionModelSavesOnTheFrustrationLaneWithNoTierOrEffort() {
        settings.set(PID, ORG, ModelLane.FRUSTRATION, "OPENROUTER:typesafe/jev-latest");
        verify(repo).upsert(PID, ModelLane.FRUSTRATION, "OPENROUTER:typesafe/jev-latest", ServiceTier.STANDARD, null);
    }

    @Test
    void anExplicitChoiceBeatsTheOrder() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.STANDARD)));

        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(HAIKU, rca.modelKey());
        assertFalse(rca.automatic());
        // TRIAGE resolves through its own provider order, not RCA's pinned Haiku.
        var triage = settings.resolve(PID, ModelLane.TRIAGE).orElseThrow();
        assertEquals(SONNET_5, triage.modelKey());
        assertTrue(triage.automatic());
    }

    @Test
    void anExplicitChoiceWhoseProviderIsGoneFallsBackToTheOrder() {
        // The credential was deleted after the choice: the lane keeps running and the row survives for when the key
        // returns.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, "GROK:grok-4.6", ServiceTier.STANDARD)));
        configured(ModelProvider.BEDROCK);

        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(SONNET_5, rca.modelKey());
        assertTrue(rca.automatic());
        assertEquals(1, settings.list(PID).size(), "the row is still reported to the settings UI");
    }

    /**
     * A bare name, a key naming no provider, and a missing catalog entry are unknown. The Nova key is dotted like a
     * Bedrock id but resolves nowhere, so it fails as unknown, not non-agentic.
     */
    @ParameterizedTest
    @CsvSource({
        "RCA, gpt-5.5",
        "RCA, amazon.nova-2-lite",
        "TRIAGE, amazon.nova-2-lite",
        "RCA, NOPE:some-model",
        "RCA, GEMINI:no-such-model"
    })
    void rejectsAModelThatIsNotOnePlatformModel(ModelLane lane, String key) {
        TessaryException ex = assertThrows(TessaryException.class, () -> settings.set(PID, ORG, lane, key));
        assertEquals(ModelConfigError.UNKNOWN_PLATFORM_MODEL, ex.error());
    }

    @Test
    void refusesToSaveAModelWhoseProviderTheOrgHasNoKeyFor() {
        configured(ModelProvider.BEDROCK);
        TessaryException ex =
                assertThrows(TessaryException.class, () -> settings.set(PID, ORG, ModelLane.RCA, "GROK:grok-4.6"));
        assertEquals(ModelConfigError.PROVIDER_NOT_CONFIGURED, ex.error());
    }

    @Test
    void aStoredPairThatIsNoLongerSupportedFallsBackToTheOrder() {
        // A retired tier would otherwise fail every call in the lane.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.FLEX)));

        assertTrue(
                settings.resolve(PID, ModelLane.RCA).orElseThrow().automatic(),
                "unsupported pair must not reach Bedrock");
        assertEquals(1, settings.list(PID).size(), "but the row is still reported to the settings UI");
    }

    @Test
    void readsAreCachedAndInvalidatedOnWrite() {
        // Cached on the LLM call path, but a change applies on the next call, not after a TTL.
        settings.resolve(PID, ModelLane.RCA);
        settings.resolve(PID, ModelLane.RCA);
        verify(repo, times(1)).findByProject(PID);

        settings.set(PID, ORG, ModelLane.RCA, HAIKU);
        settings.resolve(PID, ModelLane.RCA);
        verify(repo, times(2)).findByProject(PID);
    }

    @Test
    void clearingALaneDeletesTheRowRatherThanWritingADefault() {
        // No default is written: the lane returns to its priority order.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.STANDARD)));
        settings.clear(PID, ModelLane.RCA);
        verify(repo).delete(PID, ModelLane.RCA);

        when(repo.findByProject(PID)).thenReturn(List.of());
        assertTrue(settings.resolve(PID, ModelLane.RCA).orElseThrow().automatic());
    }

    /**
     * A stored row the validator would refuse today must not pin the lane: a chat model cannot drive a sandbox or
     * answer a decision, and a key naming no provider resolves nowhere.
     */
    @ParameterizedTest
    @CsvSource({
        "RCA, OPENAI:gpt-5.5, anthropic.claude-sonnet-5",
        "FRUSTRATION, OPENAI:gpt-5.5, TYPESAFE:jev-latest",
        "RCA, NOPE:some-model, anthropic.claude-sonnet-5",
        // A row from before Nova was removed must not pin a sandbox to a model that resolves nowhere.
        "RCA, amazon.nova-2-lite, anthropic.claude-sonnet-5"
    })
    void aStoredRowTheLaneCannotRunFallsBackToTheOrder(ModelLane lane, String stored, String fallback) {
        when(repo.findByProject(PID)).thenReturn(List.of(row(lane, stored, ServiceTier.STANDARD)));

        var resolved = settings.resolve(PID, lane).orElseThrow();

        assertEquals(fallback, resolved.modelKey());
        assertTrue(resolved.automatic());
    }

    @Test
    void aNonBedrockAgenticCatalogModelOnTheSandboxLane_isAccepted() {
        settings.set(PID, ORG, ModelLane.RCA, "GEMINI:gemini-3.1-pro-preview");
        verify(repo).upsert(PID, ModelLane.RCA, "GEMINI:gemini-3.1-pro-preview", ServiceTier.STANDARD, null);
    }

    @Test
    void aCatalogEntryThatIsNotAgentic_isRejectedOnTheSandboxLane() {
        // A real catalog entry, but not agentic, so not offered on a sandbox lane.
        TessaryException ex =
                assertThrows(TessaryException.class, () -> settings.set(PID, ORG, ModelLane.TRIAGE, "OPENAI:gpt-5.5"));
        assertEquals(ModelConfigError.MODEL_NOT_AGENTIC, ex.error());
    }

    @Test
    void resolveAgenticModel_forABedrockRow_returnsBedrockAndTheFullInferenceProfileId() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.BEDROCK, resolved.provider());
        assertEquals("global.anthropic.claude-haiku-4-5-20251001-v1:0", resolved.modelId());
        assertEquals(resolved.modelId(), resolved.pricingId(), "Bedrock's inferenceProfileId is already priceable");
    }

    @Test
    void resolveAgenticModel_forAMantleRow_returnsBedrockMantle() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, TERRA, ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.BEDROCK_MANTLE, resolved.provider());
        assertEquals(resolved.modelId(), resolved.pricingId(), "mantle's inferenceProfileId is already priceable");
    }

    @Test
    void resolveAgenticModel_forACatalogRowOnARoutePrefixedProvider_pricingIdCarriesThePrefix() {
        // Decision 19: book keys carry a route prefix. modelId stays bare, pricingId carries it, or these price as
        // unknown.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, "GROK:grok-4.6", ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.GROK, resolved.provider());
        assertEquals("grok-4.6", resolved.modelId());
        assertEquals("xai/grok-4.6", resolved.pricingId());
    }

    @Test
    void aCustomProviderCatalogKey_acceptsAnyModelNameSuffix() {
        // CUSTOM has no catalog: any non-blank suffix round-trips as the user's name.
        settings.set(PID, ORG, ModelLane.RCA, "CUSTOM:my-self-hosted-model");
        verify(repo).upsert(PID, ModelLane.RCA, "CUSTOM:my-self-hosted-model", ServiceTier.STANDARD, null);

        when(repo.findByProject(PID))
                .thenReturn(List.of(row(ModelLane.RCA, "CUSTOM:my-self-hosted-model", ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.CUSTOM, resolved.provider());
        assertEquals("my-self-hosted-model", resolved.modelId());
        assertEquals("my-self-hosted-model", resolved.pricingId(), "no book carries a rate for a custom endpoint");
    }
}
