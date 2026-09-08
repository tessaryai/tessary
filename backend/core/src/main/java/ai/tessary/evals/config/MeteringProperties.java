// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the async usage-metering worker, bound from {@code evals.metering.*}.
 * Metering is infrastructure feeding billing quotas and runs unconditionally (no enablement env gate);
 * defaults live here in code, auto-registered via {@code @ConfigurationPropertiesScan}.
 *
 * <p>{@code MeteringWorker}'s poll cadence is bound directly by {@code @Scheduled} from
 * {@code evals.metering.heartbeat-ms}. {@link #claimBatch} bounds how many per-(project,
 * bucket) jobs one heartbeat claims; the {@code FOR UPDATE SKIP LOCKED} claim spreads the rest across
 * instances and the next heartbeat picks up the remainder. {@link #leaseSeconds} is the reclaim window
 * for a job a worker took but died before finishing.
 */
@Component
@ConfigurationProperties(prefix = "evals.metering")
public class MeteringProperties {

    /**
     * Whether to produce the {@code storage} usage unit. Default off: storage is a LEVEL
     * snapshot whose billable basis (rows vs bytes vs retention-days) is a product decision tied to billing,
     * so the slot stays reserved until a deployment ratifies the basis and opts in. The other four units
     * always meter.
     */
    private boolean storageEnabled = false;

    /** How many per-(project, bucket) jobs one heartbeat claims; SKIP-LOCKED spreads the rest. */
    private int claimBatch = 50;

    /** Reclaim window (seconds) for a job a worker claimed but did not finish (e.g. crashed mid-aggregation). */
    private long leaseSeconds = 600;

    /**
     * Platform-funded spend, in USD, that one org may burn in a day before the daily operator report
     * escalates its line from INFO to WARN.
     *
     * <p><b>This is a threshold, not a cap.</b> Launch decision D6 leaves platform-paid LLM deliberately
     * uncapped — telemetry, no ceiling — and H4 asks only that a cap stay a decision we can take on
     * evidence rather than one we are forced into. Crossing this changes a log level and nothing else: no
     * request is refused and no lane is stopped. Zero or negative disables the escalation entirely.
     */
    private double spendWarnUsdPerOrgPerDay = 25.0;

    /** How many orgs the daily report lists. Ordered by spend, so the tail is the cheap half. */
    private int spendReportOrgLimit = 50;

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

    public double getSpendWarnUsdPerOrgPerDay() {
        return spendWarnUsdPerOrgPerDay;
    }

    public void setSpendWarnUsdPerOrgPerDay(double v) {
        this.spendWarnUsdPerOrgPerDay = v;
    }

    public int getSpendReportOrgLimit() {
        return spendReportOrgLimit;
    }

    public void setSpendReportOrgLimit(int v) {
        this.spendReportOrgLimit = v;
    }
}
