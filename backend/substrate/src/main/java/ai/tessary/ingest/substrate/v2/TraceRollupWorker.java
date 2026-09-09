// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Repository.Claim;
import ai.tessary.storage.TraceV2Repository.Recomputed;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trace rollup worker (substrate-model.md §7.2/§7.3): claims traces whose deadline has come and
 * rewrites their counters, sums and previews from their spans.
 *
 * <h2>Claim, then write, and the order is the whole protocol</h2>
 *
 * <p>The claim clears {@code rollup_due_at} and that is not tidy-up — it is the correctness crux. From the
 * instant a trace is claimed, any arriving span re-arms the deadline to a non-null value in the same
 * transaction as its own row (§6.1), so the write that follows can detect that it happened by testing
 * whether the deadline is STILL clear. If it is not, the trace does not settle and fires again with that
 * span included. Both outcomes write correct numbers, because the numbers are a replacement as of the
 * aggregate's read rather than a delta applied to a prior value: there is no lost update to lose.
 *
 * <p>That is also why {@code is_settled} can be believed. It means precisely "nothing has arrived since
 * the last rollup" and never "we stopped waiting" — no timeout in this class can set it.
 *
 * <h2>No hard cap, deliberately</h2>
 *
 * <p>A trace that never goes quiet is not a problem to be capped. It fires at its deadline, the next span
 * re-arms it, and it fires again — so a long-running turn is refreshed with current numbers roughly every
 * ten seconds instead of showing nothing at all until it ends. The deadline only ever moves earlier
 * (§7.1), which is what makes that terminate.
 *
 * <h2>Failure is the reaper's problem, not a lease's</h2>
 *
 * <p>There is no lease and no job row. A claim is a committed {@code UPDATE}, so a worker that dies after
 * it leaves a trace with a fingerprint no legitimate state produces — unsettled, unarmed, not recently
 * rolled up — which {@link TraceRollupReaper} sweeps back into the queue. A job table could not express
 * this protocol at all: the null-and-check on {@code rollup_due_at} has to live on the same row the
 * counters do, or a span arriving mid-rollup would have nothing to race against.
 *
 * <p>Per-trace failures are caught per trace, which is safe HERE and forbidden in the batch writer: each
 * recompute is its own autocommit statement, so catching one does not leave a transaction rollback-only,
 * and one poison trace must not strand the other 499 the round claimed.
 */
@Component
@ConditionalOnProperty(
        prefix = "tessary.ingest.substrate",
        name = "rollup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TraceRollupWorker {

    private static final Logger log = LoggerFactory.getLogger(TraceRollupWorker.class);

    /**
     * Upper bound on claim rounds per tick, so one tick can drain a burst but never spins unbounded on a
     * queue being refilled as fast as it is drained.
     */
    private static final int MAX_CLAIM_ROUNDS = 100;

    private final TraceV2Repository traces;
    private final TraceRollupMetrics metrics;
    private final SubstrateProperties props;

    public TraceRollupWorker(TraceV2Repository traces, TraceRollupMetrics metrics, SubstrateProperties props) {
        this.traces = traces;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * One tick's work.
     *
     * @param claimed traces claimed, which is also how many deadlines this tick cleared.
     * @param recomputed traces whose replacement landed.
     * @param settled of those, the ones nothing arrived under. The shortfall is spans landing mid-rollup.
     * @param vanished claimed traces the write could not find — deleted under the worker, normal at a
     *     retention boundary and worth seeing anywhere else.
     * @param failed recomputes that threw. Each leaves the reaper's fingerprint and is recovered by it.
     */
    public record Pass(int claimed, int recomputed, int settled, int vanished, int failed) {}

    @Scheduled(
            fixedDelayString = "${tessary.ingest.substrate.rollup-interval-ms:1000}",
            initialDelayString = "${tessary.ingest.substrate.rollup-interval-ms:1000}")
    public void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // Categorical only: a Postgres error message can echo span content, and OPS WARN egresses.
            // The rows are durable and unchanged, and anything this tick claimed carries the reaper's
            // fingerprint, so the next tick or the next sweep picks the work back up.
            log.warn(
                    Markers.OPS,
                    "trace rollup pass failed error={}",
                    e.getClass().getSimpleName());
            log.debug("trace rollup failure detail", e);
        }
    }

    /**
     * Run one pass synchronously and report what it did.
     *
     * <p>Synchronous on the scheduler's own (virtual) thread rather than dispatched to an executor, and
     * that is a choice: {@code fixedDelay} guarantees no two passes overlap only while the tick is the
     * work. Handing the batch to an executor would buy concurrency the recompute does not need — it is one
     * indexed statement per trace — at the cost of ticks racing each other over the same queue.
     *
     * <p>Public so a test can drive it without a scheduler, which is what makes the settle assertions
     * deterministic rather than a sleep long enough to usually work.
     */
    public Pass runOnce() {
        Instant started = Instant.now();
        int limit = props.getRollupClaimLimit();
        int claimed = 0;
        int recomputed = 0;
        int settled = 0;
        int vanished = 0;
        int failed = 0;

        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<Claim> batch = traces.claimDue(limit);
            if (batch.isEmpty()) break;
            claimed += batch.size();
            for (Claim claim : batch) {
                try {
                    Optional<Recomputed> result = traces.recompute(claim.projectId(), claim.traceId());
                    if (result.isEmpty()) {
                        vanished++;
                    } else {
                        recomputed++;
                        if (result.get().settled()) settled++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn(
                            Markers.OPS,
                            "trace rollup recompute failed project={} error={}",
                            claim.projectId(),
                            e.getClass().getSimpleName());
                    log.debug("trace rollup recompute failure detail", e);
                }
            }
            // A short round means the queue is drained; claiming again would only cost a statement.
            if (batch.size() < limit) break;
        }

        Pass pass = new Pass(claimed, recomputed, settled, vanished, failed);
        metrics.recordPass(claimed, recomputed, settled, failed);
        if (claimed > 0) {
            StructuredLog.info(log, Markers.OPS, "substrate.rollup.pass")
                    .message(
                            "rolled up %s trace(s), %s settled, %s still receiving spans",
                            recomputed, settled, recomputed - settled)
                    .field("claimed", claimed)
                    .field("recomputed", recomputed)
                    .field("settled", settled)
                    .field("vanished", vanished)
                    .field("failed", failed)
                    .durationMs(started)
                    .log();
        }
        return pass;
    }
}
