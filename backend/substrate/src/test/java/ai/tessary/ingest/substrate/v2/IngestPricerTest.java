// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.pricing.ModelRate;
import ai.tessary.pricing.ModelRates;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.storage.SpanRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Ingest-time pricing of one span. The resolver and the book are database reads, so they are fed canned
 * answers here; everything asserted is the arithmetic and bookkeeping this class does with them.
 */
@ExtendWith(MockitoExtension.class)
class IngestPricerTest {

    @Mock
    ModelResolver models;

    @Mock
    PriceBookRepository books;

    private IngestPricer pricer() {
        return new IngestPricer(models, books, new ObjectMapper());
    }

    /**
     * Cached tokens come off a cache-inclusive input count before it is priced, a count sent as text is read
     * like a number, and a bucket the model is never billed for costs exactly zero rather than nothing.
     */
    @Test
    void inferredCostPricesEachBucketAndABucketWithNoRateCostsZero() {
        when(models.resolve("claude-x")).thenReturn(Optional.of("claude-x-id"));
        when(books.rateFor("claude-x-id"))
                .thenReturn(Optional.of(new ModelRate(
                        "book-1",
                        new ModelRates(new BigDecimal("3"), new BigDecimal("15"), new BigDecimal("0.3"), null))));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(GenAiAttributes.USAGE_INPUT_TOKENS, 1500L);
        attrs.put(GenAiAttributes.USAGE_OUTPUT_TOKENS, " 100 ");
        attrs.put(GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, 200L);
        attrs.put(GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS, 300L);
        attrs.put(GenAiAttributes.USAGE_REASONING_TOKENS, "many");

        IngestPricer.Priced priced = pricer().price(raw("claude-x", attrs), KindNormalizer.LLM);

        // 1500 reported - (200 + 300) cached = 1000 fresh: 1000 x $3/MTok, 100 x $15/MTok, 200 x $0.3/MTok.
        assertEquals(
                new IngestPricer.Priced(
                        1000L,
                        100L,
                        200L,
                        300L,
                        null,
                        "0.003000000000",
                        "0.001500000000",
                        "0.000060000000",
                        "0.000000000000",
                        SpanRow.CostSource.INFERRED,
                        "book-1",
                        "claude-x-id",
                        "{\"gen_ai.usage.input_tokens\":1500,\"gen_ai.usage.output_tokens\":\" 100 \","
                                + "\"gen_ai.usage.cache_read.input_tokens\":200,"
                                + "\"gen_ai.usage.cache_creation.input_tokens\":300,"
                                + "\"gen_ai.usage.reasoning_tokens\":\"many\"}"),
                priced);
    }

    /**
     * A producer's per-bucket costs are taken verbatim over any total, an unreadable one is left empty
     * rather than guessed, and the usage receipt keeps a flag as a flag and drops an absent value.
     */
    @Test
    void providedPerBucketCostsAreTakenVerbatim() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(GenAiAttributes.OI_COST_PROMPT, "0.01");
        attrs.put(GenAiAttributes.OI_COST_COMPLETION, 0.02);
        attrs.put(GenAiAttributes.OI_COST_CACHE_READ, "n/a");
        attrs.put(GenAiAttributes.USAGE_COST, "9.99");
        attrs.put("gen_ai.usage.is_estimate", true);
        attrs.put(GenAiAttributes.USAGE_OUTPUT_TOKENS, null);

        IngestPricer.Priced priced = pricer().price(raw(null, attrs), KindNormalizer.LLM);

        assertEquals(
                new IngestPricer.Priced(
                        null,
                        null,
                        null,
                        null,
                        null,
                        "0.01",
                        "0.02",
                        null,
                        null,
                        SpanRow.CostSource.PROVIDED,
                        null,
                        null,
                        "{\"llm.cost.prompt\":\"0.01\",\"llm.cost.completion\":0.02,"
                                + "\"llm.cost.prompt_details.cache_read\":\"n/a\",\"gen_ai.usage.cost\":\"9.99\","
                                + "\"gen_ai.usage.is_estimate\":true}"),
                priced);
    }

    /**
     * The name-to-model cache is bounded: a producer stamping a fresh model string on every span cannot grow
     * it without limit, and once it overflows it is dropped, so a name resolves against the book again.
     */
    @Test
    void theModelCacheIsDroppedOnceItOverflowsAndNamesResolveAfresh() {
        AtomicInteger generation = new AtomicInteger();
        when(models.resolve(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return name.startsWith("filler-") ? Optional.empty() : Optional.of(name + "@" + generation.get());
        });
        when(books.rateFor(anyString())).thenReturn(Optional.empty());
        IngestPricer pricer = pricer();

        assertEquals("m@0", pricer.price(raw("m", Map.of()), KindNormalizer.LLM).modelId());
        generation.incrementAndGet();
        assertEquals(
                "m@0",
                pricer.price(raw("m", Map.of()), KindNormalizer.LLM).modelId(),
                "a cached name is not looked up again while the cache holds");

        IntStream.rangeClosed(0, 20_000).forEach(i -> pricer.price(raw("filler-" + i, Map.of()), KindNormalizer.LLM));

        assertEquals("m@1", pricer.price(raw("m", Map.of()), KindNormalizer.LLM).modelId());
    }

    private static RawEntry raw(@Nullable String model, Map<String, Object> attrs) {
        return new RawEntry(
                "s", "n", null, null, model, new LinkedHashMap<>(attrs), null, "t", "2026-08-12T10:00:00Z", null);
    }
}
