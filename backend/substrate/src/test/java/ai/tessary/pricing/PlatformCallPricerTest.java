// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.llmspi.ServiceTier;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform lane's half of the one pricing rule the whole system rests on: a cost is either right or
 * absent, and never invented. These are the failure modes that cost real money before — a tier billed at
 * the wrong rate, an unknown model reading as free, a bucket the model is never charged for inflating the
 * total.
 */
class PlatformCallPricerTest {

    private static final String MODEL = "anthropic.claude-sonnet-5";

    /** Per MTok: input $3, output $15, cache-read $0.30, cache-write $3.75. */
    private static final ModelRates RATES = new ModelRates(
            new BigDecimal("3.00"), new BigDecimal("15.00"), new BigDecimal("0.30"), new BigDecimal("3.75"));

    private final PriceBookRepository books = mock(PriceBookRepository.class);
    private final PlatformCallPricer pricer = new PlatformCallPricer(new ModelResolver(books), books);

    private void bookCarries(String modelId, ModelRates rates) {
        when(books.hasModel(modelId)).thenReturn(true);
        when(books.rateFor(modelId)).thenReturn(Optional.of(new ModelRate("litellm-testbook", rates)));
    }

    @Test
    @DisplayName("each bucket is priced at its own rate and the total is their sum, stamped with the book")
    void price_sumsBucketsAndNamesTheBook() {
        bookCarries(MODEL, RATES);

        PlatformCallPricer.PricedCall priced = pricer.price(
                        MODEL, ServiceTier.STANDARD, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
                .orElseThrow();

        assertEquals(0, new BigDecimal("3.00").compareTo(priced.input()));
        assertEquals(0, new BigDecimal("15.00").compareTo(priced.output()));
        assertEquals(0, new BigDecimal("0.30").compareTo(priced.cacheRead()));
        assertEquals(0, new BigDecimal("3.75").compareTo(priced.cacheWrite()));
        assertEquals(0, new BigDecimal("22.05").compareTo(priced.total()));
        // The version is what makes the number auditable later; a cost with no book behind it is a claim.
        assertEquals("litellm-testbook", priced.priceBookVersion());
    }

    @Test
    @DisplayName("a Flex call is billed at half Standard, not silently at the Standard price")
    void price_scalesEveryBucketByTheTierFactor() {
        bookCarries(MODEL, RATES);

        PlatformCallPricer.PricedCall standard = pricer.price(
                        MODEL, ServiceTier.STANDARD, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
                .orElseThrow();
        PlatformCallPricer.PricedCall flex = pricer.price(
                        MODEL, ServiceTier.FLEX, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
                .orElseThrow();

        assertEquals(
                0, standard.total().divide(new BigDecimal("2")).compareTo(flex.total()), "Flex is 50% of Standard");
        // A null tier is Standard, which is what Bedrock serves when the field is absent.
        assertEquals(
                0,
                standard.total()
                        .compareTo(pricer.price(MODEL, null, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
                                .orElseThrow()
                                .total()));
    }

    @Test
    @DisplayName("a tier with no published multiplier is unpriced rather than guessed at Standard")
    void price_priorityTierYieldsNothing() {
        bookCarries(MODEL, RATES);
        // AWS states the Priority premium per model and publishes no multiplier. Billing it at Standard
        // would under-report by exactly that premium, and the under-report would be invisible.
        assertTrue(pricer.price(MODEL, ServiceTier.PRIORITY, 1_000, 1_000, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("a model no book in force carries is unpriced, never $0")
    void price_unknownModelIsEmptyNotFree() {
        when(books.hasModel("some.model-nobody-carries")).thenReturn(false);
        assertTrue(pricer.price("some.model-nobody-carries", ServiceTier.STANDARD, 500_000, 500_000, 0, 0)
                .isEmpty());
        assertTrue(
                pricer.price(null, ServiceTier.STANDARD, 500_000, 500_000, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("a bucket the model is never billed for contributes zero, and does not make the call unpriced")
    void price_nullBucketIsZeroNotUnpriced() {
        // OpenAI's automatic prefix caching has no separate write charge: the bucket is absent, which is a
        // different statement from the model having no rates at all.
        bookCarries(
                "gpt-5.5",
                new ModelRates(new BigDecimal("5.00"), new BigDecimal("30.00"), new BigDecimal("0.50"), null));

        PlatformCallPricer.PricedCall priced = pricer.price("gpt-5.5", ServiceTier.STANDARD, 1_000_000, 0, 0, 1_000_000)
                .orElseThrow();

        assertEquals(0, BigDecimal.ZERO.compareTo(priced.cacheWrite()), "no write rate contributes nothing");
        assertEquals(0, new BigDecimal("5.00").compareTo(priced.total()));
    }

    @Test
    @DisplayName("the resolver's precedence reaches a model reported under a Bedrock inference-profile id")
    void price_resolvesThroughTheSameLegsIngestUses() {
        // The platform calls Bedrock with a full profile id; the book carries the logical name. Before the
        // fallback legs existed this whole lane read as unpriced.
        when(books.hasModel("global.anthropic.claude-sonnet-5")).thenReturn(false);
        bookCarries("anthropic.claude-sonnet-5", RATES);

        assertEquals(
                0,
                new BigDecimal("3.00")
                        .compareTo(pricer.price("global.anthropic.claude-sonnet-5", null, 1_000_000, 0, 0, 0)
                                .orElseThrow()
                                .total()));
    }
}
