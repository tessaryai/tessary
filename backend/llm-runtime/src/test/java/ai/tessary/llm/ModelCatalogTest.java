// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llm.catalog.ProviderModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #939 TASK 2 changed what this class tests. {@link ModelCatalog#entries()} used to BE the roster —
 * so pinning "gpt-4o is gone" or "Bedrock hosts no Moonshot models yet" here was a fact about the
 * whole product. It no longer is: {@link ModelCatalog#mergeLive} means the roster a project actually
 * sees is the static table overlaid with a live-fetched listing, per org and per provider — "which
 * models exist" is now a question {@code ModelCatalogFetchService}, {@code OpenAiCompatModelListerTest}
 * and friends answer, against fake vendor responses, not a hardcoded fact pinned here.
 *
 * <p>What stays here: the static table's own SHAPE invariants (every entry names a real platform,
 * vendor is never blank) and {@link ModelCatalog#mergeLive}'s reconciliation logic — the design point
 * the class javadoc calls out (per-model overlay, not per-provider, because OPENROUTER's own static
 * rows already carry different {@code effortLevels} per model).
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
    void platformAuthKinds() {
        // #939 D6 removed Ollama (the platform's one AUTH_NONE, credential-free provider) — every
        // platform is now AUTH_API_KEY or AUTH_AWS; see PlatformCatalog's own removal note.
        assertEquals(PlatformCatalog.AUTH_AWS, PlatformCatalog.authOf(ModelProvider.BEDROCK));
        assertEquals(PlatformCatalog.AUTH_API_KEY, PlatformCatalog.authOf(ModelProvider.ANTHROPIC));
        assertEquals(PlatformCatalog.AUTH_API_KEY, PlatformCatalog.authOf(ModelProvider.OPENAI));
    }

    @Test
    void mergeLiveWithNoLiveListing_passesTheStaticTableThroughUnchanged() {
        // A cold cache (no credential, or a fetch that failed with nothing to fall back to) must
        // degrade to today's static list, not to nothing — the corrective brief's own mandatory
        // property (iii) applied at this seam.
        List<ModelCatalog.CatalogEntry> merged = ModelCatalog.mergeLive(ModelProvider.OPENAI, List.of());
        List<ModelCatalog.CatalogEntry> staticEntries = ModelCatalog.entries().stream()
                .filter(e -> e.provider() == ModelProvider.OPENAI)
                .toList();
        assertEquals(staticEntries, merged);
    }

    @Test
    void mergeLiveOverlaysDisplayNameAndVendorOntoAMatchingStaticEntry_capabilityFieldsUnchanged() {
        // gpt-5.5 is a real static OPENAI entry with strictJsonSchema=true and OPENAI_EFFORTS — the
        // live listing must not be allowed to touch either, since a vendor's /models response carries
        // no capability information at all (see OpenAiCompatModelLister's own javadoc).
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
    void mergeLiveNeverCrossesProviders() {
        // A live listing passed for OPENAI must not touch ANTHROPIC's static rows, even though
        // ModelCatalog.entries() holds both.
        List<ModelCatalog.CatalogEntry> merged = ModelCatalog.mergeLive(ModelProvider.OPENAI, List.of());
        assertTrue(merged.stream().allMatch(e -> e.provider() == ModelProvider.OPENAI));
    }
}
