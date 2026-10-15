// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Defaults live here in code; no yaml entries needed. */
@ConfigurationProperties(prefix = "tessary.ingest.substrate")
public record SubstrateProperties(

        /**
         * Measured payload bytes, reserved before enqueue and released exactly after drain, not an average-size
         * estimate (Jaeger #2715 shows averages under-count). A proxy for retained heap, so the default leaves generous
         * headroom.
         */
        @DefaultValue("67108864") long queueMaxBytes,

        /**
         * The shed fires after decode, which is how a full queue still ended in an OOM inside protobuf parsing; this
         * refuses before decode. Set to {@code 0} or {@code >= 1} to disable.
         */
        @DefaultValue("0.8") double refuseAboveQueueFraction,

        /** Retries are safe: the write path is idempotent. */
        @DefaultValue("3") int maxAttempts,

        /** Linear: attempt N waits N * backoff. */
        @DefaultValue("250") long retryBackoffMs,

        /** Bounds one pass, not the backlog: both resolvers' partial indexes stay near-empty by construction. */
        @DefaultValue("500") int resolverBatchSize,

        /** A runaway pass otherwise holds row locks and the scheduler thread; a cancelled pass changes nothing. */
        @DefaultValue("30") int resolverStatementTimeoutSeconds,

        /** Bounds one round, not the backlog. */
        @DefaultValue("500") int rollupClaimLimit,

        /**
         * Too short only costs a redundant recompute; too long leaves a crashed worker's traces stale and unsettled.
         */
        @DefaultValue("300") int rollupReapGraceSeconds,

        /** A stalled rollup queue is invisible from the read surfaces, which keep serving the last numbers. */
        @DefaultValue("60000") long rollupStaleAfterMs) {}
