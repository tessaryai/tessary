// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

/**
 * Retry for a 429, 5xx or network blip. @param maxAttempts including the first; 1 disables retry @param baseBackoffMs
 * doubles each retry @param maxBackoffMs also caps a {@code Retry-After} hint
 */
public record RetryPolicy(int maxAttempts, long baseBackoffMs, long maxBackoffMs) {

    /**
     * Seconds, because the dominant failure is a per-minute 429 bucket: a sub-second retry lands in the same window.
     */
    public static final RetryPolicy DEFAULT = new RetryPolicy(5, 2000, 60_000);

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseBackoffMs < 0 || maxBackoffMs < 0) throw new IllegalArgumentException("back-off must be >= 0");
    }

    /**
     * A {@code Retry-After} hint wins, else exponential; both capped. @param retryAfterMs non-positive when none was
     * given
     */
    public long backoffMs(int attempt, long retryAfterMs) {
        // Clamp so a pathological attempt count cannot overflow into a negative back-off.
        int shift = Math.min(Math.max(attempt - 1, 0), 31);
        long ms = retryAfterMs > 0 ? retryAfterMs : baseBackoffMs << shift;
        return Math.min(ms, maxBackoffMs);
    }
}
