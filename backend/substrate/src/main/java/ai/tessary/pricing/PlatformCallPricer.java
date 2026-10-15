// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Prices the platform's own LLM calls from the same {@code price_book} as ingested spans, naming the version. Buckets
 * are summed, so they must not overlap: OpenAI's {@code prompt_tokens} includes cached tokens, and the caller must
 * carve them out. Empty is not zero: a wrong cost is worse than a missing one.
 */
@Component
public class PlatformCallPricer {

    /** Per-MTok rates divided by this to reach per-token. */
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /** A cache-read of a few tokens is sub-micro-dollar; 10dp so nothing rounds to $0. */
    private static final int COST_SCALE = 10;

    private final ModelResolver models;
    private final PriceBookRepository books;

    public PlatformCallPricer(ModelResolver models, PriceBookRepository books) {
        this.models = models;
        this.books = books;
    }

    /** The version travels with the numbers so the cost stays auditable. */
    public record PricedCall(
            String priceBookVersion,
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheRead,
            BigDecimal cacheWrite,
            BigDecimal total) {}

    /**
     * @param modelName resolved through {@link ModelResolver}, the same precedence ingest uses @param inputTokens the
     * BILLABLE input count, cache-read tokens already carved out where the provider folds them in
     */
    public Optional<PricedCall> price(
            @Nullable String modelName,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable Integer cacheReadTokens,
            @Nullable Integer cacheWriteTokens) {
        Optional<ModelRate> found = models.resolve(modelName).flatMap(books::rateFor);
        if (found.isEmpty()) return Optional.empty();

        ModelRate rate = found.get();
        ModelRates rates = rate.rates();
        BigDecimal in = cost(rates.inputPerMtok(), inputTokens);
        BigDecimal out = cost(rates.outputPerMtok(), outputTokens);
        BigDecimal cacheRead = cost(rates.cacheReadPerMtok(), cacheReadTokens);
        BigDecimal cacheWrite = cost(rates.cacheWritePerMtok(), cacheWriteTokens);
        return Optional.of(new PricedCall(
                rate.priceBookVersion(),
                in,
                out,
                cacheRead,
                cacheWrite,
                in.add(out).add(cacheRead).add(cacheWrite)));
    }

    /** Zero for a null rate or no tokens, unlike an empty {@link #price}, where the model has no rate at all. */
    private static BigDecimal cost(@Nullable BigDecimal perMtok, @Nullable Integer tokens) {
        if (perMtok == null || tokens == null || tokens <= 0) return BigDecimal.ZERO;
        return perMtok.multiply(BigDecimal.valueOf(tokens)).divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }
}
