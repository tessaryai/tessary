// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pricing;

import ai.tessary.evals.llmspi.ServiceTier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What one of the PLATFORM's own LLM calls cost, priced from the same {@code price_book} an ingested span
 * is priced from.
 *
 * <p><b>Why this exists at all.</b> The platform used to price its own spend from a hand-maintained table
 * compiled into the jar, while producer telemetry priced from the versioned book — two rate sources, one
 * of which no row could point at. A rate change to the jar table silently restated every historical
 * figure derived from it, and the two sources had drifted: measured at the flip, the mantle GPT-5.6 line
 * resolved through the book to OpenAI-DIRECT rates about 4.5x its real bill, and three more models
 * disagreed outright. Both lanes now read one book, and every row the platform prices names the version
 * that priced it.
 *
 * <p><b>Buckets are priced independently and summed, so they must not overlap.</b> Bedrock and Anthropic
 * satisfy that natively — their reported input count already excludes both cache buckets. OpenAI's does
 * not: its {@code prompt_tokens} INCLUDES the cached-prompt tokens, so a caller must carve the cache-read
 * tokens out of the input count before calling in, or those tokens are billed twice (full input rate plus
 * the discounted cache-read rate). That carve-out stays with the caller, where the provider's token
 * semantics are known ({@code LlmCaller.uncachedInput}); this class is a pure per-bucket table read.
 *
 * <p><b>Empty is a first-class answer, and it is not zero.</b> No model resolved, no book in force
 * carrying a rate, or a tier with no published factor all yield empty, and the caller writes NULL. A wrong
 * cost is worse than a missing one, because only the missing one is visible as missing.
 */
@Component
public class PlatformCallPricer {

    /** Per-MTok rates divided by this to reach per-token. */
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /** USD is fractional to the cent, but a cache-read of a few tokens is sub-micro-dollar; keep 10dp so nothing rounds to $0. */
    private static final int COST_SCALE = 10;

    private final ModelResolver models;
    private final PriceBookRepository books;

    public PlatformCallPricer(ModelResolver models, PriceBookRepository books) {
        this.models = models;
        this.books = books;
    }

    /**
     * One call's USD cost split by bucket, plus the summed {@code total} and the book that produced it.
     *
     * <p>The version travels with the numbers because a cost is only auditable if the book behind it is
     * named — with a manual correction book layered over the vendored snapshot, which one priced a given
     * model is not deducible from the model id alone.
     */
    public record PricedCall(
            String priceBookVersion,
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheRead,
            BigDecimal cacheWrite,
            BigDecimal total) {}

    /**
     * Price one completed call, or empty when it cannot be priced honestly.
     *
     * @param modelName the model string as the caller resolved it (a Bedrock inference-profile id, a bare
     *     OpenAI id, …) — resolved to a {@code model.id} by {@link ModelResolver}'s exact → region-strip →
     *     vendor-strip precedence, the same one ingest uses
     * @param tier the tier the call was SERVED at; its {@link ServiceTier#priceFactor()} scales every
     *     bucket, so a Flex call is billed and reported at half Standard rather than silently at full
     *     price. A null tier prices at Standard; {@link ServiceTier#PRIORITY}, whose premium AWS states
     *     per model and does not publish as a multiplier, yields empty
     * @param inputTokens the BILLABLE input count — cache-read tokens already carved out where the
     *     provider folds them in (see the class javadoc)
     */
    public Optional<PricedCall> price(
            @Nullable String modelName,
            @Nullable ServiceTier tier,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable Integer cacheReadTokens,
            @Nullable Integer cacheWriteTokens) {
        BigDecimal factor = tier == null ? BigDecimal.ONE : tier.priceFactor();
        if (factor == null) return Optional.empty();
        Optional<ModelRate> found = models.resolve(modelName).flatMap(books::rateFor);
        if (found.isEmpty()) return Optional.empty();

        ModelRate rate = found.get();
        ModelRates rates = rate.rates();
        BigDecimal in = cost(rates.inputPerMtok(), inputTokens, factor);
        BigDecimal out = cost(rates.outputPerMtok(), outputTokens, factor);
        BigDecimal cacheRead = cost(rates.cacheReadPerMtok(), cacheReadTokens, factor);
        BigDecimal cacheWrite = cost(rates.cacheWritePerMtok(), cacheWriteTokens, factor);
        return Optional.of(new PricedCall(
                rate.priceBookVersion(),
                in,
                out,
                cacheRead,
                cacheWrite,
                in.add(out).add(cacheRead).add(cacheWrite)));
    }

    /**
     * One bucket's USD cost. Zero when the model is never billed for that bucket (a null rate) or nothing
     * was consumed — which is a different statement from the empty {@link #price} above, where no rate for
     * the MODEL exists at all and the whole call is unpriced.
     */
    private static BigDecimal cost(@Nullable BigDecimal perMtok, @Nullable Integer tokens, BigDecimal factor) {
        if (perMtok == null || tokens == null || tokens <= 0) return BigDecimal.ZERO;
        return perMtok.multiply(factor)
                .multiply(BigDecimal.valueOf(tokens))
                .divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }
}
