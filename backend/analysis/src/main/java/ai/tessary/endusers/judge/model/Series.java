// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.ActivityBasis;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The day series of spec 2. Keys are the key indexes left after aliasing: an alias member's days are in its
 * representative's entry.
 *
 * @param t0Id the first day with a trace that carries a user ID
 */
public record Series(int t0Id, Map<Integer, IdSeries> byId) {

    /**
     * Day lists are ascending.
     *
     * @param days the series days: paced and set-up days, else automation and set-up days, else every day
     * @param leftCensored first seen at most one day after {@code t0Id}, so never new
     * @param autoMedianGap median gap of the automation days; null with fewer than two
     */
    public record IdSeries(
            int firstSeen,
            List<Integer> days,
            ActivityBasis basis,
            List<Integer> pacedDays,
            List<Integer> autoDays,
            List<Integer> setupDays,
            boolean leftCensored,
            @Nullable Double autoMedianGap) {}
}
