// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Repository.RollupQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Crash recovery for the rollup protocol (substrate-model.md §7.4), and the only thing watching whether
 * the queue is draining at all.
 *
 * <h2>The fingerprint</h2>
 *
 * <p>{@link TraceRollupWorker}'s claim clears {@code rollup_due_at} and commits before the write that
 * follows it. A worker that dies in between therefore leaves a state nothing legitimate produces:
 * {@code is_settled = false} — so spans have arrived since the last rollup — with {@code rollup_due_at}
 * null, so nobody is going to look at it, and no recent {@code rolled_up_at} to say someone just did.
 * Such a trace would sit forever showing counters from before the spans that un-settled it, honestly
 * marked stale and never becoming fresh. This sweep re-arms it.
 *
 * <p><b>The sweep is deliberately blunt.</b> It cannot tell a dead worker's trace from one being written
 * right now, and it does not try: re-arming a healthy in-flight claim costs exactly one redundant
 * recompute, because every rollup is a replacement rather than a delta. Recovery that cannot corrupt is
 * worth far more than recovery that is precise, and the alternative — a lease with an expiry — would put
 * the settle protocol's state in two places that can disagree.
 *
 * <h2>The staleness alarm</h2>
 *
 * <p>A rollup queue that stops draining is invisible from every read surface: the traces list keeps
 * serving the numbers the last successful rollup wrote, with no gap and no error, simply older than it
 * claims. So the sweep also samples the queue and logs ERROR when the oldest due deadline has fallen
 * further behind than {@code rollup-stale-after-ms}. Log-only, like the grading-spend cursor alarm it is
 * modelled on: the healthy response to a stuck queue is a human, and there is no automatic action here
 * that would not risk making an infrastructure hiccup worse.
 */
@Component
@ConditionalOnProperty(
        prefix = "tessary.ingest.substrate",
        name = "rollup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TraceRollupReaper {

    private static final Logger log = LoggerFactory.getLogger(TraceRollupReaper.class);

    private final TraceV2Repository traces;
    private final TraceRollupMetrics metrics;
    private final SubstrateProperties props;

    public TraceRollupReaper(TraceV2Repository traces, TraceRollupMetrics metrics, SubstrateProperties props) {
        this.traces = traces;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * One sweep's outcome.
     *
     * @param rearmed traces recovered from a rollup that never completed.
     * @param queueDepth traces armed at the moment of the sweep, due or not.
     * @param overdueMs how far past its deadline the oldest due trace has fallen, or 0 if none is due.
     */
    public record Sweep(int rearmed, int queueDepth, long overdueMs) {}

    @Scheduled(
            fixedDelayString = "${tessary.ingest.substrate.rollup-reap-interval-ms:60000}",
            initialDelayString = "${tessary.ingest.substrate.rollup-reap-interval-ms:60000}")
    public void tick() {
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // A failed sweep means crash recovery stopped happening, which looks exactly like nothing being
            // wrong — the same shape as the grading-spend cursor going quiet, and ERROR for the same reason.
            log.error(
                    Markers.OPS,
                    "trace rollup reaper sweep failed error={}",
                    e.getClass().getSimpleName());
            log.debug("trace rollup reaper failure detail", e);
        }
    }

    /**
     * Run one sweep synchronously and report what it did. Public so a test can drive recovery without
     * waiting a minute for a scheduler, and without the sweep racing the assertions it is being checked by.
     */
    public Sweep sweepOnce() {
        int rearmed = traces.reap(props.getRollupReapGraceSeconds());
        RollupQueue queue = traces.queueStats();
        metrics.recordSweep(rearmed, queue.depth(), queue.overdueMs());

        if (rearmed > 0) {
            StructuredLog.warn(log, Markers.OPS, "substrate.rollup.reaped")
                    .message("re-armed %s trace(s) claimed for a rollup that never wrote back", rearmed)
                    .field("rearmed", rearmed)
                    .field("grace_seconds", props.getRollupReapGraceSeconds())
                    .field("queue_depth", queue.depth())
                    .log();
        }
        if (queue.overdueMs() > props.getRollupStaleAfterMs()) {
            log.error(
                    Markers.OPS,
                    "trace rollup queue is behind by {}ms over {} armed trace(s) — every trace surface is"
                            + " serving counters older than it claims",
                    queue.overdueMs(),
                    queue.depth());
        }
        return new Sweep(rearmed, queue.depth(), queue.overdueMs());
    }
}
