// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import ai.tessary.pricing.PriceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Per-token USD rates for the models customers run, read from the same checked-in vendored LiteLLM
 * snapshot the {@code price_book} table is imported from ({@code resources/pricing/litellm-model-prices.json}).
 *
 * <p><b>What is left of this class, and why.</b> It was the read-time price book for customer traffic,
 * against a hand-maintained catalog that priced only the handful of models the PLATFORM calls — a third of
 * production calls were on models that catalog did not list, and the gap was Opus, the expensive one. Both
 * of those jobs are gone: ingested spans are priced on arrival against the versioned {@code price_book}
 * and never repriced, and the platform lane moved onto the same book.
 * The ONE reader left is {@code classifier/metric/MetricSource}'s {@link #billsCacheCreation}, which asks
 * a question about billing CONVENTION rather than about dollars and asks it once per leaf span inside a
 * sweep — an in-memory map read, where the book's repository would be three queries. Retiring this class
 * means giving {@code PriceBookRepository} a cached convention lookup; until then it reads the same file,
 * so the two cannot disagree about which models carry a cache-creation rate.
 *
 * <p><b>A second hand-maintained file used to layer corrections on top of this one</b>, including a
 * {@code global.amazon.nova-2-lite-v1:0} cache-creation rate the vendored snapshot has never carried — no
 * Nova generation, at any scope, has ever published one upstream. That override was an unverified guess,
 * not a sourced correction, so it was retired rather than reconciled: Nova cache
 * writes now read as unpriced here and in {@code pricing/ModelResolver#billsCacheCreation}, which agree
 * because both now read this same single file.
 *
 * <p><b>Bucket semantics.</b> Each bucket is priced independently and summed, which is only correct
 * when the counts are non-overlapping. Every {@link TokenUsage} reaching here already is: the
 * cache-inclusive prompt count the OTel convention defines is carved into fresh + cached at ingest
 * (substrate-model.md §6.5), which is the same correction {@code LlmCaller} applies on the platform
 * side, for the same reason.
 *
 * <p><b>Unpriced is null, never zero.</b> A model absent from the book yields an empty cost, and the
 * read surface counts those calls separately. Pricing an unknown model at $0 would render a real
 * spend as free, which is the one failure mode that makes the number worse than not showing it.
 */
@Component
public class TokenPriceBook {

    private static final Logger log = LoggerFactory.getLogger(TokenPriceBook.class);

    /** USD is fractional to the cent, but a cache-read of a few tokens is sub-micro-dollar. */
    private static final int COST_SCALE = 10;

    /**
     * Bedrock and Vertex prefix the same model with a region or routing scope
     * ({@code global.anthropic.claude-sonnet-5}, {@code us.anthropic.…}). The book carries most of
     * these verbatim, so they are only stripped as a fallback after an exact lookup misses.
     */
    private static final String[] SCOPE_PREFIXES = {
        "global.", "us.", "eu.", "apac.", "au.", "jp.", "ca.", "sa.", "us-gov."
    };

    /**
     * Vendor prefixes, stripped after the region prefix and only once an exact lookup has missed.
     *
     * <p>Producers emit undated Bedrock-style ids the book does not always carry verbatim:
     * {@code anthropic.claude-haiku-4-5} is absent while both {@code claude-haiku-4-5} and the dated
     * {@code anthropic.claude-haiku-4-5-20251001-v1:0} are present, so a real project's whole spend
     * read as unpriced.
     *
     * <p>Safe ONLY for the vendor prefix, and only when no region prefix was consumed first. A vendor
     * prefix is a naming convention: across every vendor-prefixed key in the book that has a bare
     * counterpart, none differs in price. A REGION prefix is a pricing dimension — Bedrock charges a
     * regional premium, +10% for {@code us./eu./au./jp.} and +20% for {@code us-gov.}, on 28 keys.
     * Stripping both would resolve {@code us.anthropic.claude-haiku-4-5} to the non-regional rate and
     * under-report that spend by 10%, silently. An honest unpriced beats a quietly wrong number — the
     * whole reason {@link #costOf} returns empty rather than zero.
     */
    private static final String[] VENDOR_PREFIXES = {"anthropic.", "openai.", "meta.", "mistral.", "cohere.", "amazon."
    };

    private final Map<String, Rates> rates;

    public TokenPriceBook(ObjectMapper mapper) {
        this.rates = load(mapper);
    }

    private static Map<String, Rates> load(ObjectMapper mapper) {
        Map<String, Rates> out = new HashMap<>();
        readInto(mapper, PriceSnapshot.LITELLM_RESOURCE, out);
        return Map.copyOf(out);
    }

    /**
     * Read the vendored rate file into {@code out}.
     *
     * <p>A file that is missing or corrupt is logged and skipped rather than thrown: an unreadable book
     * must not stop the app booting, and the models it would have carried simply read as unpriced — which
     * the read surface already renders honestly as an unpriced count.
     */
    private static void readInto(ObjectMapper mapper, String resource, Map<String, Rates> out) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            root.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                BigDecimal input = dec(v, "input_cost_per_token");
                BigDecimal output = dec(v, "output_cost_per_token");
                if (!hasTokenRate(input, output)) return;
                out.put(
                        e.getKey().toLowerCase(Locale.ROOT),
                        new Rates(
                                input,
                                output,
                                dec(v, "cache_read_input_token_cost"),
                                dec(v, "cache_creation_input_token_cost")));
            });
        } catch (IOException e) {
            log.warn("model price book unreadable at {}; the models it carries will read as unpriced", resource, e);
        }
    }

    /**
     * Whether an entry prices tokens at all. An entry carrying neither an input nor an output rate is
     * NOT in this book: {@link #prices} must answer false for it and {@link #costOf} must answer empty,
     * because the alternative is worse than silence. Every rate bucket would be null, {@code cost} would
     * add four zeroes, and the call would report a confident $0.00 — "this was free" — where the truth is
     * "we have no rate for this". The unpriced-call count, which exists to surface exactly that gap, would
     * stop counting it.
     *
     * <p>Upstream carries about 740 such entries out of 3,500: image and audio models priced per image or
     * per second rather than per token ({@code dall-e-3}, {@code stable-diffusion-xl}, {@code flux-pro}),
     * plus LiteLLM's own {@code sample_spec} documentation stub. Until 2026-09-02 they never reached this
     * parser because {@code scripts/refresh-model-prices.sh} filtered them out while trimming the vendored
     * file to four fields. That script now vendors upstream verbatim, so the invariant has to hold here
     * instead — which is where it always belonged, since it is a property of what this book means rather
     * than of how the file was produced. {@link ai.tessary.pricing.PriceSnapshot} applies the same
     * rule on the DB-import side; the two must agree, or a model priced in one path reads as unpriced in
     * the other.
     *
     * <p>Cache rates alone are deliberately not enough. A cache-read rate with no input rate cannot price
     * an ordinary call, so treating it as priced would reintroduce the same false zero for every
     * non-cached request.
     */
    private static boolean hasTokenRate(@Nullable BigDecimal input, @Nullable BigDecimal output) {
        return (input != null && input.signum() != 0) || (output != null && output.signum() != 0);
    }

    private static @Nullable BigDecimal dec(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : new BigDecimal(v.asText());
    }

    /** How many models the book prices — surfaced so a silently-empty book is visible in tests/ops. */
    public int size() {
        return rates.size();
    }

    /** Every id the book prices, lowercased. Lets a test assert a property of the WHOLE book. */
    public java.util.Set<String> modelIds() {
        return rates.keySet();
    }

    /**
     * The USD cost of one generation, or empty when the model carries no published rate.
     *
     * @param model the model string exactly as the producer reported it
     */
    public Optional<BigDecimal> costOf(@Nullable String model, TokenUsage usage) {
        return lookup(model).map(r -> r.cost(usage));
    }

    /** Whether the book can price this model at all — the unpriced-call count reads this. */
    public boolean prices(@Nullable String model) {
        return lookup(model).isPresent();
    }

    /**
     * Whether this model is billed for cache <b>creation</b> — i.e. whether writing to its prompt cache
     * is a charged, and therefore reported, quantity at all.
     *
     * <p>The metric-drift {@code tok_cache_write} measure reads this to decide whether a missing write
     * count means "no writes" or "not measured"
     * ({@code classifiers/metric_drift/PROGRAM.md} §3.3). Those are different sentences and only one of
     * them is a number: a call site that switched provider would otherwise show a clean collapse in cache
     * writes for purely bookkeeping reasons.
     *
     * <p><b>Why the price book answers this and a provider-family list does not.</b> The obvious rule is
     * "Anthropic and Bedrock bill cache creation explicitly, OpenAI's automatic caching does not", and it
     * is the rule PROGRAM.md was written with — but measured against this snapshot it is wrong inside a
     * single vendor and in both directions at once. 19 OpenAI-family keys carry a
     * {@code cache_creation_input_token_cost} ({@code gpt-5.6} and its siblings, whose caching is
     * explicit) while 438 do not ({@code gpt-4o}, {@code o3}, whose caching is automatic); Gemini carries
     * none on any of its 125 keys, because it bills cache storage per hour rather than per written token.
     * A hand-kept family predicate would have to be re-derived every time a vendor changes convention, and
     * the convention just changed. The rate table is the record of who is billed, it is refreshed as a
     * reviewable diff by {@code scripts/refresh-model-prices.sh}, and it is already the thing this class
     * exists to be the authority on.
     *
     * <p>A model the book does not carry answers {@code false} — unknown convention, so no claim. That
     * turn's cost already abstains as {@code UNPRICED_MODEL}, so this only keeps the decomposition from
     * saying something the book cannot support.
     *
     * <p>A rate of exactly zero also answers {@code false}, matching {@link Rates}' own reading that a
     * bucket with no rate is a bucket never billed. Nine gateway-routed OpenAI keys carry a literal
     * {@code 0.0} there, which records "writing this cache is free" rather than "writes are counted".
     */
    public boolean billsCacheCreation(@Nullable String model) {
        return lookup(model)
                .map(r -> {
                    BigDecimal write = r.cacheWrite();
                    return write != null && write.signum() > 0;
                })
                .orElse(false);
    }

    private Optional<Rates> lookup(@Nullable String model) {
        if (model == null || model.isBlank()) return Optional.empty();
        String key = model.trim().toLowerCase(Locale.ROOT);

        // Exact always wins — the book is the authority wherever it carries the id verbatim.
        Rates exact = rates.get(key);
        if (exact != null) return Optional.of(exact);

        // A region prefix carries a PRICE PREMIUM (+10% for us./eu./au./jp., +20% for us-gov., on 28
        // keys), so once one is consumed only a key that still carries it can be trusted. Fall to the
        // region-stripped id and STOP: dropping the vendor too would land on the cheaper non-regional
        // rate and under-report that spend silently, which is worse than the unpriced-and-counted it
        // would replace.
        String scoped = strip(key, SCOPE_PREFIXES);
        if (!scoped.equals(key)) return Optional.ofNullable(rates.get(scoped));

        // No region prefix, so the vendor prefix is pure naming and stripping it cannot change price.
        return Optional.ofNullable(rates.get(strip(key, VENDOR_PREFIXES)));
    }

    /** {@code key} with the first matching prefix removed, or unchanged when none matches. */
    private static String strip(String key, String[] prefixes) {
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) return key.substring(prefix.length());
        }
        return key;
    }

    /** Per-token USD rates for one model. A null bucket means the model is never billed for it. */
    private record Rates(
            @Nullable BigDecimal input,
            @Nullable BigDecimal output,
            @Nullable BigDecimal cacheRead,
            @Nullable BigDecimal cacheWrite) {

        BigDecimal cost(TokenUsage u) {
            return bucket(input, u.inputTokens())
                    .add(bucket(output, u.outputTokens()))
                    .add(bucket(cacheRead, u.cacheReadTokens()))
                    .add(bucket(cacheWrite, u.cacheWriteTokens()))
                    .setScale(COST_SCALE, java.math.RoundingMode.HALF_UP);
        }

        private static BigDecimal bucket(@Nullable BigDecimal perToken, long tokens) {
            if (perToken == null || tokens <= 0) return BigDecimal.ZERO;
            return perToken.multiply(BigDecimal.valueOf(tokens));
        }
    }
}
