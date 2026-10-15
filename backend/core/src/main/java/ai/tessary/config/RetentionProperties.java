// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention defaults and per-pass work bounds; a {@code retention_policy} row overrides these per project. A TTL of
 * {@code 0} keeps forever. {@link #getBatchSize} caps one statement and {@link #getMaxBatchesPerSweep} one
 * project-signal per pass, so a backlog drains over passes.
 */
@ConfigurationProperties(prefix = "tessary.retention")
public record RetentionProperties(

        /** Off means nothing is ever deleted. */
        @DefaultValue("true") boolean enabled,

        /** Bounds lock duration, not total work. */
        @DefaultValue("5000") int batchSize,

        /** Bounds total work, so a backlog drains over passes. */
        @DefaultValue("20") int maxBatchesPerSweep,

        /** Everything that cascades from a trace. 0 = keep forever. */
        @DefaultValue("90") int traceTtlDays,

        /** Matched to the traces default: a detection that outlives its span renders as a row nobody can open. */
        @DefaultValue("90") int detectionTtlDays,

        /**
         * Not a TTL: bytes are stored before the transaction that files their {@code media_ref} rows, so a fresh image
         * is legitimately unreferenced for a batch's length.
         */
        @DefaultValue("24") int mediaGraceHours) {}
