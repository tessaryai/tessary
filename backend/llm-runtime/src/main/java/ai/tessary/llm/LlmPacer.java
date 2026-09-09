// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token-aware pacer for the judge model. Enforces two sliding-window budgets
 * (requests per minute, tokens per minute) and a hard minimum gap between
 * successive calls. Records actual token usage after each call so the next
 * call's wait can be computed from real data, not a guess.
 *
 * <p>Caller contract:
 * <pre>
 *   pacer.acquireSlot(estimatedTokens);   // sleeps as needed
 *   try {
 *     resp = chatModel.chat(req);
 *     pacer.recordCall(actualTokens);
 *   } catch (rate-limited) {
 *     pacer.recordRateLimit(e);            // adjusts internal back-off
 *     // caller retries
 *   }
 * </pre>
 *
 * Thread-safe by construction, not by convention: every grading path (manual "try it", the
 * classifier-escalation {@code GraderRunWorker} pool, the bounded fan-out executors) can call
 * this concurrently — the {@code synchronized} methods enforce the sliding window correctly
 * regardless of caller concurrency. The synchronisation is cheap since a paced call is already
 * dominated by real network latency.
 *
 * <p>Limits are static constants — a conservative ceiling shared by every paced provider
 * (OpenRouter, Moonshot — Ollama was dropped by the maker filter; see
 * {@code ChatModelFactory.paced}), all reached only via a
 * user-supplied credential, plus 429 back-off. They are intentionally not configurable per
 * provider: the ceiling is deliberately cautious rather than tuned to any one tier's advertised
 * limit.
 */
@Component
public class LlmPacer {

    private static final Logger log = LoggerFactory.getLogger(LlmPacer.class);

    /** Hard floor between successive calls. */
    private static final long MIN_INTERVAL_MS = 1000;
    /** Sliding-window cap on requests per 60s. */
    private static final int MAX_REQUESTS_PER_MINUTE = 28;
    /** Sliding-window cap on (input + output) tokens per 60s. */
    private static final int MAX_TOKENS_PER_MINUTE = 6500;
    /** Max retries when upstream returns 429. */
    private static final int MAX_RETRIES = 5;
    /** Base for exponential back-off on 429. */
    private static final long BACKOFF_BASE_MS = 2000;
    /** Cap on back-off sleep. */
    private static final long BACKOFF_MAX_MS = 60_000;

    /** Instance accessor so callers (and Mockito) can read the retry budget. */
    public int getMaxRetries() {
        return MAX_RETRIES;
    }

    /** Last-known total tokens (in+out) for a single call. Seed for the next estimate. */
    private volatile int lastSeenTokens = 0;

    /** entries: [timestampMs, tokens]. Pruned per call. */
    private final Deque<long[]> window = new ArrayDeque<>();
    /** End time of the most recent call (acquire or record). */
    private long lastCallEnd = 0;

    public synchronized int estimateNextTokens() {
        return lastSeenTokens > 0 ? lastSeenTokens : 1000;
    }

    /** Block until budgets + min-interval permit another call. */
    public void acquireSlot(int estimatedTokens) throws InterruptedException {
        while (true) {
            long wait;
            synchronized (this) {
                long now = System.currentTimeMillis();
                pruneOlderThan(now - 60_000);
                wait = 0;

                wait = Math.max(wait, (lastCallEnd + MIN_INTERVAL_MS) - now);

                if (window.size() >= MAX_REQUESTS_PER_MINUTE && !window.isEmpty()) {
                    wait = Math.max(wait, window.peekFirst()[0] + 60_000 - now);
                }

                int tokensInWindow = 0;
                for (long[] e : window) tokensInWindow += (int) e[1];
                // If a SINGLE call's estimate alone exceeds the budget, no amount of waiting
                // helps — just proceed and let 429 + retry handle it.
                if (estimatedTokens <= MAX_TOKENS_PER_MINUTE
                        && tokensInWindow + estimatedTokens > MAX_TOKENS_PER_MINUTE
                        && !window.isEmpty()) {
                    wait = Math.max(wait, window.peekFirst()[0] + 60_000 - now);
                }

                if (wait <= 0) {
                    log.debug("pacer: ok — window={}r/{}t estimate={}", window.size(), tokensInWindow, estimatedTokens);
                    return;
                }
                log.info(
                        "pacer: sleeping {}ms (window={}r/{}t, estimate={}, limits {}r/{}t)",
                        wait,
                        window.size(),
                        tokensInWindow,
                        estimatedTokens,
                        MAX_REQUESTS_PER_MINUTE,
                        MAX_TOKENS_PER_MINUTE);
            }
            Thread.sleep(Math.min(wait, 30_000));
        }
    }

    /** Record a successful call's token usage. */
    public synchronized void recordCall(int tokensUsed) {
        long now = System.currentTimeMillis();
        window.addLast(new long[] {now, Math.max(0, tokensUsed)});
        lastCallEnd = now;
        if (tokensUsed > 0) lastSeenTokens = tokensUsed;
    }

    /**
     * Compute back-off for a 429-style failure. Honors a "Retry-After"-like
     * hint parsed from the error message ("Please try again in 1m23.5s")
     * when present; otherwise exponential with a cap.
     */
    public long computeBackoffMs(int attempt, @Nullable Throwable err) {
        long parsed = parseRetryAfterMs(err == null ? null : err.getMessage());
        if (parsed > 0) return Math.min(parsed, BACKOFF_MAX_MS);
        long exp = BACKOFF_BASE_MS * (1L << Math.min(attempt, 10));
        return Math.min(exp, BACKOFF_MAX_MS);
    }

    /** True if the throwable looks like a 429 / rate-limit. */
    public static boolean isRateLimit(@Nullable Throwable t) {
        if (t == null) return false;
        Throwable cur = t;
        for (int i = 0; cur != null && i < 6; i++, cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("429")) return true;
            if (lower.contains("rate limit")) return true;
            if (lower.contains("rate_limit")) return true;
            if (lower.contains("too many requests")) return true;
        }
        return false;
    }

    private void pruneOlderThan(long minTs) {
        while (!window.isEmpty() && window.peekFirst()[0] < minTs) {
            window.pollFirst();
        }
    }

    // Three independent units: minutes (\dm not followed by 's'), seconds (\d.\ds), milliseconds (\dms).
    // The "try again in" prefix is kept as a guard so we don't match unrelated numbers in the error.
    private static final Pattern RETRY_PREFIX = Pattern.compile("try again in", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_MINUTES = Pattern.compile("(\\d+)\\s*m(?!s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_SECONDS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*s\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_MILLIS = Pattern.compile("(\\d+)\\s*ms\\b", Pattern.CASE_INSENSITIVE);

    static long parseRetryAfterMs(@Nullable String msg) {
        if (msg == null) return -1;
        Matcher prefix = RETRY_PREFIX.matcher(msg);
        if (!prefix.find()) return -1;
        String tail = msg.substring(prefix.end());

        long total = 0;
        Matcher mm = UNIT_MILLIS.matcher(tail);
        if (mm.find()) total += Long.parseLong(mm.group(1));
        // Strip the matched ms span so the seconds matcher doesn't double-count.
        String tailMinusMs = mm.find(0) ? tail.substring(0, mm.start()) + tail.substring(mm.end()) : tail;

        Matcher sm = UNIT_SECONDS.matcher(tailMinusMs);
        if (sm.find()) total += Math.round(Double.parseDouble(sm.group(1)) * 1000.0);

        Matcher mim = UNIT_MINUTES.matcher(tailMinusMs);
        if (mim.find()) total += Long.parseLong(mim.group(1)) * 60_000L;

        return total > 0 ? total : -1;
    }
}
