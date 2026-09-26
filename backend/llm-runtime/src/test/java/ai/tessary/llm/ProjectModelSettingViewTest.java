// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The settings payload against {@link ProjectModelSettingController}'s claim: the page renders with no lane, group,
 * or model knowledge of its own. The OpenAPI drift test pins names, not consistency; a lane whose group has no
 * section, or an option naming a model the payload lacks, serializes fine and renders broken. The org holds no
 * credential, like a new org, so every {@code effective_model_key} is null.
 */
class ProjectModelSettingViewTest {

    private ProjectModelSettingController controller;

    @BeforeEach
    void setUp() {
        ProjectModelSettingRepository repo = mock(ProjectModelSettingRepository.class);
        when(repo.findByProject(anyString())).thenReturn(List.of());
        TenantPathResolver resolver = mock(TenantPathResolver.class);
        when(resolver.requireProject(any(), anyString(), anyString()))
                .thenReturn(new TenantPathResolver.Resolved(
                        new Organization("org_1", null, "acme", "Acme", "t0", null, null),
                        new Project("prj_1", "org_1", "web", "Web", null, "t0", null, null, true, null),
                        "owner"));
        ai.tessary.pricing.ModelResolver priceModels = mock(ai.tessary.pricing.ModelResolver.class);
        when(priceModels.resolve(any())).thenReturn(java.util.Optional.empty());
        ProviderCredentialRepository providerCredentials = mock(ProviderCredentialRepository.class);
        when(providerCredentials.findByOrg(anyString())).thenReturn(List.of());
        controller = new ProjectModelSettingController(
                new ProjectModelSettings(
                        repo,
                        providerCredentials,
                        mock(ProjectOrgResolver.class),
                        mock(ai.tessary.llm.catalog.ModelCatalogFetchService.class)),
                resolver,
                priceModels,
                mock(ai.tessary.pricing.PriceBookRepository.class),
                providerCredentials);
    }

    private ProjectModelSettingController.ModelSettingsView view() {
        var ctx = new TenantContext("usr_1", "a@b.c", "org_1", null, null, null);
        ProjectModelSettingController.ModelSettingsView v =
                controller.get(ctx, "acme", "web").data();
        assertNotNull(v, "the GET always carries a payload");
        return v;
    }

    @Test
    void eachLaneCarriesItsOwnOptionsGroupedByProviderInPriorityOrder() {
        // Every option names a model the payload describes, or the dropdown shows a key with no label.
        var v = view();
        // Keys can be Bedrock (models) or catalog (catalogModels), so check against the union.
        Set<String> catalogue = new java.util.HashSet<>(v.models().stream()
                .map(BedrockModelProfile.ModelDescriptor::modelKey)
                .toList());
        v.catalogModels().forEach(e -> catalogue.add(ModelCatalog.key(e)));
        assertEquals(ModelLane.values().length, v.lanes().size());
        for (var lane : v.lanes()) {
            assertFalse(lane.providerOptions().isEmpty(), "lane " + lane.id() + " would render an empty dropdown");
            assertEquals(
                    LanePriority.of(lane.id()).stream()
                            .map(LanePriority.ProviderOption::provider)
                            .toList(),
                    lane.providerOptions().stream()
                            .map(ProjectModelSettingController.ProviderOptionView::provider)
                            .toList(),
                    "the client renders this order as-is, so it must be the fallback order itself");
            for (var option : lane.providerOptions()) {
                assertFalse(option.label().isBlank(), "every provider needs a name the page can print");
                assertFalse(option.modelKeys().isEmpty(), "provider " + option.provider() + " offers nothing");
                assertTrue(
                        catalogue.containsAll(option.modelKeys()),
                        "lane " + lane.id() + " offers a key with no model: " + option.modelKeys());
                assertTrue(
                        option.modelKeys().contains(option.defaultModelKey()),
                        "provider " + option.provider() + " defaults outside its own model list");
            }
        }
    }

    @Test
    void anOrgWithNoProviderRunsNoModelOnAnyLane() {
        // No credential means no model on every lane, and automatic; the page says so rather than defaulting.
        for (var lane : view().lanes()) {
            assertNull(lane.effectiveModelKey(), "lane " + lane.id() + " has no provider to run on");
            assertTrue(lane.automatic(), "lane " + lane.id());
        }
    }

    @Test
    void theWireNamesAreSnakeCaseAndTheRetiredFieldsAreGone() throws Exception {
        // Serialized, since the frontend reads JSON: a dropped @JsonProperty turns a field camelCase and consumers
        // read undefined. No default model remains, and LaneView.tiered is retired.
        JsonNode json = new ObjectMapper().valueToTree(view());

        assertTrue(json.at("/default_model_key").isMissingNode(), "a default named a provider the org may not have");
        // ModelLane.ASSISTANT is deleted, so RCA is first: an AGENT_VM lane with no tier or effort.
        JsonNode rca = json.at("/lanes/0");
        assertEquals("rca", rca.at("/id").asText(), "lanes ship in ModelLane declaration order");
        assertEquals("agent_vm", rca.at("/group").asText());
        assertTrue(rca.at("/default_model_key").isMissingNode(), "a lane names no default model of its own");
        assertTrue(rca.at("/effective_model_key").isNull(), "no credential, so nothing runs this lane");
        assertTrue(rca.at("/automatic").asBoolean());
        assertTrue(rca.at("/model_keys").isMissingNode(), "options are grouped by provider now, not flat");
        assertTrue(rca.at("/provider_options").isArray());
        // Each provider carries the model it runs when nobody has chosen, which the page's two dropdowns read.
        assertEquals("BEDROCK", rca.at("/provider_options/0/provider").asText());
        assertEquals("AWS Bedrock", rca.at("/provider_options/0/label").asText());
        assertEquals(
                "anthropic.claude-sonnet-5",
                rca.at("/provider_options/0/default_model_key").asText());
        assertTrue(rca.at("/tiered").isMissingNode(), "a retired field");

        assertEquals("agent_vm", json.at("/groups/0/id").asText());
        assertEquals("decision_calls", json.at("/groups/1/id").asText());
        assertFalse(json.at("/groups/1/model_selectable").asBoolean());
        assertTrue(json.at("/groups/0/model_selectable").asBoolean());

        // The surviving lanes, the set a client may PUT.
        Set<String> laneIds = json.at("/lanes").findValuesAsText("id").stream().collect(Collectors.toSet());
        assertEquals(Set.of("rca", "triage", "frustration"), laneIds);
    }
}
