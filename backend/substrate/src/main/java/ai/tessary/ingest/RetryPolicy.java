// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

/**
 * Per-call retry/back-off policy for {@link HttpJson}. Controls how a transient
 * upstream failure (a 429 rate-limit, a 5xx, or a network blip) is retried.
 *
 * <p>It is the seam that lets each path pick its own trade-off: a background run
 * or export wants {@link #DEFAULT} — patient, seconds-scale back-off that rides
 * out a per-minute rate-limit window — while an interactive preview wants
 * {@link #INTERACTIVE} so a long upstream {@code Retry-After} can never hang a UI
 * request; it surfaces the 429 fast instead. A new source with different
 * rate-limit semantics plugs in here rather than forking {@link HttpJson}.
 *
 * @param maxAttempts   total attempts including the first (≥ 1; 1 disables retry)
 * @param baseBackoffMs first-retry back-off when the upstream gives no hint; doubles each retry
 * @param maxBackoffMs  ceiling on any single back-off sleep (also caps a {@code Retry-After} hint)
 */
public record RetryPolicy(int maxAttempts, long baseBackoffMs, long maxBackoffMs) {

    /**
     * Background runs/exports: 5 attempts, 2s → 4s → 8s → 16s, capped at 60s. Sized
     * in seconds because the dominant failure is a per-minute 429 bucket (e.g.
     * Langfuse's 30 req/min on Hobby): a sub-second retry would land in the same window.
     */
    public static final RetryPolicy DEFAULT = new RetryPolicy(5, 2000, 60_000);

    /**
     * Interactive preview: at most one quick retry with a short cap, so a request a
     * user is waiting on never blocks on a long {@code Retry-After}. A persistent
     * rate-limit surfaces as a 429 in ~half a second rather than after a minute.
     */
    public static final RetryPolicy INTERACTIVE = new RetryPolicy(2, 500, 2000);

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseBackoffMs < 0 || maxBackoffMs < 0) throw new IllegalArgumentException("back-off must be >= 0");
    }

    /**
     * Back-off before the {@code attempt}-th retry (1-based). An upstream
     * {@code Retry-After} hint wins — it is the provider telling us exactly when its
     * bucket refills — otherwise exponential from {@link #baseBackoffMs}. Both are
     * capped at {@link #maxBackoffMs}.
     *
     * @param retryAfterMs upstream hint in ms, or a non-positive value when none was given
     */
    public long backoffMs(int attempt, long retryAfterMs) {
        // Clamp the shift so a pathological attempt count can't overflow the long into a
        // negative back-off; the result is capped at maxBackoffMs anyway.
        int shift = Math.min(Math.max(attempt - 1, 0), 31);
        long ms = retryAfterMs > 0 ? retryAfterMs : baseBackoffMs << shift;
        return Math.min(ms, maxBackoffMs);
    }
}
