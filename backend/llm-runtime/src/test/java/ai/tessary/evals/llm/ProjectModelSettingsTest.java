// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.evals.llm.catalog.ModelCatalogFetchService;
import ai.tessary.evals.llmspi.ModelLane;
import ai.tessary.evals.llmspi.ServiceTier;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.ModelConfigError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The validation gate and the automatic-selection semantics, without a database. */
class ProjectModelSettingsTest {

    private static final String PID = "p1";
    private static final String ORG = "org1";
    private static final String HAIKU = "anthropic.claude-haiku-4-5";
    /** Removed by #939 D6 (Amazon is not one of the six supported makers) — kept as a string
     *  constant purely so the "unknown model key" tests below still exercise a Bedrock-SHAPED
     *  (dotted) key that resolves nowhere, the same failure class a stale pre-D6 row would hit. */
    private static final String NOVA = "amazon.nova-2-lite";

    private static final String TERRA = "openai.gpt-5.6-terra";
    private static final String LUNA = "openai.gpt-5.6-luna";
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
        // Every model in either lane's priority order is reachable by default, so a test that cares
        // about validation or caching does not also have to say which providers the org has. The
        // tests that DO care call configured(...) to narrow it.
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
    void withNoProviderConfiguredALaneRunsNothing() {
        // The state a new org is in, and the reason the settings page asks for a provider before it
        // asks for a model: there is no default to fall back to, because a default would name a
        // provider this org has no key for.
        configured();
        assertTrue(settings.resolve(PID, ModelLane.RCA).isEmpty());
        assertTrue(settings.resolve(PID, ModelLane.TRIAGE).isEmpty());
    }

    @Test
    void theFirstProviderConfiguredDecidesEveryUnsetLane() {
        configured(ModelProvider.BEDROCK);

        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(SONNET_5, rca.modelKey(), "Bedrock's default model on RCA");
        assertTrue(rca.automatic());
        assertEquals(ServiceTier.STANDARD, rca.serviceTier());

        var triage = settings.resolve(PID, ModelLane.TRIAGE).orElseThrow();
        assertEquals(HAIKU, triage.modelKey(), "Bedrock's default on TRIAGE is the one model under the ceiling");
        assertTrue(triage.automatic());
    }

    @Test
    void providerIsChosenBeforeModel() {
        // The org holds a key for a provider that is NOT first in RCA's order but whose flagship would
        // outrank the configured provider's smaller model on any flat by-model ranking. Provider order
        // decides: Bedrock is configured, so RCA runs Bedrock's own default rather than reaching past
        // it. Both providers here are in the list, which is what makes the ordering the thing under
        // test rather than availability.
        configured(ModelProvider.BEDROCK, ModelProvider.GEMINI);
        assertEquals(
                SONNET_5, settings.resolve(PID, ModelLane.RCA).orElseThrow().modelKey());

        configured(ModelProvider.GEMINI);
        assertEquals(
                "GEMINI:gemini-3.1-pro-preview",
                settings.resolve(PID, ModelLane.RCA).orElseThrow().modelKey(),
                "with Bedrock gone, the next provider in the order supplies its own default");
    }

    @Test
    void everyTriageModelIsUnderThePriceCeiling() {
        // The ceiling is $1 in / $5 out per MTok, curated into TRIAGE's list against the vendored
        // LiteLLM book. Pinning the list itself is what catches a model added to the lane without
        // anyone checking its rate — the rate lookup lives in the price book, not here.
        assertEquals(
                List.of(
                        "GLM:glm-5.3-flash",
                        "OPENAI:gpt-5.6-luna",
                        LUNA,
                        "GEMINI:gemini-3.7-flash",
                        "MOONSHOT:kimi-k2.6",
                        "GROK:grok-code-fast-1",
                        "ANTHROPIC:claude-haiku-4-5",
                        HAIKU,
                        "OPENROUTER:openai/gpt-5.6-luna",
                        "CUSTOM:custom-model"),
                LanePriority.modelKeys(ModelLane.TRIAGE));
    }

    @Test
    void triageRefusesAFrontierModelEvenThoughItsGroupPermitsIt() {
        // Sonnet 5 is agentic, offered for AGENT_VM, and valid at Standard — every check that existed
        // before the ceiling passes it. The lane-scoped offer list is the only thing that stops it
        // landing on the lane that runs unattended once per cause.
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> settings.set(PID, ORG, ModelLane.TRIAGE, SONNET_5, ServiceTier.STANDARD, null));
        assertEquals(ModelConfigError.MODEL_NOT_OFFERED_FOR_LANE, ex.error());
        // ...and the same model is perfectly valid on RCA, which has no ceiling.
        settings.set(PID, ORG, ModelLane.RCA, SONNET_5, ServiceTier.STANDARD, null);
        verify(repo).upsert(PID, ModelLane.RCA, SONNET_5, ServiceTier.STANDARD, null);
    }

    @Test
    void aProviderOffersEveryModelWeSupportForTheLaneNotOnlyItsDefault() {
        // "One default per provider, every model we support" — Haiku is not Bedrock's RCA default but
        // must still be pickable there, or choosing a cheaper model would mean changing provider.
        var bedrockOnRca =
                LanePriority.forProvider(ModelLane.RCA, ModelProvider.BEDROCK).orElseThrow();
        assertEquals(SONNET_5, bedrockOnRca.defaultModelKey());
        assertEquals(List.of(SONNET_5, HAIKU), bedrockOnRca.modelKeys());

        settings.set(PID, ORG, ModelLane.RCA, HAIKU, ServiceTier.STANDARD, null);
        verify(repo).upsert(PID, ModelLane.RCA, HAIKU, ServiceTier.STANDARD, null);
    }

    @Test
    void aLaneFallsToTheNextProviderInItsOrder() {
        // The org holds one key, for a provider well down both orders. Every lane still gets a model
        // rather than nothing: the order is a preference, not a requirement. The two lanes land on
        // different xAI models because TRIAGE's ceiling excludes the flagship — every grok-4.x chat
        // model starts at $1.25 input, so the coding tier is xAI's only way onto that lane.
        configured(ModelProvider.GROK);
        assertEquals(
                "GROK:grok-4.6",
                settings.resolve(PID, ModelLane.RCA).orElseThrow().modelKey());
        assertEquals(
                "GROK:grok-code-fast-1",
                settings.resolve(PID, ModelLane.TRIAGE).orElseThrow().modelKey());
    }

    @Test
    void everyLaneReachesEveryProviderTheSandboxCanRun() {
        // The coverage rule: whichever single key an org holds, both lanes resolve to something.
        // ANTHROPIC, OPENROUTER and MOONSHOT used to be absent here because the sandbox launcher had
        // no provider mode for them; it now has one for all ten, so the rule is over the whole enum.
        // Asserted as allOf rather than a hand-listed set on purpose: a new ModelProvider constant
        // must fail this test until it is given both a launcher mode and a place on both lanes, which
        // is the mistake the three exclusions above were.
        Set<ModelProvider> reachable = EnumSet.allOf(ModelProvider.class);
        for (ModelLane lane : ModelLane.values()) {
            Set<ModelProvider> covered = LanePriority.of(lane).stream()
                    .map(LanePriority.ProviderOption::provider)
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ModelProvider.class)));
            assertEquals(reachable, covered, "lane " + lane);
        }
    }

    @Test
    void anExplicitChoiceBeatsTheOrder() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.STANDARD)));

        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(HAIKU, rca.modelKey());
        assertFalse(rca.automatic());
        assertEquals(
                "GLM:glm-5.3-flash",
                settings.resolve(PID, ModelLane.TRIAGE).orElseThrow().modelKey(),
                "an unset lane must not inherit another lane's choice; it follows its own provider order");
    }

    @Test
    void anExplicitChoiceWhoseProviderIsGoneFallsBackToTheOrder() {
        // A credential deleted after the choice was made. The lane must keep running, and the row must
        // survive so re-adding the key brings the choice back.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, "GROK:grok-4.6", ServiceTier.STANDARD)));
        configured(ModelProvider.BEDROCK);

        var rca = settings.resolve(PID, ModelLane.RCA).orElseThrow();
        assertEquals(SONNET_5, rca.modelKey());
        assertTrue(rca.automatic());
        assertEquals(1, settings.list(PID).size(), "the row is still reported to the settings UI");
    }

    @Test
    void rejectsAModelThatIsNotOnePlatformModel() {
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> settings.set(PID, ORG, ModelLane.RCA, "gpt-5.5", ServiceTier.STANDARD, null));
        assertEquals(ModelConfigError.UNKNOWN_PLATFORM_MODEL, ex.error());
    }

    @Test
    void refusesToSaveAModelWhoseProviderTheOrgHasNoKeyFor() {
        configured(ModelProvider.BEDROCK);
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> settings.set(PID, ORG, ModelLane.RCA, "GROK:grok-4.6", ServiceTier.STANDARD, null));
        assertEquals(ModelConfigError.PROVIDER_NOT_CONFIGURED, ex.error());
    }

    /**
     * #939 D6 removed Nova (Amazon is not a supported maker), and every model left in
     * {@link BedrockModelProfile#PROFILES} is agentic=true — there is no findable, non-agentic
     * Bedrock model left to exercise {@code MODEL_NOT_AGENTIC} through the Bedrock half of
     * {@link ProjectModelSettings#validate} any more. {@code aCatalogEntryThatIsNotAgentic_isRejectedOnTheSandboxLane}
     * below is the surviving {@code MODEL_NOT_AGENTIC} coverage, via the catalog half instead —
     * {@code OPENAI:gpt-5.5} is a real, non-agentic entry.
     */
    @Test
    void aNovaShapedKeyFailsAsUnknownRatherThanNonAgentic() {
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            EvalsException ex = assertThrows(
                    EvalsException.class, () -> settings.set(PID, ORG, lane, NOVA, ServiceTier.STANDARD, null));
            assertEquals(ModelConfigError.UNKNOWN_PLATFORM_MODEL, ex.error(), "lane " + lane);
        }
    }

    @Test
    void theAgentVmOfferListNowIncludesHaiku45AndLuna() {
        // A (#994) widened OFFERED_BY_GROUP[AGENT_VM] to include Haiku 4.5 and Luna. Both land on BOTH
        // AGENT_VM lanes at once, not TRIAGE alone: the offer list is keyed by LaneGroup
        // (ModelLane#group), and RCA and TRIAGE both share LaneGroup.AGENT_VM — there is no mechanism
        // to offer a model on one lane of a group but not its siblings.
        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            settings.set(PID, ORG, lane, HAIKU, ServiceTier.STANDARD, null);
            verify(repo).upsert(PID, lane, HAIKU, ServiceTier.STANDARD, null);
            settings.set(PID, ORG, lane, LUNA, ServiceTier.STANDARD, null);
            verify(repo).upsert(PID, lane, LUNA, ServiceTier.STANDARD, null);
        }
    }

    @Test
    void aStoredRowForARemovedModelFallsBackToTheOrder() {
        // A row written before the validator existed (or, since #939 D6, before Nova was removed
        // entirely) must not pin a sandbox to a model that no longer resolves anywhere.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, NOVA, ServiceTier.STANDARD)));
        assertTrue(settings.resolve(PID, ModelLane.RCA).orElseThrow().automatic());
    }

    @Test
    void aStoredPairThatIsNoLongerSupportedFallsBackToTheOrder() {
        // If AWS retires a tier for a model (or we drop a model from the profile list), the stored row
        // would otherwise fail EVERY call in that lane.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.FLEX)));

        assertTrue(
                settings.resolve(PID, ModelLane.RCA).orElseThrow().automatic(),
                "unsupported pair must not reach Bedrock");
        assertEquals(1, settings.list(PID).size(), "but the row is still reported to the settings UI");
    }

    @Test
    void readsAreCachedAndInvalidatedOnWrite() {
        // Reads sit on the path of every LLM call, so they must not hit Postgres each time — but a
        // settings change has to take effect on the NEXT call, not after some TTL.
        settings.resolve(PID, ModelLane.RCA);
        settings.resolve(PID, ModelLane.RCA);
        verify(repo, times(1)).findByProject(PID);

        settings.set(PID, ORG, ModelLane.RCA, HAIKU, ServiceTier.STANDARD, null);
        settings.resolve(PID, ModelLane.RCA);
        verify(repo, times(2)).findByProject(PID);
    }

    @Test
    void clearingALaneDeletesTheRowRatherThanWritingADefault() {
        // There is no default to write: the lane goes back to its priority order, resolved against
        // whatever providers the org has at the time.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.STANDARD)));
        settings.clear(PID, ModelLane.RCA);
        verify(repo).delete(PID, ModelLane.RCA);

        when(repo.findByProject(PID)).thenReturn(List.of());
        assertTrue(settings.resolve(PID, ModelLane.RCA).orElseThrow().automatic());
    }

    // ---- #939: the non-Bedrock model_key union (GEMINI/GLM/GROK/CUSTOM) ----

    @Test
    void aNonBedrockAgenticCatalogModelOnTheSandboxLane_isAccepted() {
        settings.set(PID, ORG, ModelLane.RCA, "GEMINI:gemini-3.1-pro-preview", ServiceTier.STANDARD, null);
        verify(repo).upsert(PID, ModelLane.RCA, "GEMINI:gemini-3.1-pro-preview", ServiceTier.STANDARD, null);
    }

    @Test
    void aCatalogKeyForAnUnknownCatalogEntry_isRejectedAsUnknownPlatformModel() {
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> settings.set(PID, ORG, ModelLane.RCA, "GEMINI:no-such-model", ServiceTier.STANDARD, null));
        assertEquals(ModelConfigError.UNKNOWN_PLATFORM_MODEL, ex.error());
    }

    @Test
    void aCatalogEntryThatIsNotAgentic_isRejectedOnTheSandboxLane() {
        // OPENAI:gpt-5.5 is a real ModelCatalog entry (chat-completion only, agentic=false) — a valid
        // model, just not one offered for a lane that hands its id to a sandbox agent.
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> settings.set(PID, ORG, ModelLane.TRIAGE, "OPENAI:gpt-5.5", ServiceTier.STANDARD, null));
        assertEquals(ModelConfigError.MODEL_NOT_AGENTIC, ex.error());
    }

    @Test
    void resolveAgenticModel_forACatalogRow_returnsTheProviderAndBareModelName() {
        when(repo.findByProject(PID))
                .thenReturn(List.of(row(ModelLane.RCA, "GEMINI:gemini-3.1-pro-preview", ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.GEMINI, resolved.provider());
        assertEquals("gemini-3.1-pro-preview", resolved.modelId());
    }

    @Test
    void resolveAgenticModel_forABedrockRow_returnsBedrockAndTheFullInferenceProfileId() {
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, HAIKU, ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.BEDROCK, resolved.provider());
        assertEquals("global.anthropic.claude-haiku-4-5-20251001-v1:0", resolved.modelId());
    }

    @Test
    void resolveAgenticModel_forAMantleRow_returnsBedrockMantle() {
        // RCA, not TRIAGE: Terra is $2/$12 per MTok, over TRIAGE's ceiling, so that lane does not
        // offer it and a row naming it there would fall back to the automatic answer.
        when(repo.findByProject(PID)).thenReturn(List.of(row(ModelLane.RCA, TERRA, ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.BEDROCK_MANTLE, resolved.provider());
    }

    @Test
    void aCustomProviderCatalogKey_acceptsAnyModelNameSuffix() {
        // CUSTOM has no real per-model catalog (ModelCatalog's own comment) — any non-blank suffix is
        // valid, and it round-trips through resolveAgenticModel as the free-text name the user chose.
        settings.set(PID, ORG, ModelLane.RCA, "CUSTOM:my-self-hosted-model", ServiceTier.STANDARD, null);
        verify(repo).upsert(PID, ModelLane.RCA, "CUSTOM:my-self-hosted-model", ServiceTier.STANDARD, null);

        when(repo.findByProject(PID))
                .thenReturn(List.of(row(ModelLane.RCA, "CUSTOM:my-self-hosted-model", ServiceTier.STANDARD)));
        var resolved = settings.resolveAgenticModel(PID, ModelLane.RCA).orElseThrow();
        assertEquals(ModelProvider.CUSTOM, resolved.provider());
        assertEquals("my-self-hosted-model", resolved.modelId());
    }
}
