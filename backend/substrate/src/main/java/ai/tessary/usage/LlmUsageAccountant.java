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
 * Records every platform LLM call into the {@code llm_call} ledger, so Settings → Usage can answer
 * "which part of the product burned these tokens, in which bucket, at what cost" for every lane.
 *
 * <p>Accounting must never break the thing it is accounting for: every method swallows its own
 * failures at WARN. An unwritable ledger row costs a line on a usage page, whereas a thrown one
 * would fail a run for an LLM call that already happened, and was already paid for.
 *
 * <p>A call with no project id is dropped rather than stored: {@code llm_call} hangs off
 * {@code project}, and a call that cannot be attributed to a project cannot be attributed to an org
 * either, so there is no surface it could honestly appear on.
 */
@Component
public class LlmUsageAccountant {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageAccountant.class);

    /**
     * What one run was for: the unit of work the spend is attributable to, beyond the lane that did
     * it. The lane alone can say the triage agent cost a project $X this week, but not which
     * rulings that was. {@code kind} is the subject's table name so the pointer is resolvable
     * without a lookup table of its own: {@code behavior_finding} for a Layer-2 ruling.
     *
     * @param kind the subject's table name
     * @param id the subject row's id, an opaque pointer, not an FK
     */
    public record Subject(String kind, String id) {}

    private final LlmCallWriteRepository ledger;

    public LlmUsageAccountant(LlmCallWriteRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * Record one agent-sandbox run: the coding agent in an E2B microVM, for RCA or Layer-2 triage.
     * The whole run is one ledger entry: the launcher reports usage for the run, not per turn, and
     * the run is what the lane is billed for.
     *
     * <p>{@code platformFunded} is a real parameter, not a hardcoded {@code true}: RCA and TRIAGE run
     * on the org's own injected credential (see {@code AgenticCredentialResolver} on the caller
     * side), so a caller passes {@code false} for the ordinary case rather than silently
     * misattributing every sandbox run's cost to the platform.
     *
     * <p>Unlike the per-call lanes, a sandbox run has a unit of work: one ruling, one report. So
     * {@code subject} is the thing the money was spent on, and is what turns "the triage agent cost
     * this project $40" into "these eleven rulings cost $40". Null where the caller genuinely has no
     * single subject.
     *
     * <p>The cost is the one the sandbox reported: OpenCode's, computed from the models.dev rates the
     * launcher declared for the run's model (see {@code llm/ModelsDevRates}). The price book does not
     * price a sandbox run, so no row of this kind names a book version. A null {@code costUsd} is the
     * run reporting no cost it can stand behind, and the row stays unpriced: an absent cost is
     * honest, a wrong one is not.
     *
     * @param model the name to store on the ledger row — what a person actually chose, e.g.
     *     {@code grok-4.6}
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
     * Record one hosted decision-model call ({@code llm/decisions/}): one typed question set. The caller
     * has already priced it, under the book id its gateway maps to, and passes the book that did.
     *
     * @param lane the lane's wire value
     * @param model the model id as requested, e.g. {@code jev-latest}
     * @param platformFunded true when the call ran on the deployment's own provider rather than the org's
     *     key, which is the row a credit debit reads
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

    /** Clamp a launcher-reported count into the ledger's {@code integer} columns; 0 reads as absent. */
    private static @Nullable Integer toInt(long value) {
        if (value <= 0) return null;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
