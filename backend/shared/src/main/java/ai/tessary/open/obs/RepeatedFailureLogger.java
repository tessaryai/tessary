// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Dedups a failure that repeats every time a caller retries the same operation (e.g. a scheduled
 * worker re-attempting the same job on every tick), so it doesn't re-log a full stacktrace on
 * every retry.
 *
 * <p>The first occurrence of a given {@code key} runs {@code onFirst} (typically a full
 * {@code log.error(marker, msg, e)} — the caller decides level/marker/message). Every occurrence
 * after that is counted silently until the count is a multiple of {@code summaryEvery}, when
 * {@code onSummary(count)} runs once — a cheap "still failing, N occurrences" line instead of N
 * stacktraces. {@link #clear(String)} resets the streak (call it on success) so the next failure
 * for that key logs fresh again.
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

    /**
     * Record one failure for {@code key}. Runs {@code onFirst} on the first occurrence of a new
     * streak, {@code onSummary} every {@code summaryEvery}th occurrence thereafter, and is silent
     * otherwise.
     */
    public void record(String key, Runnable onFirst, LongConsumer onSummary) {
        long count = occurrences.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        if (count == 1) {
            onFirst.run();
        } else if (count % summaryEvery == 0) {
            onSummary.accept(count);
        }
    }

    /** Reset the streak for {@code key} (call on success) so the next failure logs fresh. */
    public void clear(String key) {
        occurrences.remove(key);
    }
}
