// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the rollup worker and its reaper did, for the {@code ingest.throughput} heartbeat to carry.
 *
 * <p><b>Why a bean of its own.</b> The two producers are gated by {@code @ConditionalOnProperty} and the
 * consumer — {@code IngestThroughputReporter} — is not, so a reporter that injected the worker directly
 * would fail to start the moment an operator turned the worker off. Holding the counters here is the same
 * shape {@link SpanLateness} uses for the same reason, and it keeps the reporter free of any conditional
 * bean and of the database.
 *
 * <p><b>Cumulative counters, deltas at the reporter.</b> The reporter subtracts, exactly as it does for the
 * writer's counters, so its "nothing moved, stay silent" guard can see rollup activity too.
 *
 * <h2>What each number answers</h2>
 *
 * <ul>
 *   <li>{@code claimed} vs {@code recomputed} — a gap is traces deleted under the worker, which is normal
 *       at retention boundaries and alarming anywhere else.
 *   <li>{@code settled} vs {@code claimed} — the shortfall is spans arriving mid-rollup. A persistently
 *       large one means the §7.4 windows are too short, and is the same finding the lateness histogram
 *       reports from the other side.
 *   <li>{@code failed} — recomputes that threw. Each leaves a trace with the reaper's fingerprint, so this
 *       number and {@code rearmed} should move together.
 *   <li>{@code rearmed} — traces the reaper recovered. Steady-state zero; anything else is a worker that
 *       died, or one slow enough that a sweep caught it mid-claim.
 *   <li>{@code queueDepth} / {@code overdueMs} — the queue as of the last sweep. Depth is traffic; overdue
 *       is the one that means the read surfaces are serving numbers older than they claim.
 * </ul>
 */
@Component
public class TraceRollupMetrics {

    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong recomputed = new AtomicLong();
    private final AtomicLong settled = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rearmed = new AtomicLong();

    private volatile int queueDepth;
    private volatile long overdueMs;

    /** One completed round of the worker's loop. */
    public void recordPass(int claimedTraces, int recomputedTraces, int settledTraces, int failedTraces) {
        claimed.addAndGet(claimedTraces);
        recomputed.addAndGet(recomputedTraces);
        settled.addAndGet(settledTraces);
        failed.addAndGet(failedTraces);
    }

    /** One completed reaper sweep: what it re-armed, and the queue it saw while it was there. */
    public void recordSweep(int rearmedTraces, int depth, long oldestOverdueMs) {
        rearmed.addAndGet(rearmedTraces);
        this.queueDepth = depth;
        this.overdueMs = oldestOverdueMs;
    }

    public long claimedTraces() {
        return claimed.get();
    }

    public long recomputedTraces() {
        return recomputed.get();
    }

    public long settledTraces() {
        return settled.get();
    }

    public long failedTraces() {
        return failed.get();
    }

    public long rearmedTraces() {
        return rearmed.get();
    }

    /**
     * Armed traces as of the last reaper sweep — a gauge, not a delta, and up to one sweep interval old.
     * A queue-depth graph does not need sub-minute resolution, and sampling it on the worker's own
     * one-second tick would be sixty counts a minute of a number nobody reads at that rate.
     */
    public int queueDepth() {
        return queueDepth;
    }

    /** How far past its deadline the oldest due trace was at the last sweep, in milliseconds. */
    public long overdueMs() {
        return overdueMs;
    }
}
