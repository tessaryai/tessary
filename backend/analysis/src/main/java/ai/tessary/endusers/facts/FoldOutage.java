// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

/**
 * The tracing-outage flag of a folded day (level 2 guard): every route fell under 30% of its median daily trace count
 * over the 28 days before. A route with no median and no traffic today is ignored; a route with no median that carries
 * traffic today is not down, so it keeps the day from being an outage. With no route that has a median there is no
 * flag.
 */
public final class FoldOutage {

    public static final int MEDIAN_DAYS = 28;
    public static final double SHARE = 0.3;

    private FoldOutage() {}

    /**
     * @param dayTracesByRoute the day's traces per route number
     * @param previousDaysByRoute per route number, its trace count on each of the {@link #MEDIAN_DAYS} days before, 0
     *     for a day without traces
     */
    public static boolean outage(Map<Integer, Integer> dayTracesByRoute, Map<Integer, int[]> previousDaysByRoute) {
        TreeSet<Integer> routes = new TreeSet<>(dayTracesByRoute.keySet());
        routes.addAll(previousDaysByRoute.keySet());
        boolean anyMedian = false;
        for (int route : routes) {
            int today = dayTracesByRoute.getOrDefault(route, 0);
            int[] previous = previousDaysByRoute.get(route);
            double median = previous == null ? 0 : median(previous);
            if (median > 0) {
                anyMedian = true;
                if (today >= SHARE * median) return false;
            } else if (today > 0) {
                return false;
            }
        }
        return anyMedian;
    }

    private static double median(int[] counts) {
        int[] sorted = counts.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
