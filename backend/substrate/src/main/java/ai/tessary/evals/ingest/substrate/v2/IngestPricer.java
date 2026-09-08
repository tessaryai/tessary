// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate.v2;

import ai.tessary.evals.ingest.GenAiAttributes;
import ai.tessary.evals.ingest.KindNormalizer;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import ai.tessary.evals.pricing.ModelRate;
import ai.tessary.evals.pricing.ModelRates;
import ai.tessary.evals.pricing.ModelResolver;
import ai.tessary.evals.pricing.PriceBookRepository;
import ai.tessary.evals.storage.SpanRow;
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
 * Turns one arriving span's reported usage into the typed token and cost columns of {@code span}
 * (substrate-model.md §6.5).
 *
 * <h2>Priced on arrival, never repriced</h2>
 *
 * <p>A recorded cost is a fact about what a call was billed at, not a derivation that moves when a rate
 * file is refreshed. The order is fixed:
 *
 * <ol>
 *   <li>The producer sent a cost — store it verbatim, {@code cost_source = 'provided'}, no book stamped.
 *   <li>Otherwise resolve the reported model name and price each bucket against the book in force —
 *       {@code cost_source = 'inferred'}, stamped with the version that produced the number.
 *   <li>Otherwise every cost column stays null and {@code cost_source = 'unpriced'}. <b>Never zero.</b> A
 *       zero would render real spend as free, and the trace rollup counts these so a low total is never
 *       mistaken for a cheap turn.
 * </ol>
 *
 * <h2>Container kinds carry their children's numbers, so they carry none of their own</h2>
 *
 * <p>The producers that report cumulative usage on an {@code agent} span report cumulative COST on it
 * too. §7.2 sums every span of a trace, so storing either verbatim would double-count the whole subtree
 * into {@code trace.total_cost} — permanently, because cost is never repriced. Both are therefore routed
 * to {@code span_payload.provided_usage} as a receipt and the typed columns are left null with
 * {@code cost_source = 'unpriced'}.
 *
 * <p>The rule is a DENYLIST of container kinds rather than an allowlist of {@code llm}/{@code embedding}/
 * {@code reranker}. An allowlist reads the same for every producer seen so far and is wrong the first time
 * a {@code guardrail} span — a moderation LLM call, with real tokens and a real bill — arrives: its spend
 * would vanish from the rollup entirely, and §7.3's promise that {@code unpriced_spans} counts unpriced
 * spend "of any kind" would be false. Only containers double-count, so only containers are stripped.
 *
 * <h2>The cache-inclusive correction happens here</h2>
 *
 * <p>The OTel gen_ai semantic convention defines {@code gen_ai.usage.input_tokens} as the CACHE-INCLUSIVE
 * prompt size — fresh tokens plus everything served from or written to cache — while every rate table
 * prices the three buckets independently. Pricing the reported input as-is therefore bills the cached
 * tokens twice: once at the full input rate and again at their own read/write rate. The correction is
 * applied at WRITE time and the corrected input is what {@code span.input_tokens} stores, because
 * {@code span.total_tokens} sums all five buckets and an uncorrected input double-counts into the token
 * total as well as the bill. The producer's own figures survive verbatim in
 * {@code span_payload.provided_usage}.
 */
@Component
public class IngestPricer {

    private static final Logger log = LoggerFactory.getLogger(IngestPricer.class);

    /**
     * Kinds whose reported usage and cost describe their children rather than themselves. {@code chain}
     * (the OpenInference name) normalizes to {@link KindNormalizer#WORKFLOW} at the ingest edge, so the
     * two entries here are the same two the implementation plan names.
     */
    private static final Set<String> CONTAINER_KINDS = Set.of(KindNormalizer.AGENT, KindNormalizer.WORKFLOW);

    private static final BigDecimal PER_MTOK = new BigDecimal(1_000_000);

    /** {@code numeric(18,12)} is the column; rounding at the column's own scale keeps the write lossless. */
    private static final int COST_SCALE = 12;

    /**
     * How long a resolved model id or rate is trusted before it is looked up again. Price books change on
     * a boot or a daily import, so minutes of staleness costs nothing — whereas resolving per span is
     * three queries per span, and the drain has a 200-span/second budget to keep. The bound on staleness
     * is what a newly imported book waits before it prices anything.
     */
    private static final long CACHE_TTL_NANOS = java.time.Duration.ofMinutes(5).toNanos();

    /** Cache ceiling. A project fuzzing model names must not turn the cache into a leak. */
    private static final int CACHE_MAX_ENTRIES = 20_000;

    private final ModelResolver models;
    private final PriceBookRepository books;
    private final ObjectMapper mapper;

    private final Map<String, Optional<String>> modelIdByName = new ConcurrentHashMap<>();
    private final Map<String, Optional<ModelRate>> rateByModelId = new ConcurrentHashMap<>();
    private final AtomicLong cacheLoadedAt = new AtomicLong(System.nanoTime());

    /**
     * Models already warned about for reporting a spec-violating input count. A misbehaving producer
     * sends the same violation on every one of its spans, so without this one bad integration would be
     * the log volume of the whole drain; the set is cleared on the same TTL as the rate caches, so the
     * warning re-fires while the condition lasts instead of going silent forever.
     */
    private final Set<String> warnedNonInclusiveModels = ConcurrentHashMap.newKeySet();

    public IngestPricer(ModelResolver models, PriceBookRepository books, ObjectMapper mapper) {
        this.models = models;
        this.books = books;
        this.mapper = mapper;
    }

    /**
     * The typed usage and cost columns for one span, plus the {@code span_payload.provided_usage} receipt.
     *
     * @param providedUsage the producer's raw usage/cost object, kept verbatim as an audit copy and never
     *     read for arithmetic. It is the only place a container kind's cumulative numbers survive.
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

    /** Price one span. {@code kind} is the normalized {@link KindNormalizer} kind already resolved for it. */
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

    // ----- usage ------------------------------------------------------------------------------------

    /**
     * The FRESH share of the reported prompt: {@code gen_ai.usage.input_tokens} with both cache buckets
     * carved out, so the five buckets on {@code span} are disjoint and summing or pricing them is honest.
     *
     * <p><b>Unconditional, because the wire contract says so.</b> The OTel gen_ai convention defines
     * {@code input_tokens} as cache-INCLUSIVE, and every producer reaching this class speaks OTLP carrying
     * those keys. An earlier version instead guessed the convention from the model name and subtracted
     * only the cache-READ bucket, which left Anthropic traffic billed at 5.75x: the whole inclusive input
     * priced at the full rate, then both cache buckets priced again on top.
     *
     * <p>The one test kept is the only one that is logically sound. A total cannot be smaller than its
     * parts, so {@code input < cacheRead + cacheWrite} proves the producer is NOT using the inclusive
     * convention and is sending disjoint buckets in violation of the spec; subtracting there would strip
     * real fresh tokens. The converse is not a test — a spec-violating disjoint sender with a large fresh
     * prompt also satisfies {@code input >= read + write}, so treating that as evidence of inclusiveness
     * would misprice exactly the traffic the guard exists to protect.
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
        // The warn-once set is cleared by the same TTL as the rate caches, so the line re-fires while the
        // condition lasts. Sweep from here too: a producer that states no model never reaches
        // resolveModelId's cache, and would otherwise warn once at boot and stay silent forever.
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

    /**
     * The producer's raw usage and cost keys, verbatim, for {@code span_payload.provided_usage}. Null when
     * the producer reported neither — an empty receipt would claim we were sent something we were not.
     */
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

    // ----- provided cost ----------------------------------------------------------------------------

    private record ProvidedCost(
            @Nullable String input,
            @Nullable String output,
            @Nullable String cacheRead,
            @Nullable String cacheWrite) {}

    /**
     * The producer's own cost figures, or null when it reported none.
     *
     * <p>Per-bucket attributes win when present. <b>A lone total lands in {@code input_cost}</b>, because
     * {@code span.total_cost} is a generated sum of the four buckets and an undecomposed total has to be
     * visible to it somewhere; splitting it ourselves would be a derivation, and §6.5 rule 1 says verbatim.
     * The split is the producer's to state, {@code cost_source = 'provided'} says whose number it is, and
     * every surface that matters reads the total.
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

    // ----- inferred cost ----------------------------------------------------------------------------

    /**
     * One bucket's cost. A null token count means the producer never reported that bucket, so its cost is
     * unknown and stays null; a null RATE means this model is never billed for that bucket, which
     * contributes exactly zero (spec §5.3).
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

    // ----- resolution cache -------------------------------------------------------------------------

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
     * Drop both caches once the TTL is up, or once either has grown past the ceiling. Wholesale rather
     * than per-entry: the importer replaces a whole book at a time, so an entry-level expiry would let one
     * batch price half its spans against the old book and half against the new.
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

    // ----- attribute readers ------------------------------------------------------------------------

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

    /** A cost attribute as an exact decimal string. Money is never carried as a double past this point. */
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
