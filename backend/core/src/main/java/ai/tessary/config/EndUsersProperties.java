// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Install keys of the Users view ({@code tessary.end-users.*}). The feature has no capability: these keys are the only
 * controls. Cadences are {@code tessary.end-users.hits-heartbeat-ms} and {@code tessary.end-users.daily-heartbeat-ms},
 * bound by {@code @Scheduled}.
 */
@ConfigurationProperties(prefix = "tessary.end-users")
public record EndUsersProperties(

        /** Off stops the fold, the hit sweep and the daily job for the whole install. */
        @DefaultValue("true") boolean collectEnabled,

        /** Days of facts kept; the horizon is the larger of this and the project's trace TTL. */
        @DefaultValue("400") int historyDays,

        @DefaultValue("180") int judgeWindowDays,

        /** User-ID days in the judge window above which the snapshot is not computed. */
        @DefaultValue("5000000") long maxIdDays,

        /** Reclaim window for a daily job whose worker died mid-run. */
        @DefaultValue("1800") long leaseSeconds,

        @DefaultValue("5") int maxAttempts,

        @DefaultValue Guards guards) {

    /** A guard that is off removes only that guard; the rules behind it stay. */
    public record Guards(
            @DefaultValue("true") boolean notJudgedWithoutPastCheck,
            @DefaultValue("true") boolean cannotTellYet,
            @DefaultValue("true") boolean historyTooShort,
            @DefaultValue("true") boolean hitsNotComplete,
            @DefaultValue("true") boolean mixedIdFormats,
            @DefaultValue("true") boolean findingsFireTogether,
            @DefaultValue("true") boolean outageBanner) {}
}
