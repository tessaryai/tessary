// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.pricing.PlatformCallPricer;
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
    private final PlatformCallPricer pricer;

    public LlmUsageAccountant(LlmCallWriteRepository ledger, PlatformCallPricer pricer) {
        this.ledger = ledger;
        this.pricer = pricer;
    }

    /**
     * Record one in-process LLM call ({@code LlmCaller}'s lanes). {@code platformFunded} decides the
     * funding side: the platform's own ambient identity versus the customer's pinned provider
     * credential.
     */
    public void record(
            @Nullable String projectId,
            ModelLane lane,
            @Nullable String model,
            @Nullable ServiceTier tier,
            boolean platformFunded,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable Integer cacheReadTokens,
            @Nullable Integer cacheWriteTokens,
            @Nullable BigDecimal costUsd,
            @Nullable String priceBookVersion,
            @Nullable Integer latencyMs) {
        record(
                projectId,
                lane.wire(),
                model,
                tier == null ? null : tier.wireName(),
                platformFunded,
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                costUsd,
                priceBookVersion,
                latencyMs,
                // The per-call lanes have no single unit of work behind one call: a lane call is one
                // among many, and pointing it at one is not attribution.
                null);
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
     * <p>The harness is not the pricing authority: it either reports no cost at all, or reports one
     * that omits cache reads, and cache reads dominate a repo-grounded run, so a harness-reported
     * figure would understate the lane by most of its actual spend. A null {@code costUsd} is
     * therefore priced here from the raw token counts against the same {@code price_book} every
     * in-process call is priced from, and the row is stamped with the book that did it. Still null
     * when no book in force carries the model: an absent cost is honest, a wrong one is not.
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
        Integer in = toInt(inputTokens);
        Integer out = toInt(outputTokens);
        Integer cacheRead = toInt(cacheReadTokens);
        Integer cacheWrite = toInt(cacheWriteTokens);
        PlatformCallPricer.PricedCall priced =
                pricer.price(model, null, in, out, cacheRead, cacheWrite).orElse(null);
        record(
                projectId,
                lane,
                model,
                null,
                platformFunded,
                in,
                out,
                cacheRead,
                cacheWrite,
                // A harness-reported cost is taken verbatim when there is one, but it is not stamped with a
                // book version: no book produced it, and naming one would misattribute the number.
                costUsd != null ? costUsd : (priced == null ? null : priced.total()),
                costUsd != null ? null : (priced == null ? null : priced.priceBookVersion()),
                null,
                subject);
    }

    private void record(
            @Nullable String projectId,
            String lane,
            @Nullable String model,
            @Nullable String tier,
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
                    tier,
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
