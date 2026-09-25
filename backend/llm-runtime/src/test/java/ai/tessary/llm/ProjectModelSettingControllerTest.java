// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>The gap this closes: {@link ProjectModelSettings#set} accepts the
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
        controller =
                new ProjectModelSettingController(settings, resolver, priceModels, priceBooks, providerCredentials);
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
                        "TYPESAFE",
                        "OPENROUTER",
                        "CUSTOM"),
                view.catalogModels().stream().map(e -> e.provider().name()).toList());

        for (ModelLane lane : List.of(ModelLane.RCA, ModelLane.TRIAGE)) {
            var laneView = view.lanes().stream()
                    .filter(l -> l.id() == lane)
                    .findFirst()
                    .orElseThrow();
            assertEquals(LaneGroup.AGENT_VM, laneView.group());
            // Provider first: every provider the sandbox can run appears on both lanes. Asserts the
            // coverage rule rather than the model names, since which model is each lane's own business.
            assertEquals(
                    java.util.Arrays.stream(ModelProvider.values())
                            // Decision models only; never a sandbox agent.
                            .filter(p -> p != ModelProvider.TYPESAFE)
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
            // The pre-existing Bedrock offer list must still be reachable, not replaced by the catalog
            // keys — TRIAGE and RCA now carry the same Bedrock models, so this holds per lane.
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
    void getOffersOnlyAgenticAndDecisionCatalogEntries() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();

        assertTrue(
                view.catalogModels().stream().allMatch(e -> e.agentic() || e.decision()),
                "catalog_models must never carry a chat entry no lane offers (the older OpenAI-direct models, "
                        + "Anthropic-direct, OpenRouter, Moonshot, Bedrock): "
                        + view.catalogModels());
    }

    @Test
    void theFrustrationLaneIsADecisionSectionOfferingOnlyJevTypeSafeFirst() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();

        var group = view.groups().stream()
                .filter(g -> g.id() == LaneGroup.DECISION_CALLS)
                .findFirst()
                .orElseThrow();
        assertFalse(group.modelSelectable(), "one model per provider, so the row is a provider select only");
        var lane = view.lanes().stream()
                .filter(l -> l.id() == ModelLane.FRUSTRATION)
                .findFirst()
                .orElseThrow();
        assertEquals(LaneGroup.DECISION_CALLS, lane.group());
        assertEquals(
                List.of(ModelProvider.TYPESAFE, ModelProvider.OPENROUTER),
                lane.providerOptions().stream()
                        .map(ProjectModelSettingController.ProviderOptionView::provider)
                        .toList());
        assertEquals(
                List.of("TYPESAFE:jev-latest", "OPENROUTER:typesafe/jev-latest"),
                lane.providerOptions().stream()
                        .flatMap(o -> o.modelKeys().stream())
                        .toList());
    }
}
