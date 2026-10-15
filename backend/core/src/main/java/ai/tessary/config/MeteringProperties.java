// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the usage-metering worker ({@code tessary.metering.*}). Metering feeds billing quotas and always runs.
 * Poll cadence is {@code tessary.metering.heartbeat-ms}, bound by {@code @Scheduled}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.metering")
public class MeteringProperties {

    /**
     * Off by default: storage's billable basis (rows, bytes or retention-days) is an undecided billing question. The
     * other four units always meter.
     */
    private boolean storageEnabled = false;

    /** SKIP LOCKED spreads the rest across instances. */
    private int claimBatch = 50;

    /** Reclaim window for a job whose worker died mid-aggregation. */
    private long leaseSeconds = 600;

    public boolean isStorageEnabled() {
        return storageEnabled;
    }

    public void setStorageEnabled(boolean v) {
        this.storageEnabled = v;
    }

    public int getClaimBatch() {
        return claimBatch;
    }

    public void setClaimBatch(int v) {
        this.claimBatch = v;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long v) {
        this.leaseSeconds = v;
    }
}
