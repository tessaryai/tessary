// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the alerting engine ({@code tessary.alert.*}). The worker skips orgs without the alerts entitlement.
 * Business cadence lives per tenant ({@code signal_alert}, {@code project_alert_config}); these crons are fallbacks
 * when a row has none.
 */
@ConfigurationProperties(prefix = "tessary.alert")
public record AlertProperties(

        /** Spring 6-field cron; daily 08:00. */
        @DefaultValue("0 0 8 * * *") String defaultDigestCron,

        @DefaultValue("UTC") String cronZone,

        /**
         * Public SPA origin for case links in alert messages. Empty in local development, where omitting the link beats
         * sending one that goes nowhere.
         */
        @DefaultValue("") String appBaseUrl) {}
