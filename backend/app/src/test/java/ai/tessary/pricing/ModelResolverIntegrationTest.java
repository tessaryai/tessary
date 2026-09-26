// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Resolution precedence against the imported books. Each case is a rule a mis-priced project paid for; moving the
 * lookup into the database changed none of them.
 */
@SpringBootTest
// Own context: a model row written by another class would change the books.
@TestPropertySource(properties = "test.context-group=model-resolver")
class ModelResolverIntegrationTest {

    @Autowired
    ModelResolver resolver;

    @Autowired
    PriceBookRepository books;

    @Test
    @DisplayName("an id the snapshot carries verbatim resolves to itself")
    void resolve_exactMatchWins() {
        assertEquals(Optional.of("claude-sonnet-5"), resolver.resolve("claude-sonnet-5"));
        // Case and whitespace are producer noise.
        assertEquals(Optional.of("claude-sonnet-5"), resolver.resolve("  Claude-Sonnet-5 "));
        // Both regional profiles are carried verbatim.
        assertEquals(
                Optional.of("global.anthropic.claude-sonnet-5"), resolver.resolve("global.anthropic.claude-sonnet-5"));
        assertEquals(Optional.of("us.anthropic.claude-sonnet-5"), resolver.resolve("us.anthropic.claude-sonnet-5"));
    }

    @Test
    @DisplayName("an undated Bedrock id falls back through the vendor prefix")
    void resolve_vendorPrefixStripped() {
        // The snapshot lacks this real producer spelling; before the vendor strip, a whole project's spend read as
        // unpriced.
        assertEquals(Optional.of("claude-haiku-4-5"), resolver.resolve("anthropic.claude-haiku-4-5"));
    }

    @Test
    @DisplayName("a region-prefixed id is never resolved to the cheaper non-regional model")
    void resolve_regionPrefixStopsTheFallback() {
        // Bedrock regional prefixes cost +10% (us-gov +20%). Stripping both prefixes would price at 1.00 instead of
        // 1.10, a silent under-report worse than unpriced.
        assertEquals(Optional.empty(), resolver.resolve("us.anthropic.claude-haiku-4-5"));
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

    @Test
    @DisplayName("the memoised check answers a reported name the way resolve then billsCacheCreation does")
    void reportedModelBillsCacheCreation_matchesTheUnmemoisedAnswer() {
        for (String reported : new String[] {
            "claude-sonnet-5", "anthropic.claude-haiku-4-5", "GPT-4o", "definitely-not-a-real-model-v9"
        }) {
            boolean direct =
                    resolver.resolve(reported).map(resolver::billsCacheCreation).orElse(false);
            assertEquals(direct, resolver.reportedModelBillsCacheCreation(reported), reported);
            assertEquals(direct, resolver.reportedModelBillsCacheCreation(reported), reported + ", memoised");
        }
        assertFalse(resolver.reportedModelBillsCacheCreation(null));
        assertFalse(resolver.reportedModelBillsCacheCreation("  "));
    }
}
