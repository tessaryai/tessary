// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the async usage-metering worker, bound from {@code tessary.metering.*}.
 * Metering is infrastructure feeding billing quotas and runs unconditionally (no enablement env gate);
 * defaults live here in code, auto-registered via {@code @ConfigurationPropertiesScan}.
 *
 * <p>{@code MeteringWorker}'s poll cadence is bound directly by {@code @Scheduled} from
 * {@code tessary.metering.heartbeat-ms}. {@link #claimBatch} bounds how many per-(project,
 * bucket) jobs one heartbeat claims; the {@code FOR UPDATE SKIP LOCKED} claim spreads the rest across
 * instances and the next heartbeat picks up the remainder. {@link #leaseSeconds} is the reclaim window
 * for a job a worker took but died before finishing.
 */
@ConfigurationProperties(prefix = "tessary.metering")
public record MeteringProperties(

        /**
         * Whether to produce the {@code storage} usage unit. Default off: storage is a point-in-time
         * snapshot whose billable basis (rows vs bytes vs retention-days) is a product decision tied to
         * billing, so the slot stays reserved until a deployment ratifies the basis and opts in. The other
         * four units always meter.
         */
        @DefaultValue("false") boolean storageEnabled,

        /** How many per-(project, bucket) jobs one heartbeat claims; SKIP-LOCKED spreads the rest. */
        @DefaultValue("50") int claimBatch,

        /** Reclaim window (seconds) for a job a worker claimed but did not finish (e.g. crashed mid-aggregation). */
        @DefaultValue("600") long leaseSeconds) {}
