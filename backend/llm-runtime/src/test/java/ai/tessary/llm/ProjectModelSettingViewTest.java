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
import ai.tessary.llmspi.LaneGroup;
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
 * The settings payload's shape, held against the one claim
 * {@link ProjectModelSettingController} makes about itself: the page must be renderable with no lane,
 * group or model knowledge of its own.
 *
 * <p>That claim is not checkable by the compiler and only half-checkable by the OpenAPI drift test,
 * which pins the field NAMES against the spec but says nothing about whether the values reaching them
 * are self-consistent — a lane whose group has no section, or whose options name a model the payload
 * does not carry, serializes perfectly and renders a dropdown with a missing heading or a blank
 * selection. Those are the two ways this payload has actually broken.
 *
 * <p>A unit test rather than a Spring one deliberately: none of these facts involve the database. The
 * org here holds no provider credential, which is the shape a brand-new org has: every lane's
 * {@code effective_model_key} is null and the page asks for a provider rather than a model.
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
                mock(ChatModelFactory.class),
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
    void everyLaneBelongsToASectionThePayloadAlsoDescribes() {
        // A lane pointing at a group with no GroupView is a whole settings section that renders with no
        // heading and no copy — and it would still round-trip through JSON, so nothing else catches it.
        var v = view();
        Set<LaneGroup> sections = v.groups().stream()
                .map(ProjectModelSettingController.GroupView::id)
                .collect(Collectors.toSet());
        assertEquals(Set.of(LaneGroup.values()), sections, "one section per group, no more and no fewer");
        for (var lane : v.lanes()) {
            assertTrue(sections.contains(lane.group()), "lane " + lane.id() + " has no section to render under");
        }
    }

    @Test
    void onlyTheGroupWhoseRequestsWeBuildOffersATierOrAnEffortControl() {
        // The two per-request controls, sent as data rather than inferred client-side. An AGENT_VM lane
        // hands a model id to an agent that composes its own requests, so a control shown there would
        // record a preference no request ever carries.
        for (var g : view().groups()) {
            boolean isRequestGroup = g.id() == LaneGroup.LLM_CALLS;
            assertEquals(isRequestGroup, g.tiered(), "tier control on " + g.id());
            assertEquals(isRequestGroup, g.effortTunable(), "effort control on " + g.id());
            assertFalse(g.label().isBlank(), "every section needs a heading");
            assertFalse(g.description().isBlank(), "and the line of copy under it");
        }
    }

    @Test
    void eachLaneCarriesItsOwnOptionsGroupedByProviderInPriorityOrder() {
        // Every option a lane offers must name a model the same payload describes, or the dropdown
        // renders a key with no label — the wrong-label failure this page was rebuilt to end.
        var v = view();
        // #939: a lane's model keys can name either half of the model_key union — a Bedrock key
        // (v.models()) or a "<PROVIDER>:<model_name>" catalog key (v.catalogModels()) — so the
        // catalogue this test checks every offered key against must be the union of both.
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
        // The state the page has to say out loud rather than paper over with a default: no credential
        // means no model, on every lane, and automatic (nobody has chosen anything yet).
        for (var lane : view().lanes()) {
            assertNull(lane.effectiveModelKey(), "lane " + lane.id() + " has no provider to run on");
            assertTrue(lane.automatic(), "lane " + lane.id());
        }
    }

    @Test
    void theWireNamesAreSnakeCaseAndTheRetiredFieldsAreGone() throws Exception {
        // Serialization rather than record accessors, because the frontend reads the JSON: a dropped
        // @JsonProperty renames a field to camelCase and every consumer silently reads undefined. The
        // absent-field assertions are the cutover's own: there is no default model anywhere in this
        // payload any more, and LaneView.tiered was replaced by the group it was a lossy view of.
        JsonNode json = new ObjectMapper().valueToTree(view());

        assertTrue(json.at("/default_model_key").isMissingNode(), "a default named a provider the org may not have");
        // RCA, not "assistant": #1117 deleted ModelLane.ASSISTANT (the lane that used to ship first),
        // so RCA is first in declaration order now, and it is an AGENT_VM lane — no tier, no effort.
        JsonNode rca = json.at("/lanes/0");
        assertEquals("rca", rca.at("/id").asText(), "lanes ship in ModelLane declaration order");
        assertEquals("agent_vm", rca.at("/group").asText());
        assertTrue(rca.at("/default_model_key").isMissingNode(), "a lane names no default model of its own");
        assertTrue(rca.at("/effective_model_key").isNull(), "no credential, so nothing runs this lane");
        assertTrue(rca.at("/automatic").asBoolean());
        assertTrue(rca.at("/model_keys").isMissingNode(), "options are grouped by provider now, not flat");
        assertTrue(rca.at("/provider_options").isArray());
        // The default a lane DOES carry sits one level down, per provider: which model that provider
        // runs when nobody has chosen. That is the shape the page's two dropdowns read.
        assertEquals("BEDROCK", rca.at("/provider_options/0/provider").asText());
        assertEquals("AWS Bedrock", rca.at("/provider_options/0/label").asText());
        assertEquals(
                "anthropic.claude-sonnet-5",
                rca.at("/provider_options/0/default_model_key").asText());
        assertTrue(rca.at("/tiered").isMissingNode(), "the group carries this now, once per section");

        assertEquals("llm_calls", json.at("/groups/0/id").asText());
        assertEquals("agent_vm", json.at("/groups/1/id").asText());
        assertTrue(json.at("/groups/0/effort_tunable").asBoolean());
        assertFalse(json.at("/groups/1/effort_tunable").asBoolean());

        // Every surviving lane, on the wire name it ships under. Track A deleted "grading" and
        // "synthesis"; #1117 deleted "assistant"; this set is what a client may now PUT.
        Set<String> laneIds = json.at("/lanes").findValuesAsText("id").stream().collect(Collectors.toSet());
        assertEquals(Set.of("rca", "triage"), laneIds);
    }
}
