// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the usage-metering worker ({@code tessary.metering.*}). Metering feeds billing quotas and always runs.
 * Poll cadence is {@code tessary.metering.heartbeat-ms}, bound by {@code @Scheduled}.
 */
@ConfigurationProperties(prefix = "tessary.metering")
public record MeteringProperties(

        /** SKIP LOCKED spreads the rest across instances. */
        @DefaultValue("50") int claimBatch,

        /** Reclaim window for a job whose worker died mid-aggregation. */
        @DefaultValue("600") long leaseSeconds) {}
