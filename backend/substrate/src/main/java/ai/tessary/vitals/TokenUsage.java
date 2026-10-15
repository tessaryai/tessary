// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

/**
 * The four token buckets of one generation, already disjoint. {@code IngestPricer} carves the cache buckets out of the
 * cache-inclusive input count once, at ingest; deciding the convention at read time would disagree with the cost
 * already stored on the row. @param inputTokens billable input, excluding the cache buckets
 */
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {}
