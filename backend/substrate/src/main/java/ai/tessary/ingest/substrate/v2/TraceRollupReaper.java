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
 * Crash recovery for the rollup protocol: re-arms traces with {@code is_settled = false}, null {@code rollup_due_at}
 * and no recent {@code rolled_up_at}, which only a worker dying mid-claim produces. Deliberately blunt, since a
 * redundant recompute is harmless. Also logs ERROR when the oldest due deadline exceeds {@code rollup-stale-after-ms},
 * since a stuck queue is invisible from every read surface.
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

    /** @param queueDepth traces armed at the moment of the sweep, due or not @param overdueMs 0 if none is due */
    public record Sweep(int rearmed, int queueDepth, long overdueMs) {}

    @Scheduled(
            fixedDelayString = "${tessary.ingest.substrate.rollup-reap-interval-ms:60000}",
            initialDelayString = "${tessary.ingest.substrate.rollup-reap-interval-ms:60000}")
    public void tick() {
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // A failed sweep looks exactly like nothing being wrong, hence ERROR.
            log.error(
                    Markers.OPS,
                    "trace rollup reaper sweep failed error={}",
                    e.getClass().getSimpleName());
            log.debug("trace rollup reaper failure detail", e);
        }
    }

    /** Public so a test can drive recovery without racing a scheduler. */
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
