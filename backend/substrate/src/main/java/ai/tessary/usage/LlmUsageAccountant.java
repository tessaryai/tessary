// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.tenant.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records every platform LLM call into the {@code llm_call} ledger. Every method swallows its own failures at WARN: a
 * thrown ledger write would fail a run for a call already paid for. A call with no project id is dropped, since it
 * cannot be attributed to an org either.
 */
@Component
public class LlmUsageAccountant {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageAccountant.class);

    /**
     * The unit of work the spend is attributable to, beyond the lane. @param kind the subject's table name, e.g.
     * {@code behavior_finding} @param id the subject row's id, an opaque pointer, not an FK
     */
    public record Subject(String kind, String id) {}

    private final LlmCallWriteRepository ledger;

    public LlmUsageAccountant(LlmCallWriteRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * One ledger entry per sandbox run (RCA or triage), since the launcher reports usage per run.
     * {@code platformFunded} is usually false: these runs use the org's own credential. A null {@code costUsd} stays
     * unpriced, since the price book does not price sandbox runs. @param model what a person actually chose, e.g.
     * {@code grok-4.6}
     */
    public void recordSandboxRun(
            @Nullable String projectId,
            String lane,
            @Nullable String model,
            boolean platformFunded,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            @Nullable BigDecimal costUsd,
            @Nullable Subject subject) {
        record(
                projectId,
                lane,
                model,
                platformFunded,
                toInt(inputTokens),
                toInt(outputTokens),
                toInt(cacheReadTokens),
                toInt(cacheWriteTokens),
                costUsd,
                null,
                null,
                subject);
    }

    /**
     * One hosted decision-model call, already priced by the caller under the book it names. @param platformFunded true
     * when the call ran on the deployment's own provider, which is the row a credit debit reads
     */
    public void recordDecisionCall(
            @Nullable String projectId,
            String lane,
            String model,
            boolean platformFunded,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable BigDecimal costUsd,
            @Nullable String priceBookVersion,
            int latencyMs) {
        record(
                projectId,
                lane,
                model,
                platformFunded,
                inputTokens,
                outputTokens,
                null,
                null,
                costUsd,
                priceBookVersion,
                latencyMs,
                null);
    }

    private void record(
            @Nullable String projectId,
            String lane,
            @Nullable String model,
            boolean platformFunded,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable Integer cacheReadTokens,
            @Nullable Integer cacheWriteTokens,
            @Nullable BigDecimal costUsd,
            @Nullable String priceBookVersion,
            @Nullable Integer latencyMs,
            @Nullable Subject subject) {
        if (projectId == null || projectId.isBlank()) return;
        try {
            ledger.insert(new LlmCallRow(
                    Ids.ulid(),
                    projectId,
                    lane,
                    model,
                    null,
                    platformFunded ? LlmCallRow.CostFunding.PLATFORM : LlmCallRow.CostFunding.BYO,
                    inputTokens,
                    outputTokens,
                    cacheReadTokens,
                    cacheWriteTokens,
                    costUsd,
                    priceBookVersion,
                    latencyMs,
                    subject == null ? null : subject.kind(),
                    subject == null ? null : subject.id(),
                    Instant.now().toString()));
        } catch (RuntimeException e) {
            log.warn("llm usage ledger write failed lane={}", lane, e);
        }
    }

    /** 0 reads as absent. */
    private static @Nullable Integer toInt(long value) {
        if (value <= 0) return null;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
