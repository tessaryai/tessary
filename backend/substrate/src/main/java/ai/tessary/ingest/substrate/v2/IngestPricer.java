// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.pricing.ModelRate;
import ai.tessary.pricing.ModelRates;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.storage.SpanRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Priced on arrival, never repriced: provided cost verbatim, else inferred from the book in force, else
 * {@code unpriced} with null costs, never zero. Container kinds carry their subtree's cumulative usage, so their
 * numbers go only to {@code provided_usage}; a denylist of containers, not an allowlist, so a guardrail LLM call's
 * spend is not lost. {@code input_tokens} is cache-inclusive per OTel, so the fresh share is stored, or cached tokens
 * would be billed and counted twice.
 */
@Component
public class IngestPricer {

    private static final Logger log = LoggerFactory.getLogger(IngestPricer.class);

    /** {@code chain} normalizes to {@link KindNormalizer#WORKFLOW} at the ingest edge. */
    private static final Set<String> CONTAINER_KINDS = Set.of(KindNormalizer.AGENT, KindNormalizer.WORKFLOW);

    private static final BigDecimal PER_MTOK = new BigDecimal(1_000_000);

    private static final int COST_SCALE = 12;

    /**
     * Resolving per span is three queries, against a 200-span/second drain budget; minutes of staleness on a book that
     * changes daily costs nothing.
     */
    private static final long CACHE_TTL_NANOS = java.time.Duration.ofMinutes(5).toNanos();

    /** A project fuzzing model names must not turn the cache into a leak. */
    private static final int CACHE_MAX_ENTRIES = 20_000;

    private final ModelResolver models;
    private final PriceBookRepository books;
    private final ObjectMapper mapper;

    private final Map<String, Optional<String>> modelIdByName = new ConcurrentHashMap<>();
    private final Map<String, Optional<ModelRate>> rateByModelId = new ConcurrentHashMap<>();
    private final AtomicLong cacheLoadedAt = new AtomicLong(System.nanoTime());

    /**
     * One bad integration repeats the violation on every span. Cleared on the cache TTL so the warning re-fires while
     * the condition lasts.
     */
    private final Set<String> warnedNonInclusiveModels = ConcurrentHashMap.newKeySet();

    public IngestPricer(ModelResolver models, PriceBookRepository books, ObjectMapper mapper) {
        this.models = models;
        this.books = books;
        this.mapper = mapper;
    }

    /**
     * @param providedUsage an audit copy, never read for arithmetic; the only place a container's cumulative numbers
     * survive
     */
    public record Priced(
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable String inputCost,
            @Nullable String outputCost,
            @Nullable String cacheReadCost,
            @Nullable String cacheWriteCost,
            String costSource,
            @Nullable String priceBookVersion,
            @Nullable String modelId,
            @Nullable String providedUsage) {}

    public Priced price(RawEntry raw, String kind) {
        Map<String, Object> attrs = raw.metadata();
        String receipt = receipt(attrs);
        String modelId = resolveModelId(raw.model());

        if (CONTAINER_KINDS.contains(kind)) {
            return new Priced(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    SpanRow.CostSource.UNPRICED,
                    null,
                    modelId,
                    receipt);
        }

        Long output = longAttr(attrs, GenAiAttributes.USAGE_OUTPUT_TOKENS);
        Long cacheRead = longAttr(attrs, GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS);
        Long cacheWrite = longAttr(attrs, GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS);
        Long reasoning =
                firstLong(attrs, GenAiAttributes.USAGE_REASONING_TOKENS, GenAiAttributes.OI_TOKEN_COUNT_REASONING);
        Long reportedInput = longAttr(attrs, GenAiAttributes.USAGE_INPUT_TOKENS);
        Long input = freshInput(reportedInput, cacheRead, cacheWrite, raw.model());

        ProvidedCost provided = providedCost(attrs);
        if (provided != null) {
            return new Priced(
                    input,
                    output,
                    cacheRead,
                    cacheWrite,
                    reasoning,
                    provided.input(),
                    provided.output(),
                    provided.cacheRead(),
                    provided.cacheWrite(),
                    SpanRow.CostSource.PROVIDED,
                    null,
                    modelId,
                    receipt);
        }

        ModelRate rate = modelId == null ? null : rateFor(modelId);
        boolean anyUsage = input != null || output != null || cacheRead != null || cacheWrite != null;
        if (rate == null || !anyUsage) {
            return new Priced(
                    input,
                    output,
                    cacheRead,
                    cacheWrite,
                    reasoning,
                    null,
                    null,
                    null,
                    null,
                    SpanRow.CostSource.UNPRICED,
                    null,
                    modelId,
                    receipt);
        }

        ModelRates rates = rate.rates();
        return new Priced(
                input,
                output,
                cacheRead,
                cacheWrite,
                reasoning,
                cost(input, rates.inputPerMtok()),
                cost(output, rates.outputPerMtok()),
                cost(cacheRead, rates.cacheReadPerMtok()),
                cost(cacheWrite, rates.cacheWritePerMtok()),
                SpanRow.CostSource.INFERRED,
                rate.priceBookVersion(),
                modelId,
                receipt);
    }

    /**
     * Unconditional: OTel defines {@code input_tokens} as cache-inclusive. Guessing by model name once billed Anthropic
     * traffic at 5.75x. The one sound test is {@code input < cacheRead + cacheWrite}, which proves a spec-violating
     * disjoint sender; the converse proves nothing.
     */
    private @Nullable Long freshInput(
            @Nullable Long input, @Nullable Long cacheRead, @Nullable Long cacheWrite, @Nullable String model) {
        if (input == null) return null;
        long cached = Math.max(0, cacheRead == null ? 0 : cacheRead) + Math.max(0, cacheWrite == null ? 0 : cacheWrite);
        if (cached <= 0) return input;
        if (input < cached) {
            warnNonInclusiveInput(model, input, cached);
            return input;
        }
        return input - cached;
    }

    private void warnNonInclusiveInput(@Nullable String model, long input, long cached) {
        // Sweep here too: a producer that states no model never reaches resolveModelId's cache.
        expireCacheIfStale();
        String key = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (!warnedNonInclusiveModels.add(key)) return;
        StructuredLog.warn(log, Markers.OPS, "ingest.v2.usage.non_inclusive_input")
                .message(
                        "producer reported %s input tokens under %s cached ones for %s, so its input count is not"
                                + " cache-inclusive; leaving it unadjusted",
                        input, cached, model == null ? "an unnamed model" : model)
                .field("model", model)
                .field("inputTokens", input)
                .field("cachedTokens", cached)
                .log();
    }

    /** Null when the producer reported neither; an empty receipt would claim we were sent something. */
    private @Nullable String receipt(@Nullable Map<String, Object> attrs) {
        if (attrs == null) return null;
        ObjectNode node = mapper.createObjectNode();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            String key = e.getKey();
            if (!GenAiAttributes.isUsageOrCostKey(key)) continue;
            Object v = e.getValue();
            switch (v) {
                case Number n -> node.put(key, new BigDecimal(n.toString()));
                case Boolean b -> node.put(key, b);
                case null -> {}
                default -> node.put(key, v.toString());
            }
        }
        return node.isEmpty() ? null : node.toString();
    }

    private record ProvidedCost(
            @Nullable String input,
            @Nullable String output,
            @Nullable String cacheRead,
            @Nullable String cacheWrite) {}

    /**
     * A lone total lands in {@code input_cost} so the generated {@code total_cost} sees it; splitting it ourselves
     * would be a derivation.
     */
    private @Nullable ProvidedCost providedCost(@Nullable Map<String, Object> attrs) {
        if (attrs == null) return null;
        String input = decimalAttr(attrs, GenAiAttributes.OI_COST_PROMPT);
        String output = decimalAttr(attrs, GenAiAttributes.OI_COST_COMPLETION);
        String cacheRead = decimalAttr(attrs, GenAiAttributes.OI_COST_CACHE_READ);
        String cacheWrite = decimalAttr(attrs, GenAiAttributes.OI_COST_CACHE_WRITE);
        if (input != null || output != null || cacheRead != null || cacheWrite != null) {
            return new ProvidedCost(input, output, cacheRead, cacheWrite);
        }
        String total = firstDecimal(
                attrs, GenAiAttributes.USAGE_COST, GenAiAttributes.USAGE_TOTAL_COST, GenAiAttributes.OI_COST_TOTAL);
        return total == null ? null : new ProvidedCost(total, null, null, null);
    }

    /**
     * A null token count is unknown and stays null; a null rate means never billed for that bucket, contributing zero.
     */
    private static @Nullable String cost(@Nullable Long tokens, @Nullable BigDecimal ratePerMtok) {
        if (tokens == null) return null;
        if (ratePerMtok == null)
            return BigDecimal.ZERO
                    .setScale(COST_SCALE, RoundingMode.UNNECESSARY)
                    .toPlainString();
        return BigDecimal.valueOf(tokens)
                .multiply(ratePerMtok)
                .divide(PER_MTOK, COST_SCALE, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private @Nullable String resolveModelId(@Nullable String providedModelName) {
        if (providedModelName == null || providedModelName.isBlank()) return null;
        expireCacheIfStale();
        return modelIdByName
                .computeIfAbsent(providedModelName.trim().toLowerCase(Locale.ROOT), models::resolve)
                .orElse(null);
    }

    private @Nullable ModelRate rateFor(String modelId) {
        expireCacheIfStale();
        return rateByModelId.computeIfAbsent(modelId, books::rateFor).orElse(null);
    }

    /**
     * Wholesale rather than per-entry: the importer replaces a whole book, and per-entry expiry would price one batch
     * against two books.
     */
    private void expireCacheIfStale() {
        long loadedAt = cacheLoadedAt.get();
        boolean expired = System.nanoTime() - loadedAt > CACHE_TTL_NANOS;
        boolean overflowing = modelIdByName.size() > CACHE_MAX_ENTRIES
                || rateByModelId.size() > CACHE_MAX_ENTRIES
                || warnedNonInclusiveModels.size() > CACHE_MAX_ENTRIES;
        if ((expired || overflowing) && cacheLoadedAt.compareAndSet(loadedAt, System.nanoTime())) {
            modelIdByName.clear();
            rateByModelId.clear();
            warnedNonInclusiveModels.clear();
        }
    }

    private static @Nullable Long firstLong(@Nullable Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Long v = longAttr(attrs, key);
            if (v != null) return v;
        }
        return null;
    }

    private static @Nullable Long longAttr(@Nullable Map<String, Object> attrs, String key) {
        Object v = attrs == null ? null : attrs.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable String firstDecimal(@Nullable Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            String v = decimalAttr(attrs, key);
            if (v != null) return v;
        }
        return null;
    }

    /** Money is never carried as a double past this point. */
    private static @Nullable String decimalAttr(@Nullable Map<String, Object> attrs, String key) {
        Object v = attrs == null ? null : attrs.get(key);
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString().trim()).toPlainString();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
