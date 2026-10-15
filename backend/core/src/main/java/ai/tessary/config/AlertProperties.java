// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
@ConfigurationProperties(prefix = "tessary.alert")
public record AlertProperties(

        /** Cadence used when an enabled project has no digest_cron of its own. Spring 6-field cron; daily 08:00. */
        @DefaultValue("0 0 8 * * *") String defaultDigestCron,

        /** Zone in which alert crons are evaluated (per-project zones are a future extension). */
        @DefaultValue("UTC") String cronZone,

        /**
         * Public origin of the SPA, used to put a link to the case in an alert message
         * ({@code https://app.tessary.ai}). Empty by default and in local development, where there is
         * no stable public origin: better to omit the link than send one that goes nowhere.
         */
        @DefaultValue("") String appBaseUrl) {}
