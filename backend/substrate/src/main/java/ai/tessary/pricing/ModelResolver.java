// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns the model string a producer reported into a {@code model.id}, or nothing.
 *
 * <p>The precedence is exact, then region-stripped, then vendor-stripped, lifted unchanged from
 * the read-time price book it replaces, because the two rules it encodes were each paid for by a
 * real mis-priced project.
 *
 * <p><b>Exact always wins.</b> Wherever the book carries an id verbatim, that entry is the
 * authority.
 *
 * <p><b>A region prefix is a pricing dimension, so stripping it stops there.</b> Bedrock and
 * Vertex prefix the same model with a routing scope ({@code global.anthropic.claude-sonnet-5},
 * {@code us.anthropic....}), and the regional profiles bill a premium: +10% for
 * {@code us./eu./au./jp.} and +20% for {@code us-gov.}, on 28 keys in the current snapshot. Once
 * a region prefix has been consumed, only a key that still carries it can be trusted; falling
 * further to the bare model would resolve {@code us.anthropic.claude-haiku-4-5} to the
 * non-regional rate and under-report that spend by 10%, silently. Unresolved is the better
 * answer: the substrate records it as unpriced and counts it.
 *
 * <p><b>A vendor prefix is naming, so stripping it is safe, but only when no region prefix was
 * consumed first.</b> Producers emit undated Bedrock-style ids the snapshot does not always carry
 * verbatim: {@code anthropic.claude-haiku-4-5} is absent while both {@code claude-haiku-4-5} and
 * the dated {@code anthropic.claude-haiku-4-5-20251001-v1:0} are present, so before this fallback
 * a real project's entire spend read as unpriced. Across every vendor-prefixed key in the
 * snapshot that has a bare counterpart, none differs in price, a property pinned by {@code
 * PriceSnapshotTest}, so a future snapshot that introduces a divergence fails there rather than
 * quietly mispricing here.
 */
@Component
public class ModelResolver {

    private static final String[] SCOPE_PREFIXES = {
        "global.", "us.", "eu.", "apac.", "au.", "jp.", "ca.", "sa.", "us-gov."
    };

    private static final String[] VENDOR_PREFIXES = {"anthropic.", "openai.", "meta.", "mistral.", "cohere.", "amazon."
    };

    /** How long a memoised cache-creation answer is trusted before the books in force are re-read. */
    private static final Duration BOOKS_RECHECK = Duration.ofMinutes(5);

    /**
     * {@link #reportedModelBillsCacheCreation} answers, valid for the books in force when {@code books} was read.
     * Swapped whole, never edited in place, so a reader never sees answers from two different books.
     */
    private record CacheCreationMemo(List<String> books, Instant checkedAt, Map<String, Boolean> answers) {}

    private final PriceBookRepository books;

    private volatile CacheCreationMemo memo =
            new CacheCreationMemo(List.of(), Instant.EPOCH, new ConcurrentHashMap<>());

    public ModelResolver(PriceBookRepository books) {
        this.books = books;
    }

    /**
     * The {@code model.id} this reported name resolves to, or empty when no known model matches.
     *
     * @param providedModelName the model string exactly as the producer reported it
     */
    public Optional<String> resolve(@Nullable String providedModelName) {
        if (providedModelName == null || providedModelName.isBlank()) return Optional.empty();
        String key = providedModelName.trim().toLowerCase(Locale.ROOT);
        if (books.hasModel(key)) return Optional.of(key);

        String scoped = strip(key, SCOPE_PREFIXES);
        if (!scoped.equals(key)) return books.hasModel(scoped) ? Optional.of(scoped) : Optional.empty();

        String bare = strip(key, VENDOR_PREFIXES);
        if (bare.equals(key)) return Optional.empty();
        return books.hasModel(bare) ? Optional.of(bare) : Optional.empty();
    }

    /**
     * Whether this model is billed for cache <b>creation</b>, i.e. whether writing to its prompt
     * cache is a charged, and therefore reported, quantity at all.
     *
     * <p>This is the book-backed form of the question the metric-drift {@code tok_cache_write}
     * measure asks: whether a missing write count means "no writes" or "not measured". Those are
     * different sentences and only one of them is a number: a call site that switched provider
     * would otherwise show a clean collapse in cache writes for purely bookkeeping reasons.
     *
     * <p>The metric-drift measure asks once per leaf span inside a sweep, so it calls the memoised
     * {@link #reportedModelBillsCacheCreation} rather than this.
     *
     * <p>Takes a {@code model.id}: a name as a producer reported it must be put through {@link
     * #resolve} first, or a vendor-prefixed spelling the book carries only in bare form answers
     * {@code false}.
     *
     * <p><b>Why the rate table answers this and a provider-family list does not.</b> The obvious
     * rule is "Anthropic and Bedrock bill cache creation explicitly, OpenAI's automatic caching
     * does not", but measured against the snapshot it is wrong inside a single vendor and in both
     * directions at once. Some OpenAI-family keys carry a cache-creation rate (the {@code gpt-5.6}
     * line, whose caching is explicit) while most do not ({@code gpt-4o}, {@code o3}); Gemini
     * carries none on any key, because it bills cache storage per hour rather than per written
     * token. A hand-kept family predicate would have to be re-derived every time a vendor changes
     * convention. The rate table is the record of who is billed.
     *
     * <p>A model no book in force prices answers {@code false}: unknown convention, so no claim. A
     * rate of exactly zero also answers {@code false}, matching the reading that a bucket with no
     * rate is a bucket never billed: the gateway-routed keys carrying a literal {@code 0.0} there
     * record "writing this cache is free" rather than "writes are counted".
     */
    public boolean billsCacheCreation(@Nullable String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        return books.rateFor(modelId)
                .map(rate -> {
                    BigDecimal write = rate.rates().cacheWritePerMtok();
                    return write != null && write.signum() > 0;
                })
                .orElse(false);
    }

    /**
     * {@link #billsCacheCreation} for a model name as a producer reported it, memoised per name for the books in
     * force. The metric-drift {@code tok_cache_write} measure asks this once per leaf span inside a sweep, where
     * {@link #resolve} plus a rate lookup would be several queries per span; memoised, a sweep costs one lookup
     * per distinct model.
     *
     * <p>The books in force are re-read at most every {@link #BOOKS_RECHECK}, and a changed set discards every
     * answer, so a book fetched from home.tessary.ai reaches this within that window without a restart.
     */
    public boolean reportedModelBillsCacheCreation(@Nullable String reportedModelName) {
        if (reportedModelName == null || reportedModelName.isBlank()) return false;
        String key = reportedModelName.trim().toLowerCase(Locale.ROOT);
        CacheCreationMemo current = currentMemo();
        Boolean known = current.answers().get(key);
        if (known != null) return known;
        boolean answer = resolve(key).map(this::billsCacheCreation).orElse(false);
        current.answers().put(key, answer);
        return answer;
    }

    private CacheCreationMemo currentMemo() {
        CacheCreationMemo current = memo;
        Instant now = Instant.now();
        if (now.isBefore(current.checkedAt().plus(BOOKS_RECHECK))) return current;
        List<String> inForce =
                books.currentBooks().stream().map(PriceBook::version).toList();
        CacheCreationMemo next = inForce.equals(current.books())
                ? new CacheCreationMemo(inForce, now, current.answers())
                : new CacheCreationMemo(inForce, now, new ConcurrentHashMap<>());
        memo = next;
        return next;
    }

    /** {@code key} with the first matching prefix removed, or unchanged when none matches. */
    private static String strip(String key, String[] prefixes) {
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) return key.substring(prefix.length());
        }
        return key;
    }
}
