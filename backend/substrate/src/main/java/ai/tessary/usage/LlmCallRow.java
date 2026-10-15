// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One platform LLM call in the {@code llm_call} ledger, with token buckets kept split because they are priced
 * differently. A null {@code costUsd} is unpriced, never zero. @param subjectKind the subject's table name, or null for
 * lanes with no single unit of work @param subjectId an opaque pointer, not an FK (migration {@code 0051}) @param
 * priceBookVersion non-null exactly when {@code costUsd} is; pre-{@code 0084} rows are never back-stamped, since that
 * would fabricate the audit trail
 */
public record LlmCallRow(
        String id,
        String projectId,
        String lane,
        @Nullable String model,
        @Nullable String serviceTier,
        String funding,
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable Integer cacheReadTokens,
        @Nullable Integer cacheWriteTokens,
        @Nullable BigDecimal costUsd,
        @Nullable String priceBookVersion,
        @Nullable Integer latencyMs,
        @Nullable String subjectKind,
        @Nullable String subjectId,
        String createdAt) {

    /**
     * Whose credential paid. A spend ceiling counts only {@link #PLATFORM}, so one tenant's BYO usage never stops
     * others.
     */
    public static final class CostFunding {
        private CostFunding() {}

        public static final String PLATFORM = "platform";
        public static final String BYO = "byo";
    }
}
