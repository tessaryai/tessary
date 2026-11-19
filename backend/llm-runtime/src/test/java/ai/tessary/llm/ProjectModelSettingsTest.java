// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.llmspi.ModelLane;
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
    private static final String HAIKU = "anthropic.claude-haiku-5-5";
    private static final String MANTLE_SOL = "openai.gpt-6.1-sol";
    private static final String SONNET = "anthropic.claude-sonnet-5-5";
    private static final String PLATFORM_SONNET = "PLATFORM:claude-sonnet-5-5";

    private ProjectModelSettingRepository repo;
    private ProviderCredentialRepository credentials;
    private ProjectModelSettings settings;

    /** Whether the fake supplier below offers {@link ModelProvider#PLATFORM} to the org. */
    private boolean platformOffered;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectModelSettingRepository.class);
        when(repo.findByProject(anyString())).thenReturn(List.of());
        credentials = mock(ProviderCredentialRepository.class);
        ProjectOrgResolver orgs = mock(ProjectOrgResolver.class);
        when(orgs.orgIdFor(PID)).thenReturn(ORG);
        // Every provider is configured by default; tests that care narrow it with configured(...).
        configured(ModelProvider.values());
        PlatformProviderSupplier supplier = new PlatformProviderSupplier() {
            @Override
            public boolean available(String orgId) {
                return platformOffered && ORG.equals(orgId);
            }

            @Override
            public Optional<SuppliedProvider> describe(String orgId) {
                return Optional.empty();
            }
        };
        settings = new ProjectModelSettings(repo, credentials, orgs, mock(ModelCatalogFetchService.class), supplier);
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

    private static ProjectModelSetting row(ModelLane lane, String model) {
        return new ProjectModelSetting(PID, lane, model, "t0", "t0");
    }

    @Test
    void providerIsChosenBeforeModel() {
        // Bedrock's flagship would outrank on a flat by-model ranking, but provider order decides: Bedrock is
        // configured, so RCA runs Bedrock's default.
        configured(ModelProvider.BEDROCK, ModelProvider.GEMINI);
        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(SONNET, rca.modelKey());
        assertTrue(rca.automatic());
        var triage = settings.resolve(PID, ModelLane.TRIAGE).orElseThrow();
        assertEquals(SONNET, triage.modelKey(), "TRIAGE is exactly RCA's list, so it lands on the same default");
        assertTrue(triage.automatic());
        var agentRun = settings.resolve(PID, ModelLane.AGENT_RUN).orElseThrow();
        assertEquals(SONNET, agentRun.modelKey(), "AGENT_RUN shares RCA's list, so it lands on the same default");
        assertTrue(agentRun.automatic());

        configured(ModelProvider.GEMINI);
        assertEquals(
                "GEMINI:gemini-4-hydrogen",
                settings.resolve(PID, ModelLane.RCA).orElseThrow().modelKey(),
                "with Bedrock gone, the next provider in the order supplies its own default");
    }

    @Test
    void anOrgsOwnKeyOutranksThePlatformProvider() {
        configured(ModelProvider.ANTHROPIC);
        platformOffered = true;
        assertEquals(
                "ANTHROPIC:claude-sonnet-5-5",
                settings.resolve(PID, ModelLane.RCA).orElseThrow().modelKey(),
                "the platform provider is last, so an unpinned lane moves to the org's own key");
    }

    @Test
    void aLaneCanBePinnedToThePlatformProviderWhenItIsOffered() {
        configured();
        platformOffered = true;
        settings.set(PID, ORG, ModelLane.RCA, PLATFORM_SONNET);
        verify(repo).upsert(PID, ModelLane.RCA, PLATFORM_SONNET);
    }

    @Test
    void thePlatformProviderIsNeverConfiguredWithoutASupplierEvenWithAStoredRow() {
        // A PLATFORM credential row cannot be written through the API, but one left in the table must
        // not make the provider count as configured on a build that offers it to no one.
        configured(ModelProvider.PLATFORM);
        assertTrue(settings.resolve(PID, ModelLane.RCA).isEmpty());
        TessaryException refused =
                assertThrows(TessaryException.class, () -> settings.set(PID, ORG, ModelLane.RCA, PLATFORM_SONNET));
        assertEquals(ModelConfigError.PROVIDER_NOT_CONFIGURED, refused.error());
    }

    @Test
    void everyLaneReachesEveryProviderTheSandboxCanRun() {
        // Whichever single key an org holds, both lanes resolve. allOf, not a hand-listed set, so a new ModelProvider
        // fails here until it has a launcher mode and a place on both lanes. TYPESAFE serves decision models only.
        Set<ModelProvider> reachable = EnumSet.complementOf(EnumSet.of(ModelProvider.TYPESAFE));
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE, ModelLane.AGENT_RUN)) {
            Set<ModelProvider> covered = LanePriority.of(lane).stream()
                    .map(LanePriority.ProviderOption::provider)
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ModelProvider.class)));
            assertEquals(reachable, covered, "lane " + lane);
        }
    }

    @Test
    void theFrustrationLaneOffersOnlyJevAndTypeSafeLeadsIt() {
        assertEquals(
                List.of("TYPESAFE:jev-latest", "OPENROUTER:~typesafe/jev-latest"),
                LanePriority.modelKeys(ModelLane.FRUSTRATION));
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE, ModelLane.AGENT_RUN)) {
            assertTrue(
                    LanePriority.forProvider(lane, ModelProvider.TYPESAFE).isEmpty(),
                    "TypeSafe serves no chat model, so " + lane + " never offers it");
        }

        configured(ModelProvider.OPENROUTER, ModelProvider.TYPESAFE);
        var both = settings.resolve(PID, ModelLane.FRUSTRATION).orElseThrow();
        assertEquals("TYPESAFE:jev-latest", both.modelKey());
        assertTrue(both.automatic());

        configured(ModelProvider.OPENROUTER);
        assertEquals(
                new ProjectModelSettings.ResolvedDecisionModel(ModelProvider.OPENROUTER, "~typesafe/jev-latest"),
                settings.resolveDecisionModel(PID, ModelLane.FRUSTRATION).orElseThrow());

        configured(ModelProvider.BEDROCK);
        assertTrue(
                settings.resolve(PID, ModelLane.FRUSTRATION).isEmpty(),
                "a chat-only key runs no decision lane, whatever else it serves");
    }

    @Test
    void anOrgsOwnJevKeyOutranksThePlatformProviderOnTheFrustrationLane() {
        configured(ModelProvider.OPENROUTER);
        platformOffered = true;

        assertEquals(
                new ProjectModelSettings.ResolvedDecisionModel(ModelProvider.OPENROUTER, "~typesafe/jev-latest"),
                settings.resolveDecisionModel(PID, ModelLane.FRUSTRATION).orElseThrow(),
                "the platform provider is last, so an unpinned lane runs on the org's own key");
    }

    @Test
    void aPinnedOpenRouterBeatsTypeSafeOnTheFrustrationLane() {
        when(repo.findByProject(PID))
                .thenReturn(List.of(row(ModelLane.FRUSTRATION, "OPENROUTER:~typesafe/jev-latest")));

        assertEquals(
                new ProjectModelSettings.ResolvedDecisionModel(ModelProvider.OPENROUTER, "~typesafe/jev-latest"),
                settings.resolveDecisionModel(PID, ModelLane.FRUSTRATION).orElseThrow());
    }

    @Test
    void aDecisionModelIsRefusedOnTheAgentLanes() {
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE, ModelLane.AGENT_RUN)) {
            for (String jev : List.of("TYPESAFE:jev-latest", "OPENROUTER:~typesafe/jev-latest")) {
                TessaryException ex = assertThrows(TessaryException.class, () -> settings.set(PID, ORG, lane, jev));
                assertEquals(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, ex.error(), lane + " " + jev);
            }
        }
    }

    @Test
    void aChatModelIsRefusedOnTheFrustrationLane() {
        for (String chat : List.of(SONNET, "OPENROUTER:openai/gpt-6.1-sol", "GROK:grok-4.7")) {
            TessaryException ex =
                    assertThrows(TessaryException.class, () -> settings.set(PID, ORG, ModelLane.FRUSTRATION, chat));
            assertEquals(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, ex.error(), chat);
        }
    }

    @Test
    void aDecisionModelSavesOnTheFrustrationLaneWithNoTierOrEffort() {
        settings.set(PID, ORG, ModelLane.FRUSTRATION, "OPENROUTER:~typesafe/jev-latest");
        verify(repo).upsert(PID, ModelLane.FRUSTRATION, "OPENROUTER:~typesafe/jev-latest");
    }

    @Test
    void anExplicitChoiceWhoseProviderIsGoneFallsBackToTheOrder() {
        // The credential was deleted after the choice: the lane keeps running and the row survives for when the key
        // returns.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, "GROK:grok-4.7")));
        configured(ModelProvider.BEDROCK);

        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(SONNET, rca.modelKey());
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
        "AGENT_RUN, amazon.nova-2-lite",
        "RCA, NOPE:some-model",
        "RCA, GEMINI:no-such-model"
    })
    void rejectsAModelThatIsNotOnePlatformModel(ModelLane lane, String key) {
        TessaryException ex = assertThrows(TessaryException.class, () -> settings.set(PID, ORG, lane, key));
        assertEquals(ModelConfigError.UNKNOWN_PLATFORM_MODEL, ex.error());
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
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU)));
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
        "RCA, OPENROUTER:anthropic/claude-sonnet-5.5, anthropic.claude-sonnet-5-5",
        "FRUSTRATION, OPENROUTER:anthropic/claude-sonnet-5.5, TYPESAFE:jev-latest",
        "RCA, NOPE:some-model, anthropic.claude-sonnet-5-5",
        // A row from before Nova was removed must not pin a sandbox to a model that resolves nowhere.
        "RCA, amazon.nova-2-lite, anthropic.claude-sonnet-5-5",
        // Frontier is not agentic: the fix lane's flag must not let a model onto the other sandbox lanes.
        "RCA, OPENROUTER:anthropic/claude-opus-5.5, anthropic.claude-sonnet-5-5",
        // Agentic is not frontier, and Custom is never frontier: neither may pin the fix lane.
        "FIX, anthropic.claude-sonnet-5-5, anthropic.claude-opus-5-5",
        "FIX, CUSTOM:my-self-hosted-model, anthropic.claude-opus-5-5"
    })
    void aStoredRowTheLaneCannotRunFallsBackToTheOrder(ModelLane lane, String stored, String fallback) {
        when(repo.findByProject(PID)).thenReturn(List.of(row(lane, stored)));

        var resolved = settings.resolve(PID, lane).orElseThrow();

        assertEquals(fallback, resolved.modelKey());
        assertTrue(resolved.automatic());
    }

    @Test
    void aCatalogEntryThatIsNotAgentic_isRejectedOnTheSandboxLane() {
        // A real catalog entry, but not agentic, so not offered on a sandbox lane.
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> settings.set(PID, ORG, ModelLane.TRIAGE, "OPENROUTER:anthropic/claude-sonnet-5.5"));
        assertEquals(ModelConfigError.MODEL_NOT_AGENTIC, ex.error());
    }

    @Test
    void resolveAgenticModel_forABedrockRow_returnsBedrockAndTheFullInferenceProfileId() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.BEDROCK, resolved.provider());
        assertEquals("global.anthropic.claude-haiku-5-5", resolved.modelId());
        assertEquals(
                "amazon-bedrock/global.anthropic.claude-haiku-5-5",
                resolved.modelsDevId(),
                "models.dev prices a Bedrock model under its region-scoped inference-profile id");
    }

    @Test
    void resolveAgenticModel_forAMantleRow_returnsBedrockMantle() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, MANTLE_SOL)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.BEDROCK_MANTLE, resolved.provider());
        assertEquals(
                "amazon-bedrock/openai.gpt-6.1-sol",
                resolved.modelsDevId(),
                "LiteLLM's bedrock_mantle/ route prefix is not part of the models.dev id");
    }

    @Test
    void resolveAgenticModel_forACatalogRow_modelsDevIdNamesTheModelsDevProvider() {
        // modelId stays the name a person chose; the models.dev id carries models.dev's provider id, or the run's
        // rates are never found and it books unpriced.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, "GROK:grok-4.7")));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.GROK, resolved.provider());
        assertEquals("grok-4.7", resolved.modelId());
        assertEquals("xai/grok-4.7", resolved.modelsDevId());
    }

    /**
     * The bug: the fix lane applies the agentic check, so it refuses OpenRouter's Opus (frontier, not agentic) and
     * accepts Sonnet, Custom or the platform provider, none of which the owner chose to write pull requests.
     */
    @Test
    void theFixLaneSavesOnlyFrontierModels() {
        platformOffered = true;
        for (String frontier : List.of(
                "anthropic.claude-opus-5-5",
                "ANTHROPIC:claude-opus-5-5",
                "OPENROUTER:anthropic/claude-opus-5.5",
                "OPENAI:gpt-6.1-sol",
                "OPENROUTER:openai/gpt-6.1-sol")) {
            settings.set(PID, ORG, ModelLane.FIX, frontier);
            verify(repo).upsert(PID, ModelLane.FIX, frontier);
        }
        for (String other :
                List.of(SONNET, MANTLE_SOL, "GROK:grok-4.7", "CUSTOM:my-self-hosted-model", PLATFORM_SONNET)) {
            TessaryException ex =
                    assertThrows(TessaryException.class, () -> settings.set(PID, ORG, ModelLane.FIX, other));
            assertEquals(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, ex.error(), other);
        }
    }

    /**
     * The bug: reading a stored row applies the agentic check, so a saved OpenRouter Opus pin (frontier, not agentic)
     * reads as unset and the lane silently runs another model.
     */
    @Test
    void aStoredFrontierRowThatIsNotAgenticPinsTheFixLane() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.FIX, "OPENROUTER:anthropic/claude-opus-5.5")));

        assertEquals(
                new ProjectModelSettings.LaneSelection("OPENROUTER:anthropic/claude-opus-5.5", false),
                settings.resolve(PID, ModelLane.FIX).orElseThrow());
    }

    /** The bug: the frontier flag leaks to the other sandbox lanes, which only run agentic models. */
    @Test
    void aFrontierModelThatIsNotAgenticIsStillRefusedOnTheOtherSandboxLanes() {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> settings.set(PID, ORG, ModelLane.RCA, "OPENROUTER:anthropic/claude-opus-5.5"));
        assertEquals(ModelConfigError.MODEL_NOT_AGENTIC, ex.error());
    }

    /** The bug: an unpinned fix lane picks a cheaper model, or a model the org holds no key for. */
    @Test
    void anUnpinnedFixLaneTakesTheFrontierModelOfTheFirstProviderTheOrgHolds() {
        configured(ModelProvider.GEMINI, ModelProvider.BEDROCK);
        var bedrock = settings.resolve(PID, ModelLane.FIX).orElseThrow();
        assertEquals("anthropic.claude-opus-5-5", bedrock.modelKey());
        assertTrue(bedrock.automatic());

        configured(ModelProvider.OPENROUTER);
        assertEquals(
                new ProjectModelSettings.ResolvedAgenticModel(
                        ModelProvider.OPENROUTER, "anthropic/claude-opus-5.5", "openrouter/anthropic/claude-opus-5.5"),
                settings.resolveAgenticModel(PID, ModelLane.FIX).orElseThrow());
    }

    /**
     * The bug: an org with no frontier provider gets a fix model anyway (Custom, the platform provider, or another
     * lane's model), so the fix step runs on a model the owner never accepted for code changes.
     */
    @Test
    void theFixLaneHasNoModelWhenTheOrgHoldsNoFrontierProvider() {
        configured(ModelProvider.GEMINI, ModelProvider.BEDROCK_MANTLE, ModelProvider.CUSTOM);
        platformOffered = true;

        assertTrue(settings.resolve(PID, ModelLane.FIX).isEmpty());
        assertTrue(settings.resolve(PID, ModelLane.RCA).isPresent(), "the other sandbox lanes still resolve");
    }

    @Test
    void aCustomProviderCatalogKey_acceptsAnyModelNameSuffix() {
        // CUSTOM has no catalog: any non-blank suffix round-trips as the user's name.
        settings.set(PID, ORG, ModelLane.RCA, "CUSTOM:my-self-hosted-model");
        verify(repo).upsert(PID, ModelLane.RCA, "CUSTOM:my-self-hosted-model");

        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, "CUSTOM:my-self-hosted-model")));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.CUSTOM, resolved.provider());
        assertEquals("my-self-hosted-model", resolved.modelId());
        assertNull(resolved.modelsDevId(), "models.dev cannot know a customer's own endpoint");
    }
}
