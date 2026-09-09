// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pricing correctness. The failure mode that matters is silently pricing an unknown model at $0 — a real
 * spend rendered as free. Cache-bucket disjointness is established at ingest (substrate-model.md §6.5),
 * so it is asserted there, on the write path, rather than re-derived here.
 */
class TokenPricingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenPriceBook book = new TokenPriceBook(mapper);

    /** Every id the book prices — the vendored snapshot is the only source, so this is the whole book. */
    private final java.util.Set<String> vendoredIds = book.modelIds();

    @Test
    @DisplayName("the vendored book loads and covers the models production actually runs")
    void bookCoversProductionModels() {
        assertTrue(book.size() > 1000, "the vendored snapshot loaded: " + book.size());
        // Measured against production traffic. opus-4-8 and gpt-4o are the two the hand-maintained
        // the retired ModelPricingCatalog missed, and opus is a third of all calls — the reason this book exists.
        for (String model :
                new String[] {"claude-sonnet-5", "claude-opus-4-8", "claude-sonnet-4-6", "gpt-4o", "gpt-4o-mini"}) {
            assertTrue(book.prices(model), "price book should cover " + model);
        }
    }

    @Test
    @DisplayName("an unknown model is unpriced, never zero")
    void unknownModelIsUnpriced() {
        assertFalse(book.prices("definitely-not-a-real-model-v9"));
        assertEquals(
                Optional.empty(),
                book.costOf("definitely-not-a-real-model-v9", new TokenUsage(1000, 1000, 0, 0)),
                "an unpriced model must not read as free");
        assertEquals(Optional.empty(), book.costOf(null, new TokenUsage(1000, 0, 0, 0)));
    }

    @Test
    @DisplayName("an entry that prices no tokens is unpriced, not free")
    void entriesWithNoTokenRateAreNotInTheBook() {
        // Since 2026-09-02 the vendored file is a VERBATIM copy of upstream, and upstream carries roughly
        // 740 entries out of 3,500 that price no tokens at all: image and audio models billed per image or
        // per second, plus LiteLLM's own `sample_spec` documentation stub. Before the copy went verbatim,
        // scripts/refresh-model-prices.sh dropped them while trimming; now TokenPriceBook.hasTokenRate
        // does, and this is the test that says why it must. Every rate bucket on such an entry is null, so
        // `cost` would add four zeroes and answer a confident $0.00 for a call whose price we do not know —
        // the "real spend rendered as free" this class exists to prevent — while `prices` answered true,
        // quietly removing it from the unpriced count that exists to surface exactly that gap.
        //
        // The two ids cover both shapes the guard has to catch: dall-e-3 omits the rate fields entirely
        // (null), sample_spec carries them as a literal 0.0. A null check alone would let the second one
        // through, which is why hasTokenRate tests the signum rather than just the reference.
        for (String noTokenRate : new String[] {"dall-e-3", "sample_spec"}) {
            assertFalse(book.prices(noTokenRate), noTokenRate + " prices no tokens; it must read as unpriced");
            assertEquals(
                    Optional.empty(),
                    book.costOf(noTokenRate, new TokenUsage(1000, 1000, 0, 0)),
                    noTokenRate + " must not read as free");
        }
        // ...and the guard is narrow: a model that DOES price tokens is unaffected by it.
        assertTrue(book.prices("claude-sonnet-5"), "the guard must not swallow a genuinely priced model");
    }

    @Test
    @DisplayName("Bedrock region and vendor prefixes resolve to the same model")
    void prefixedIdsResolve() {
        assertTrue(book.prices("global.anthropic.claude-sonnet-5"));
        assertTrue(book.prices("us.anthropic.claude-sonnet-5"));
        // The undated Bedrock spelling a real producer emits. The book carries `claude-haiku-4-5`
        // and the dated `anthropic.claude-haiku-4-5-20251001-v1:0` but NOT this, so before the vendor
        // strip an entire project's spend read as unpriced.
        assertTrue(book.prices("anthropic.claude-haiku-4-5"), "undated Bedrock id must price");
    }

    @Test
    @DisplayName("a region-prefixed id is never resolved to the cheaper non-regional rate")
    void regionPrefixIsNotStrippedToTheBareModel() {
        // Bedrock charges a regional premium: us./eu./au./jp. are +10% and us-gov. +20% over the bare
        // model, on 28 keys in the book. `us.anthropic.claude-haiku-4-5` is absent verbatim, so a chain
        // that stripped BOTH prefixes landed on claude-haiku-4-5 at 1.0e-06 when the regional rate is
        // 1.1e-06 — a silent 10% under-report, which is worse than the unpriced-and-counted it replaced.
        assertFalse(book.prices("us.anthropic.claude-haiku-4-5"), "honestly unpriced beats quietly 10% wrong");
        // The exact regional id still prices, from its own entry.
        assertTrue(book.prices("us.anthropic.claude-haiku-4-5-20251001-v1:0"));
    }

    @Test
    @DisplayName("no vendor-prefixed key the STRIP can reach disagrees with its bare counterpart")
    void vendorPrefixIsPriceNeutralAcrossTheWholeBook() {
        // The actual guarantee the vendor strip rests on, pinned against the book itself rather than
        // asserted about one id — so a future snapshot that introduces a divergence fails here. Note
        // this only exercises ids the strip actually reaches: mantle's own bedrock_mantle/-prefixed
        // spelling is an exact hit and never falls through the vendor strip at all (see
        // ai.tessary.llm.BedrockModelProfile.MANTLE_ROUTE_PREFIX), so it is not, and should not be,
        // part of this sweep.
        var mismatches = new java.util.ArrayList<String>();
        int compared = 0;
        for (String vendor : new String[] {"anthropic.", "openai.", "meta.", "mistral.", "cohere.", "amazon."}) {
            for (String key : vendoredIds) {
                if (!key.startsWith(vendor)) continue;
                String bare = key.substring(vendor.length());
                if (!book.prices(bare)) continue;
                compared++;
                TokenUsage u = new TokenUsage(1_000_000, 1_000_000, 0, 0);
                if (!book.costOf(key, u)
                        .orElseThrow()
                        .equals(book.costOf(bare, u).orElseThrow())) {
                    mismatches.add(key + " != " + bare);
                }
            }
        }
        assertTrue(compared > 0, "the sweep must actually compare something");
        assertTrue(mismatches.isEmpty(), "vendor prefix changed the price: " + mismatches);
    }

    @Test
    @DisplayName("Anthropic buckets are disjoint and price additively")
    void anthropicShapePricesEachBucketOnce() {
        // claude-sonnet-5, from the vendored snapshot: in 2e-6, out 1e-5, cache-read 2e-7,
        // cache-write 2.5e-6 per token. What this test asserts is the addition, not the specific rate.
        TokenUsage u = new TokenUsage(1_000_000, 1_000_000, 1_000_000, 1_000_000);
        BigDecimal cost = book.costOf("claude-sonnet-5", u).orElseThrow();
        // 2 + 10 + 0.2 + 2.5 = 14.7
        assertEquals(0, new BigDecimal("14.7").compareTo(cost.stripTrailingZeros()), "got " + cost);
    }

    @Test
    @DisplayName("the cache-creation convention is read straight off the rate table")
    void billsCacheCreationReadsTheRateTable() {
        // No Nova generation, at any scope, has ever carried cache_creation_input_token_cost upstream —
        // a hand-maintained guess at one was retired rather than reconciled. tok_cache_write now
        // abstains for Nova turns rather than being scored, which is the correct behavior for a
        // convention this platform genuinely does not know.
        assertFalse(
                book.billsCacheCreation("global.amazon.nova-2-lite-v1:0"),
                "Nova's cache-write rate is a genuine upstream gap, not something to guess at");

        // The reason the question is asked of the rate table at all: the convention differs INSIDE the
        // OpenAI family, in both directions.
        assertTrue(book.billsCacheCreation("gpt-5.6-luna"), "explicit caching: a cache-creation rate");
        assertFalse(book.billsCacheCreation("gpt-4o"), "automatic caching: no cache-creation bucket");
        assertFalse(book.billsCacheCreation("definitely-not-a-real-model-v9"), "unknown convention, no claim");
        assertFalse(book.billsCacheCreation(null));
    }

    @Test
    @DisplayName("summing adds bucket by bucket — the buckets arrive disjoint and stay that way")
    void plusAddsBucketwise() {
        // Both operands are already fresh-only: IngestPricer carved the cache buckets out at write time,
        // and re-deriving that here is what the deleted read-time model-family guess used to do wrong.
        TokenUsage a = new TokenUsage(600, 0, 400, 0);
        TokenUsage b = new TokenUsage(600, 0, 400, 0);
        TokenUsage sum = a.plus(b);
        assertEquals(1200, sum.inputTokens());
        assertEquals(800, sum.cacheReadTokens());
        assertEquals(2000, sum.total());
    }
}
