// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Rollup worker and reaper counters for the {@code ingest.throughput} heartbeat. A bean of its own because the
 * producers are conditional and the reporter is not. Cumulative; the reporter takes deltas. A
 * {@code claimed}/{@code recomputed} gap is deletions under the worker; {@code settled} short of {@code claimed} is
 * spans landing mid-rollup; {@code rearmed} is steady-state zero.
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

    public void recordPass(int claimedTraces, int recomputedTraces, int settledTraces, int failedTraces) {
        claimed.addAndGet(claimedTraces);
        recomputed.addAndGet(recomputedTraces);
        settled.addAndGet(settledTraces);
        failed.addAndGet(failedTraces);
    }

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

    /** A gauge as of the last reaper sweep, which is resolution enough. */
    public int queueDepth() {
        return queueDepth;
    }

    public long overdueMs() {
        return overdueMs;
    }
}
