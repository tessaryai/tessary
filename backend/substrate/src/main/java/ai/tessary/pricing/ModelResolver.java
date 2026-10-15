// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exact, then region-stripped, then vendor-stripped; each rule was paid for by a mis-priced project. A consumed region
 * prefix stops the fallback: regional profiles bill +10-20%, so resolving to the bare model would under-report
 * silently, and unpriced is the better answer. A vendor prefix is stripped only when the book lacks the prefixed key.
 */
@Component
public class ModelResolver {

    private static final String[] SCOPE_PREFIXES = {
        "global.", "us.", "eu.", "apac.", "au.", "jp.", "ca.", "sa.", "us-gov."
    };

    private static final String[] VENDOR_PREFIXES = {"anthropic.", "openai.", "meta.", "mistral.", "cohere.", "amazon."
    };

    private static final Duration BOOKS_RECHECK = Duration.ofMinutes(5);

    /** Swapped whole, never edited in place, so a reader never sees answers from two books. */
    private record CacheCreationMemo(List<String> books, Instant checkedAt, Map<String, Boolean> answers) {}

    private final PriceBookRepository books;

    private final Clock clock;

    private volatile CacheCreationMemo memo =
            new CacheCreationMemo(List.of(), Instant.EPOCH, new ConcurrentHashMap<>());

    @Autowired
    public ModelResolver(PriceBookRepository books) {
        this(books, Clock.systemUTC());
    }

    ModelResolver(PriceBookRepository books, Clock clock) {
        this.books = books;
        this.clock = clock;
    }

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
     * The rate table answers, not a provider-family list: the convention differs within a vendor ({@code gpt-5.6} bills
     * creation, {@code gpt-4o} does not). Takes a resolved {@code model.id}. An unpriced model, or a rate of exactly
     * zero, answers false.
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
     * Memoised per name because the drift sweep asks once per leaf span. A changed set of books in force discards every
     * answer within {@link #BOOKS_RECHECK}.
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
        Instant now = clock.instant();
        if (now.isBefore(current.checkedAt().plus(BOOKS_RECHECK))) return current;
        List<String> inForce =
                books.currentBooks().stream().map(PriceBook::version).toList();
        CacheCreationMemo next = inForce.equals(current.books())
                ? new CacheCreationMemo(inForce, now, current.answers())
                : new CacheCreationMemo(inForce, now, new ConcurrentHashMap<>());
        memo = next;
        return next;
    }

    private static String strip(String key, String[] prefixes) {
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) return key.substring(prefix.length());
        }
        return key;
    }
}
