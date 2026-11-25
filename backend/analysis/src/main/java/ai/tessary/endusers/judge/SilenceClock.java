// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The clock of spec 4 steps 7 and 8, reading 10 of spec 5. A day inside a hold interval of an ID counts
 * {@code 1 - rho}; every other day counts 1, so with no event the clock is the calendar. Intervals of different events
 * that overlap in part each take their share (reading 9).
 */
public final class SilenceClock {

    private SilenceClock() {}

    /** The clock length of the days {@code [fromDay, toDay)}. */
    public static double length(List<ClockInterval> intervals, int fromDay, int toDay) {
        double length = toDay - fromDay;
        for (ClockInterval interval : intervals) {
            int overlap = Math.min(toDay, interval.toDay()) - Math.max(fromDay, interval.fromDay());
            if (overlap > 0) length -= interval.rho() * overlap;
        }
        return length;
    }

    /** The clock length of the days {@code [fromDay, toDay)} for the ID with key index {@code id}. */
    public static double length(SilenceResult silence, int id, int fromDay, int toDay) {
        return length(silence.clock().getOrDefault(id, List.of()), fromDay, toDay);
    }

    /**
     * The members of every event that covers {@code day} ({@code startDay <= day < coverEnd}). A past-check anchor
     * leaves them out of its records, because the live logic at that date would have held them differently.
     */
    public static Set<Integer> coveredAt(SilenceResult silence, int day) {
        Set<Integer> covered = new TreeSet<>();
        for (SilenceEvent event : silence.events()) {
            if (event.startDay() <= day && day < event.coverEnd()) covered.addAll(event.members());
        }
        return covered;
    }
}
