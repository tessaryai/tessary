// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.vitals;

/**
 * The four token buckets of one generation, already disjoint.
 *
 * <p><b>Disjointness is established at ingest, once, and never re-derived here.</b>
 * {@code IngestPricer} carves both cache buckets out of the producer's cache-inclusive
 * {@code gen_ai.usage.input_tokens} before the span row is written (substrate-model.md §6.5), so every
 * reader of those columns — this record included — gets buckets it can price independently and sum. The
 * earlier shape carried a flag and a model-family guesser to decide the convention at read time; the guess
 * was wrong for Anthropic traffic routed through gateways, and a correction applied at read time can only
 * ever disagree with the cost already stored on the row.
 *
 * @param inputTokens billable input, EXCLUDING the cache buckets
 * @param outputTokens generated tokens
 * @param cacheReadTokens tokens served from cache at the discounted read rate
 * @param cacheWriteTokens tokens written to cache at the premium write rate
 */
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

    /** Every bucket summed — the honest total, which the lump {@code total_tokens} column is not. */
    public long total() {
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens);
    }
}
