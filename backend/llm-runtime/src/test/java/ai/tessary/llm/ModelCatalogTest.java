// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llm.catalog.ProviderModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The static table's shape invariants (every entry names a real platform, vendor never blank) and {@link
 * ModelCatalog#mergeLive}'s per-model overlay (OPENROUTER's static rows carry different {@code effortLevels} per
 * model). Which models exist is {@code ModelCatalogFetchService}'s question now, not a fact pinned here.
 */
class ModelCatalogTest {

    @Test
    void everyCatalogEntryHasAKnownPlatformDescriptor() {
        for (ModelCatalog.CatalogEntry e : ModelCatalog.entries()) {
            assertTrue(
                    PlatformCatalog.find(e.provider()).isPresent(), "missing platform descriptor for " + e.provider());
            assertFalse(e.vendor().isBlank(), "vendor required for grouping: " + e.modelName());
        }
    }

    @Test
    void onlyTypeSafeNamesTheLaneItIsFor() {
        for (PlatformCatalog.PlatformDescriptor p : PlatformCatalog.platforms()) {
            List<String> expected = p.id() == ModelProvider.TYPESAFE ? List.of("frustration") : List.of();
            assertEquals(expected, p.usedBy(), p.id().name());
        }
    }

    @Test
    void mergeLiveWithNoLiveListing_passesTheStaticTableThroughUnchanged() {
        // A cold cache degrades to the static list, not to nothing.
        List<ModelCatalog.CatalogEntry> merged = ModelCatalog.mergeLive(ModelProvider.OPENAI, List.of());
        List<ModelCatalog.CatalogEntry> staticEntries = ModelCatalog.entries().stream()
                .filter(e -> e.provider() == ModelProvider.OPENAI)
                .toList();
        assertEquals(staticEntries, merged);
    }

    @Test
    void mergeLiveOverlaysDisplayNameAndVendorOntoAMatchingStaticEntry_capabilityFieldsUnchanged() {
        // gpt-5.5's strictJsonSchema and efforts are static; a vendor's /models carries no capability data.
        ModelCatalog.CatalogEntry before =
                ModelCatalog.find(ModelProvider.OPENAI, "gpt-5.5").orElseThrow();
        List<ModelCatalog.CatalogEntry> merged = ModelCatalog.mergeLive(
                ModelProvider.OPENAI, List.of(new ProviderModel("gpt-5.5", "GPT-5.5 (live)", "OpenAI (live)")));
        ModelCatalog.CatalogEntry after = merged.stream()
                .filter(e -> e.modelName().equals("gpt-5.5"))
                .findFirst()
                .orElseThrow();

        assertEquals("GPT-5.5 (live)", after.displayName(), "live display name overlays the static one");
        assertEquals("OpenAI (live)", after.vendor(), "live vendor label overlays the static one");
        assertEquals(before.strictJsonSchema(), after.strictJsonSchema(), "capability fields are NOT overlaid");
        assertEquals(before.effortLevels(), after.effortLevels(), "capability fields are NOT overlaid");
        assertEquals(before.agentic(), after.agentic(), "capability fields are NOT overlaid");
    }

    @Test
    void mergeLiveSynthesizesAFailClosedEntryForALiveModelWithNoStaticMatch() {
        List<ModelCatalog.CatalogEntry> merged = ModelCatalog.mergeLive(
                ModelProvider.OPENAI, List.of(new ProviderModel("gpt-6-preview", "GPT-6 Preview", "OpenAI")));
        ModelCatalog.CatalogEntry synthesized = merged.stream()
                .filter(e -> e.modelName().equals("gpt-6-preview"))
                .findFirst()
                .orElseThrow();

        assertFalse(synthesized.agentic(), "a model this table has never evaluated must not be agentic");
        assertTrue(synthesized.effortLevels().isEmpty());
        assertFalse(synthesized.strictJsonSchema());
        assertEquals("OpenAI", synthesized.vendor());
        assertEquals("GPT-6 Preview", synthesized.displayName());
    }

    @Test
    void pricingIdRoutePrefixesTheSevenModelsWhoseBookKeysCarryOne() {
        // The seven entries priced only under a route-prefixed key; everything else is bare.
        assertEquals("xai/grok-4.6", ModelCatalog.pricingId(ModelProvider.GROK, "grok-4.6"));
        assertEquals("xai/grok-code-fast-1", ModelCatalog.pricingId(ModelProvider.GROK, "grok-code-fast-1"));
        assertEquals("zai/glm-5.3", ModelCatalog.pricingId(ModelProvider.GLM, "glm-5.3"));
        assertEquals("zai/glm-5.3-flash", ModelCatalog.pricingId(ModelProvider.GLM, "glm-5.3-flash"));
        assertEquals("moonshot/kimi-k2.6", ModelCatalog.pricingId(ModelProvider.MOONSHOT, "kimi-k2.6"));
        assertEquals(
                "openrouter/openai/gpt-5.6-terra",
                ModelCatalog.pricingId(ModelProvider.OPENROUTER, "openai/gpt-5.6-terra"));
        assertEquals(
                "openrouter/openai/gpt-5.6-luna",
                ModelCatalog.pricingId(ModelProvider.OPENROUTER, "openai/gpt-5.6-luna"));
    }

    @Test
    void pricingIdLeavesBareBookKeysUnchangedAndRoutesMantle() {
        assertEquals("gpt-5.6-terra", ModelCatalog.pricingId(ModelProvider.OPENAI, "gpt-5.6-terra"));
        assertEquals("claude-sonnet-5", ModelCatalog.pricingId(ModelProvider.ANTHROPIC, "claude-sonnet-5"));
        assertEquals("gemini-3.1-pro-preview", ModelCatalog.pricingId(ModelProvider.GEMINI, "gemini-3.1-pro-preview"));
        assertEquals(
                "bedrock_mantle/openai.gpt-5.6-luna",
                ModelCatalog.pricingId(ModelProvider.BEDROCK_MANTLE, "openai.gpt-5.6-luna"));
    }

    @Test
    void pricingIdPricesTypeSafeUnderItsBookPrefix() {
        assertEquals("typesafe/jev-latest", ModelCatalog.pricingId(ModelProvider.TYPESAFE, "jev-latest"));
    }

    @Test
    void mergeLiveDropsPinnedJevVersionsFromAnOpenRouterListing_keepingTheStaticEntry() {
        List<ModelCatalog.CatalogEntry> merged = ModelCatalog.mergeLive(
                ModelProvider.OPENROUTER,
                List.of(
                        new ProviderModel("typesafe/jev-latest", "TypeSafe: Jev", "TypeSafe"),
                        new ProviderModel("typesafe/jev-1.13-20260917", "TypeSafe: Jev 1.13", "TypeSafe")));

        List<String> jev = merged.stream()
                .map(ModelCatalog.CatalogEntry::modelName)
                .filter(n -> n.startsWith("typesafe/"))
                .toList();
        assertEquals(List.of("typesafe/jev-latest"), jev);
        assertTrue(merged.stream()
                .filter(e -> e.modelName().equals("typesafe/jev-latest"))
                .allMatch(ModelCatalog.CatalogEntry::decision));
    }
}
