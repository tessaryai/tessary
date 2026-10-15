// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the alerting engine ({@code tessary.alert.*}). The worker skips orgs without the alerts entitlement.
 * Business cadence lives per tenant ({@code signal_alert}, {@code project_alert_config}); these crons are fallbacks
 * when a row has none.
 */
@Component
@ConfigurationProperties(prefix = "tessary.alert")
public class AlertProperties {

    /** Spring 6-field cron; daily 08:00. */
    private String defaultDigestCron = "0 0 8 * * *";

    private String cronZone = "UTC";

    /**
     * Public SPA origin for case links in alert messages. Empty in local development, where omitting the link beats
     * sending one that goes nowhere.
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

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String v) {
        this.appBaseUrl = v;
    }
}
