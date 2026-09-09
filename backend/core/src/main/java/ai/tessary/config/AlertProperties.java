// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the async alerting engine, bound from {@code tessary.alert.*}. The worker runs
 * unconditionally and skips orgs that lack the alerts entitlement, per project. Defaults live
 * here in code.
 *
 * <p>As in {@link ObserverProperties}, the business cadence lives per-tenant: a per-signal
 * threshold rule in {@code signal_alert} and a per-project digest/brief cron in
 * {@code project_alert_config}. The worker's poll cadence is bound directly by {@code @Scheduled}
 * from {@code tessary.alert.heartbeat-ms}; {@link #defaultDigestCron} and {@link #cronZone} are
 * fallback server defaults used when a per-row cron is absent.
 */
@Component
@ConfigurationProperties(prefix = "tessary.alert")
public class AlertProperties {

    /** Cadence used when an enabled project has no digest_cron of its own. Spring 6-field cron; daily 08:00. */
    private String defaultDigestCron = "0 0 8 * * *";

    /** Zone in which alert crons are evaluated (per-project zones are a future extension). */
    private String cronZone = "UTC";

    /** Lease duration for a claimed alert config row; a worker that dies mid-evaluation is reclaimed after this. */
    private long leaseSeconds = 300;

    /**
     * Public origin of the SPA, used to put a link to the case in an alert message
     * ({@code https://app.tessary.ai}). Empty by default and in local development, where there is
     * no stable public origin: better to omit the link than send one that goes nowhere.
     */
    private String appBaseUrl = "";

    public String getDefaultDigestCron() {
        return defaultDigestCron;
    }

    public void setDefaultDigestCron(String v) {
        this.defaultDigestCron = v;
    }

    public String getCronZone() {
        return cronZone;
    }

    public void setCronZone(String v) {
        this.cronZone = v;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long v) {
        this.leaseSeconds = v;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String v) {
        this.appBaseUrl = v;
    }
}
