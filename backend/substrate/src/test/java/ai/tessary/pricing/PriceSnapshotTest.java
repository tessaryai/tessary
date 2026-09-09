// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Properties of the checked-in rate files themselves, asserted against the whole artifact rather than one
 * id — so a future refresh that breaks one of them fails here, before any cost has been frozen against it.
 */
class PriceSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PriceSnapshot litellm() {
        return PriceSnapshot.load(mapper, PriceBook.SOURCE_LITELLM, PriceSnapshot.LITELLM_RESOURCE)
                .orElseThrow(() -> new AssertionError("the vendored snapshot must load"));
    }

    private static Map<String, ModelRates> byId(PriceSnapshot snapshot) {
        Map<String, ModelRates> out = new LinkedHashMap<>();
        for (PriceSnapshot.Model m : snapshot.models()) {
            out.put(m.id(), m.rates());
        }
        return out;
    }

    @Test
    @DisplayName("the vendored snapshot loads and covers the models production actually runs")
    void load_coversTheModelsProductionRuns() {
        Map<String, ModelRates> rates = byId(litellm());
        assertTrue(rates.size() > 1000, "the vendored snapshot loaded: " + rates.size());
        // Measured against production traffic. opus and gpt-4o are the two the hand-maintained
        // the retired ModelPricingCatalog missed, and opus is a third of all calls — the reason this file exists.
        for (String model :
                new String[] {"claude-sonnet-5", "claude-opus-4-8", "claude-sonnet-4-6", "gpt-4o", "gpt-4o-mini"}) {
            assertNotNull(rates.get(model), "the snapshot should cover " + model);
        }
    }

    @Test
    @DisplayName("the vendored snapshot carries mantle's own route-prefixed spelling for the GPT-5.6 line")
    void load_coversMantlesRoutePrefixedIds() {
        // What BedrockModelProfile.MANTLE_ROUTE_PREFIX depends on existing upstream — see #1032. Without
        // this row, ModelResolver has nothing to resolve the producer's reported id to.
        Map<String, ModelRates> rates = byId(litellm());
        assertNotNull(rates.get("bedrock_mantle/openai.gpt-5.6-luna"), "mantle's own priced route for luna");
        assertNotNull(rates.get("bedrock_mantle/openai.gpt-5.6-terra"), "mantle's own priced route for terra");
    }

    @Test
    @DisplayName("entries that price no tokens are not imported as models")
    void load_skipsEntriesThatPriceNoTokens() {
        // The DB-import side of the same rule TokenPricingTest asserts on the read side; the two must
        // agree or a model is priced in one surface and unpriced in the other. Imported, a rate-less entry
        // becomes a model_price row with every bucket null — indistinguishable from a genuinely free model
        // and invisible to the unpriced count.
        Map<String, ModelRates> models = byId(litellm());
        assertNull(models.get("dall-e-3"), "an entry with no token rate is not a priced model");
        assertNull(models.get("sample_spec"), "LiteLLM's documentation stub is not a model");
        assertNotNull(models.get("claude-sonnet-5"), "the guard must not swallow a genuinely priced model");
    }

    @Test
    @DisplayName("the version is the content, hashed — stable across loads and prefixed by its source")
    void load_versionIsAStableContentHash() {
        assertEquals(litellm().version(), litellm().version(), "the same bytes must always be the same book");
        assertTrue(
                litellm().version().startsWith(PriceBook.SOURCE_LITELLM + "-"),
                litellm().version());
        assertEquals(
                PriceBook.SOURCE_LITELLM.length() + 1 + 12,
                litellm().version().length(),
                "source, a dash, and 12 hex characters");
    }

    @Test
    @DisplayName("per-token rates convert to per-million-token exactly, and an absent bucket stays null")
    void load_convertsToPerMillionTokens() {
        ModelRates sonnet = byId(litellm()).get("claude-sonnet-5");
        // 2e-06 / 2e-07 / 1e-05 / 2.5e-06 per token in the file.
        assertEquals(0, new BigDecimal("2").compareTo(requireRate(sonnet.inputPerMtok())));
        assertEquals(0, new BigDecimal("10").compareTo(requireRate(sonnet.outputPerMtok())));
        assertEquals(0, new BigDecimal("0.2").compareTo(requireRate(sonnet.cacheReadPerMtok())));
        assertEquals(0, new BigDecimal("2.5").compareTo(requireRate(sonnet.cacheWritePerMtok())));

        // gpt-4o's caching is automatic: no cache-creation bucket at all. Null means never billed for it,
        // which must survive the parse rather than becoming a zero rate.
        assertNull(byId(litellm()).get("gpt-4o").cacheWritePerMtok(), "an absent bucket is null, never 0");
    }

    @Test
    @DisplayName("no vendor-prefixed key in the snapshot disagrees with its bare counterpart")
    void load_vendorPrefixIsPriceNeutralAcrossTheWholeBook() {
        // The guarantee ModelResolver's vendor strip rests on, pinned against the file itself rather than
        // asserted about one id — so a future snapshot that introduces a divergence fails here.
        Map<String, ModelRates> rates = byId(litellm());
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (String vendor : new String[] {"anthropic.", "openai.", "meta.", "mistral.", "cohere.", "amazon."}) {
            for (Map.Entry<String, ModelRates> entry : rates.entrySet()) {
                if (!entry.getKey().startsWith(vendor)) continue;
                ModelRates bare = rates.get(entry.getKey().substring(vendor.length()));
                if (bare == null) continue;
                compared++;
                if (!samePrice(entry.getValue(), bare)) mismatches.add(entry.getKey());
            }
        }
        assertTrue(compared > 0, "the sweep must actually compare something");
        assertTrue(mismatches.isEmpty(), "vendor prefix changed the price: " + mismatches);
    }

    @Test
    @DisplayName("an unreadable rate file is empty, not a boot failure")
    void load_missingResourceIsEmpty() {
        Optional<PriceSnapshot> missing = PriceSnapshot.load(mapper, PriceBook.SOURCE_LITELLM, "pricing/absent.json");
        assertTrue(missing.isEmpty(), "a missing book leaves models unpriced; it must not throw");
    }

    private static BigDecimal requireRate(@Nullable BigDecimal rate) {
        assertNotNull(rate, "expected a rate");
        return rate;
    }

    private static boolean samePrice(ModelRates a, ModelRates b) {
        return same(a.inputPerMtok(), b.inputPerMtok())
                && same(a.outputPerMtok(), b.outputPerMtok())
                && same(a.cacheReadPerMtok(), b.cacheReadPerMtok())
                && same(a.cacheWritePerMtok(), b.cacheWritePerMtok());
    }

    private static boolean same(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }
}
