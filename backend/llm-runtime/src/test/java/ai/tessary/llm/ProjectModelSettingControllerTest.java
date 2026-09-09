// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * First test coverage for {@link ProjectModelSettingController} — confirmed absent before this
 * file ({@code find backend -iname "*ProjectModelSettingController*Test*"} returned nothing).
 *
 * <p>The gap this closes: {@link ProjectModelSettings#validate}/{@code set} accept the
 * {@code "<PROVIDER>:<model_name>"} catalog key for an {@link LaneGroup#AGENT_VM} lane (RCA,
 * TRIAGE) — proven by {@link ProjectModelSettingsTest} — but that write path was never checked
 * against the read path that actually feeds the settings page's only model picker
 * ({@code frontend/src/views/Settings/Models.tsx}, which renders strictly off a lane's
 * {@code model_keys} and the {@code models}/{@code catalog_models} arrays the {@code GET} sends).
 * Before the fix, {@code get()} built both from {@link BedrockModelProfile} only, so GEMINI/GLM/
 * GROK/CUSTOM were reachable by hand-crafting a raw PUT but never appeared as an option in the
 * product UI. This asserts the {@code GET} response itself now offers them.
 *
 * <p>No Spring context, no Testcontainers: every collaborator is a Mockito mock and the controller
 * is constructed and called directly, the same shape {@link ProviderCredentialControllerTest}
 * uses for its sibling controller.
 */
@ExtendWith(MockitoExtension.class)
class ProjectModelSettingControllerTest {

    private static final String ORG_SLUG = "acme";
    private static final String PROJECT_SLUG = "default";
    private static final String ORG_ID = "org_1";
    private static final String PROJECT_ID = "proj_1";

    @Mock
    private ProjectModelSettings settings;

    @Mock
    private ChatModelFactory factory;

    @Mock
    private TenantPathResolver resolver;

    @Mock
    private ModelResolver priceModels;

    @Mock
    private PriceBookRepository priceBooks;

    @Mock
    private ProviderCredentialRepository providerCredentials;

    private ProjectModelSettingController controller;
    private TenantContext ctx;

    @BeforeEach
    void setUp() {
        controller = new ProjectModelSettingController(
                settings, factory, resolver, priceModels, priceBooks, providerCredentials);
        ctx = new TenantContext("user_1", "user@example.com", ORG_ID, null, "owner", null);
        Organization org = new Organization(ORG_ID, null, ORG_SLUG, "Acme", "2026-01-01T00:00:00Z", null, null);
        Project project = new Project(
                PROJECT_ID, ORG_ID, PROJECT_SLUG, "Default", null, "2026-01-01T00:00:00Z", null, null, true, null);
        var resolved = new TenantPathResolver.Resolved(org, project, "owner");
        when(resolver.requireProject(ctx, ORG_SLUG, PROJECT_SLUG)).thenReturn(resolved);
        when(settings.list(PROJECT_ID)).thenReturn(List.of());
        // configuredProviders — empty org, no credentials configured. Individual tests
        // that need a configured provider override this.
        when(providerCredentials.findByOrg(ORG_ID)).thenReturn(List.of());
    }

    @Test
    void getOffersEveryAgenticCatalogEntryOnBothAgentVmLanes() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();

        // catalog_models: the non-Bedrock half of the union, present at all — this is the field
        // that did not exist before the fix.
        assertEquals(
                List.of(
                        "ANTHROPIC",
                        "ANTHROPIC",
                        "OPENROUTER",
                        "OPENROUTER",
                        "MOONSHOT",
                        "OPENAI",
                        "OPENAI",
                        "GEMINI",
                        "GLM",
                        "GROK",
                        "OPENAI",
                        "GEMINI",
                        "GLM",
                        "GROK",
                        "CUSTOM"),
                view.catalogModels().stream().map(e -> e.provider().name()).toList());

        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            var laneView = view.lanes().stream()
                    .filter(l -> l.id() == lane)
                    .findFirst()
                    .orElseThrow();
            assertEquals(LaneGroup.AGENT_VM, laneView.group());
            // Provider first: every provider the sandbox can run appears on both lanes, whatever the
            // model behind it turns out to be. Which model is the lane's own business (TRIAGE's price
            // ceiling changes it), so this asserts the coverage rule rather than the model names.
            assertEquals(
                    java.util.Arrays.stream(ModelProvider.values())
                            .map(ModelProvider::name)
                            .collect(java.util.stream.Collectors.toSet()),
                    laneView.providerOptions().stream()
                            .map(o -> o.provider().name())
                            .collect(java.util.stream.Collectors.toSet()),
                    lane + " must reach every runnable provider: " + laneView.providerOptions());
            Set<String> offered = laneView.providerOptions().stream()
                    .flatMap(o -> o.modelKeys().stream())
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(
                    offered.contains("CUSTOM:custom-model"),
                    lane + "'s options must offer the CUSTOM catalog entry: " + offered);
            // The pre-existing Bedrock offer list must still be reachable across the group's lanes,
            // not replaced by the catalog keys. Per lane it is a subset — TRIAGE's ceiling leaves only
            // Haiku — so the union across both lanes is what has to cover it.
            assertTrue(
                    BedrockModelProfile.offeredFor(LaneGroup.AGENT_VM).stream().anyMatch(offered::contains),
                    lane + "'s options must still offer Bedrock models: " + offered);
        }
    }

    @Test
    void everyBedrockModelStaysReachableAcrossTheGroupsLanes() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();
        Set<String> acrossLanes = view.lanes().stream()
                .filter(l -> l.group() == LaneGroup.AGENT_VM)
                .flatMap(l -> l.providerOptions().stream())
                .flatMap(o -> o.modelKeys().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(
                acrossLanes.containsAll(BedrockModelProfile.offeredFor(LaneGroup.AGENT_VM)),
                "a model the group offers that no lane names is unreachable: " + acrossLanes);
    }

    @Test
    void getDoesNotOfferNonAgenticCatalogEntriesAnywhere() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();

        assertTrue(
                view.catalogModels().stream().allMatch(ModelCatalog.CatalogEntry::agentic),
                "catalog_models must never carry a non-agentic entry (the older OpenAI-direct models, "
                        + "Anthropic-direct, OpenRouter, Moonshot, Bedrock) — none of them are offered on any lane: "
                        + view.catalogModels());
    }

    @Test
    void getDoesNotOfferCatalogKeysOnALlmCallsLane() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();

        var gradingLane = view.lanes().stream()
                .filter(l -> l.group() == LaneGroup.LLM_CALLS)
                .findFirst();
        gradingLane.ifPresent(l -> assertTrue(
                l.providerOptions().stream()
                        .flatMap(o -> o.modelKeys().stream())
                        .noneMatch(k -> k.contains(":")),
                "an LLM_CALLS lane must offer only plain Bedrock keys, never a catalog key: " + l.providerOptions()));
    }
}
