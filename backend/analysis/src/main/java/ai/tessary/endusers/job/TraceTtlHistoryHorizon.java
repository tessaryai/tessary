// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.config.EndUsersProperties;
import ai.tessary.retention.RetentionResolver;
import java.util.OptionalInt;

/**
 * The larger of the project's trace TTL and {@code tessary.end-users.history-days}. A trace TTL of 0 keeps traces for
 * good, so the facts are kept for good too.
 */
public final class TraceTtlHistoryHorizon implements EndUserHistoryHorizon {

    private final RetentionResolver retention;
    private final EndUsersProperties props;

    public TraceTtlHistoryHorizon(RetentionResolver retention, EndUsersProperties props) {
        this.retention = retention;
        this.props = props;
    }

    @Override
    public OptionalInt days(String projectId) {
        int ttlDays = TraceTtl.days(retention, projectId);
        return ttlDays > 0 ? OptionalInt.of(Math.max(ttlDays, props.historyDays())) : OptionalInt.empty();
    }
}
