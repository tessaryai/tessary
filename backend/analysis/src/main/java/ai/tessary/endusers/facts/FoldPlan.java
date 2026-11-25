// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Which closed UTC days a fold run replaces (plan 3.2, stage 1). The retention cut falls inside the day {@code today -
 * TTL}, so that day is partly deleted; one more day of margin covers a run that crosses midnight. Days older than the
 * oldest replaceable day keep the rows folded while they were whole. A TTL of 0 keeps traces for good and has no edge.
 */
final class FoldPlan {

    static final int REFOLD_DAYS = 7;
    static final int EDGE_MARGIN_DAYS = 1;

    private FoldPlan() {}

    /** Null when the TTL is 0. */
    static @Nullable Integer oldestReplaceableDay(int today, int ttlDays) {
        return ttlDays > 0 ? today - ttlDays + 1 + EDGE_MARGIN_DAYS : null;
    }

    /**
     * The last {@link #REFOLD_DAYS} closed days, every day from {@code startDay} on, and the changed days, all before
     * {@code today} and not older than {@code oldest}, oldest first.
     *
     * @param startDay the day after the last fold, or the first trace day on the first run; null when neither exists
     */
    static List<Integer> days(int today, @Nullable Integer oldest, @Nullable Integer startDay, Set<Integer> changed) {
        int lo = oldest == null ? Integer.MIN_VALUE : oldest;
        TreeSet<Integer> days = new TreeSet<>();
        int from = startDay == null ? today - REFOLD_DAYS : Math.min(startDay, today - REFOLD_DAYS);
        for (int d = Math.max(lo, from); d < today; d++) days.add(d);
        changed.stream().filter(d -> d >= lo && d < today).forEach(days::add);
        return List.copyOf(days);
    }

    /** The days that aged out of the trace TTL after the last fold and before any run folded them. */
    static List<Integer> gapDays(@Nullable Integer lastFolded, @Nullable Integer oldest) {
        if (lastFolded == null || oldest == null) return List.of();
        return IntStream.range(lastFolded + 1, oldest).boxed().toList();
    }
}
