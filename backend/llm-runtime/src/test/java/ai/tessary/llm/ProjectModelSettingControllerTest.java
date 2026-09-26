// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.llmspi.LaneGroup;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
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
 * {@link ProjectModelSettingController}'s GET must offer the {@code "<PROVIDER>:<model_name>"} catalog keys {@link
 * ProjectModelSettings#set} accepts for {@link LaneGroup#AGENT_VM} lanes. Models.tsx renders only from {@code
 * model_keys}, {@code models}, and {@code catalog_models}, and before the fix GEMINI, GLM, GROK, and CUSTOM were
 * reachable by raw PUT but never offered. Mocks only, no Spring.
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
        // No credentials configured; tests that need a provider override this.
        when(providerCredentials.findByOrg(ORG_ID)).thenReturn(List.of());
    }

    @Test
    void getOffersEveryAgenticCatalogEntryOnBothAgentVmLanes() {
        var view = controller.get(ctx, ORG_SLUG, PROJECT_SLUG).data();

        // The non-Bedrock half of the union, the field that did not exist before the fix.
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
            // Every provider the sandbox can run appears on both lanes; which model is each lane's business.
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
            // The Bedrock offer list is still there, not replaced by catalog keys.
            assertTrue(
                    BedrockModelProfile.offeredFor(LaneGroup.AGENT_VM).stream().anyMatch(offered::contains),
                    lane + "'s options must still offer Bedrock models: " + offered);
        }
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

    /**
     * The lane arrives lowercase in the path and is parsed by our reader, so any casing reaches it. Tier and effort
     * are accepted and ignored.
     */
    @Test
    void putPointsTheParsedLaneAtTheModelAndIgnoresTierAndEffort() {
        controller.put(
                ctx,
                ORG_SLUG,
                PROJECT_SLUG,
                " RCA ",
                new ProjectModelSettingController.SetLaneModelRequest(
                        "anthropic.claude-haiku-4-5", ServiceTier.FLEX, "high"));

        verify(settings).set(PROJECT_ID, ORG_ID, ModelLane.RCA, "anthropic.claude-haiku-4-5");
    }

    @Test
    void resetReturnsThatLaneToTheAutomaticAnswer() {
        controller.reset(ctx, ORG_SLUG, PROJECT_SLUG, "triage");

        verify(settings).clear(PROJECT_ID, ModelLane.TRIAGE);
    }
}
