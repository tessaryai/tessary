// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Resolution precedence, against the imported books rather than an in-memory map. These are the cases the
 * read-time price book was carrying: each one is a rule that was paid for by a mis-priced project, and the
 * point of asserting them here is that moving the lookup into the database changed none of them.
 */
@SpringBootTest
class ModelResolverIntegrationTest {

    @Autowired
    ModelResolver resolver;

    @Autowired
    PriceBookRepository books;

    @Test
    @DisplayName("an id the snapshot carries verbatim resolves to itself")
    void resolve_exactMatchWins() {
        assertEquals(Optional.of("claude-sonnet-5"), resolver.resolve("claude-sonnet-5"));
        // Case and surrounding whitespace are producer noise, not identity.
        assertEquals(Optional.of("claude-sonnet-5"), resolver.resolve("  Claude-Sonnet-5 "));
        // Both regional profiles are carried verbatim, so neither takes a fallback.
        assertEquals(
                Optional.of("global.anthropic.claude-sonnet-5"), resolver.resolve("global.anthropic.claude-sonnet-5"));
        assertEquals(Optional.of("us.anthropic.claude-sonnet-5"), resolver.resolve("us.anthropic.claude-sonnet-5"));
    }

    @Test
    @DisplayName("an undated Bedrock id falls back through the vendor prefix")
    void resolve_vendorPrefixStripped() {
        // The snapshot carries `claude-haiku-4-5` and the dated `anthropic.claude-haiku-4-5-20251001-v1:0`
        // but NOT this spelling, which a real producer emits — before the vendor strip an entire project's
        // spend read as unpriced.
        assertEquals(Optional.of("claude-haiku-4-5"), resolver.resolve("anthropic.claude-haiku-4-5"));
    }

    @Test
    @DisplayName("a region-prefixed id is never resolved to the cheaper non-regional model")
    void resolve_regionPrefixStopsTheFallback() {
        // Bedrock charges a regional premium: us./eu./au./jp. are +10% and us-gov. +20% over the bare
        // model. `us.anthropic.claude-haiku-4-5` is absent verbatim, and stripping BOTH prefixes would land
        // on claude-haiku-4-5 at 1.00 per 1M when the regional rate is 1.10 — a silent 10% under-report,
        // worse than the unpriced-and-counted it would replace.
        assertEquals(Optional.empty(), resolver.resolve("us.anthropic.claude-haiku-4-5"));
        // The exact regional id still resolves, from its own entry.
        assertEquals(
                Optional.of("us.anthropic.claude-haiku-4-5-20251001-v1:0"),
                resolver.resolve("us.anthropic.claude-haiku-4-5-20251001-v1:0"));
    }

    @Test
    @DisplayName("an unknown model resolves to nothing and holds no rate — never a zero one")
    void resolve_unknownModelIsUnpricedNotFree() {
        assertEquals(Optional.empty(), resolver.resolve("definitely-not-a-real-model-v9"));
        assertEquals(Optional.empty(), resolver.resolve(null));
        assertEquals(Optional.empty(), resolver.resolve("   "));
        assertTrue(
                books.rateFor("definitely-not-a-real-model-v9").isEmpty(),
                "no rate at all — a model priced at $0 renders a real spend as free");
    }

    @Test
    @DisplayName("cache-creation billing is read from the rate, not from a provider family")
    void billsCacheCreation_readsTheRateTable() {
        assertTrue(resolver.billsCacheCreation("claude-sonnet-5"), "carries a cache-creation rate");
        assertFalse(resolver.billsCacheCreation("gpt-4o"), "automatic caching: no cache-creation bucket");
        assertFalse(resolver.billsCacheCreation("definitely-not-a-real-model-v9"), "unknown convention, no claim");
        assertFalse(resolver.billsCacheCreation(null));
    }
}
