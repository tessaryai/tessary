// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * What one call consumed, in the ledger's buckets: {@code inputTokens} excludes cached reads, which sit in
 * {@code cacheReadTokens}. {@code reportedCostUsd} is the provider's own figure, set only where it reports one
 * (OpenRouter's {@code usage.cost}).
 */
public record ChatUsage(
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable Integer cacheReadTokens,
        @Nullable Integer cacheWriteTokens,
        @Nullable BigDecimal reportedCostUsd) {}
