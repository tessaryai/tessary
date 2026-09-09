// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One platform LLM call as it lands in the {@code llm_call} ledger. The token buckets stay split
 * (input / output / cache-read / cache-write) rather than collapsed to a total, since the four are
 * priced at different rates and a cache-heavy lane looks nothing like an output-heavy one on the bill.
 *
 * <p>{@code costUsd} is null when no price book in force holds a rate for the model: the honest
 * "unpriced" sentinel, never zero. Readers count those calls separately (see
 * {@link LlmCallWriteRepository.UsageSlice#unpricedCalls()}) so the gap reads as a gap rather than as free.
 *
 * @param subjectKind the subject's table name ({@code behavior_finding}, {@code rca_report}, …), or null
 *     for the per-call lanes that have no single unit of work behind them. With {@code subjectId} this is
 *     what makes spend attributable to one triage rather than only to a lane.
 * @param subjectId the subject row's id. An opaque pointer, not an FK: see migration {@code 0051}.
 * @param priceBookVersion which {@code price_book} priced this row, non-null exactly when {@code costUsd}
 *     is. Null also means "priced by the retired hand-maintained catalog" on rows written before migration
 *     {@code 0084}; those are never rewritten, since this ledger is append-only accounting and stamping
 *     a book onto a row it did not price would fabricate the audit trail.
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
     * {@code llm_call.funding} values: whose credential paid for the call. The platform's own ambient
     * Bedrock identity funds the platform lanes; a pinned provider runs on the customer's credential. A
     * deployment-wide spend ceiling counts only {@link #PLATFORM}, so one customer's BYO-key usage can
     * never stop work for every other tenant.
     */
    public static final class CostFunding {
        private CostFunding() {}

        public static final String PLATFORM = "platform";
        public static final String BYO = "byo";
    }
}
