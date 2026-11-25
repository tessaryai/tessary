// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.SilenceState;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared silence of spec 4.
 *
 * @param clock per key index, the spans in which its silence is weighted down
 */
public record SilenceResult(List<SilenceEvent> events, Map<Integer, List<ClockInterval>> clock) {

    /**
     * @param endDay the median first return day of a proven break; null otherwise
     * @param panel regular IDs of the scope in the event week
     * @param i share of the panel silent in the event week; {@code i0} the usual share
     * @param rho share of the members' silence that the break explains; {@code rhoHit} for freshly hit members
     * @param coverEnd the first day after the span the event covers
     */
    public record SilenceEvent(
            Scope scope,
            int startDay,
            @Nullable Integer endDay,
            SilenceState state,
            boolean provisional,
            int panel,
            double i,
            double i0,
            double rho,
            double rhoHit,
            List<Integer> members,
            int coverEnd) {}

    /** Days {@code [fromDay, toDay)} count {@code 1 - rho} each on the clock. */
    public record ClockInterval(int fromDay, int toDay, double rho) {}
}
