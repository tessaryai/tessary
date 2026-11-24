// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.SetAsideReason;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Flags, aliases and ID groups of spec 3.1 to 3.3. {@code pd} shares are shares of all ID-days (days with a trace on an
 * A, A2, M or B route).
 *
 * @param byId per key index left after aliasing
 * @param aliasOf alias member to its representative, both key indexes
 * @param groups statistics per group key ({@code prefix/length}, {@code prefix*} or {@code (app)})
 * @param app statistics of all IDs that are neither aggregate nor service
 * @param oneDayShareByShape share of old IDs seen on one day only, per shape key {@code prefix/length}
 * @param identShare share of traces on routes not of class C that carry a user ID
 * @param identShare28 the same over the last 28 days
 * @param linkShare share of IDs that copy a session token
 */
public record Identity(
        Map<Integer, IdIdentity> byId,
        Map<Integer, Integer> aliasOf,
        Map<String, GroupStats> groups,
        GroupStats app,
        Map<String, Double> oneDayShareByShape,
        double pdCovered,
        double pdAggregate,
        double pdGroupHint,
        double identShare,
        double identShare28,
        double linkShare) {

    /**
     * @param account the account cluster when clusters exist; null otherwise
     * @param setAside why the ID is not covered; null for a covered ID
     */
    public record IdIdentity(
            boolean aggregate,
            boolean groupHint,
            boolean service,
            boolean oddShape,
            boolean tokenLink,
            String groupKey,
            @Nullable Integer account,
            boolean covered,
            @Nullable SetAsideReason setAside) {}

    /**
     * Shares are of the old IDs of the group (first seen 28 or more days before {@code D}); null with none.
     *
     * @param returnLater share whose last series day is 7 or more days after the first ({@code L})
     */
    public record GroupStats(
            int ids,
            int old,
            @Nullable Double returnLater,
            @Nullable Double returnShare,
            @Nullable Double carryOver,
            @Nullable Double oneDayShare,
            boolean nonReturning) {}
}
