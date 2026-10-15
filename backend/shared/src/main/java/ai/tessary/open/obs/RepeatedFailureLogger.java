// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Dedups a failure that repeats on every retry of the same operation. The first occurrence of a {@code key} runs
 * {@code onFirst}; later ones are counted silently, with {@code onSummary(count)} every {@code summaryEvery}th.
 * {@link #clear(String)} resets the streak on success.
 */
public final class RepeatedFailureLogger {

    private final int summaryEvery;
    private final ConcurrentHashMap<String, AtomicLong> occurrences = new ConcurrentHashMap<>();

    public RepeatedFailureLogger(int summaryEvery) {
        if (summaryEvery < 1) {
            throw new IllegalArgumentException("summaryEvery must be >= 1");
        }
        this.summaryEvery = summaryEvery;
    }

    public void record(String key, Runnable onFirst, LongConsumer onSummary) {
        long count = occurrences.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        if (count == 1) {
            onFirst.run();
        } else if (count % summaryEvery == 0) {
            onSummary.accept(count);
        }
    }

    /** Call on success. */
    public void clear(String key) {
        occurrences.remove(key);
    }
}
