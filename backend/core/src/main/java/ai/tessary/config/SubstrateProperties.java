// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Defaults live here in code; no yaml entries needed. */
@Component
@ConfigurationProperties(prefix = "tessary.ingest.substrate")
public class SubstrateProperties {

    /**
     * Measured payload bytes, reserved before enqueue and released exactly after drain, not an average-size estimate
     * (Jaeger #2715 shows averages under-count). A proxy for retained heap, so the default leaves generous headroom.
     */
    private long queueMaxBytes = 64L * 1024 * 1024;

    /**
     * The shed fires after decode, which is how a full queue still ended in an OOM inside protobuf parsing; this
     * refuses before decode. Set to {@code 0} or {@code >= 1} to disable.
     */
    private double refuseAboveQueueFraction = 0.8;

    /** Retries are safe: the write path is idempotent. */
    private int maxAttempts = 3;

    /** Linear: attempt N waits N * backoff. */
    private long retryBackoffMs = 250;

    /** Bounds one pass, not the backlog: both resolvers' partial indexes stay near-empty by construction. */
    private int resolverBatchSize = 500;

    /** A runaway pass otherwise holds row locks and the scheduler thread; a cancelled pass changes nothing. */
    private int resolverStatementTimeoutSeconds = 30;

    public long getQueueMaxBytes() {
        return queueMaxBytes;
    }

    public void setQueueMaxBytes(long v) {
        this.queueMaxBytes = v;
    }

    public double getRefuseAboveQueueFraction() {
        return refuseAboveQueueFraction;
    }

    public void setRefuseAboveQueueFraction(double v) {
        this.refuseAboveQueueFraction = v;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int v) {
        this.maxAttempts = v;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long v) {
        this.retryBackoffMs = v;
    }

    public int getResolverBatchSize() {
        return resolverBatchSize;
    }

    public void setResolverBatchSize(int v) {
        this.resolverBatchSize = v;
    }

    public int getResolverStatementTimeoutSeconds() {
        return resolverStatementTimeoutSeconds;
    }

    public void setResolverStatementTimeoutSeconds(int v) {
        this.resolverStatementTimeoutSeconds = v;
    }

    /** Bounds one round, not the backlog. */
    private int rollupClaimLimit = 500;

    /** Too short only costs a redundant recompute; too long leaves a crashed worker's traces stale and unsettled. */
    private int rollupReapGraceSeconds = 300;

    /** A stalled rollup queue is invisible from the read surfaces, which keep serving the last numbers. */
    private long rollupStaleAfterMs = 60_000;

    public int getRollupClaimLimit() {
        return rollupClaimLimit;
    }

    public void setRollupClaimLimit(int v) {
        this.rollupClaimLimit = v;
    }

    public int getRollupReapGraceSeconds() {
        return rollupReapGraceSeconds;
    }

    public void setRollupReapGraceSeconds(int v) {
        this.rollupReapGraceSeconds = v;
    }

    public long getRollupStaleAfterMs() {
        return rollupStaleAfterMs;
    }

    public void setRollupStaleAfterMs(long v) {
        this.rollupStaleAfterMs = v;
    }
}
