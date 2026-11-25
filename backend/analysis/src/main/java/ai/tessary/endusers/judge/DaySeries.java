// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.SETUP_PAIR_DAYS;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.RouteClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The day series of spec 2: paced, automation and set-up days of each ID, its basis, and whether it was present before
 * the data started. An alias member's rows are read under its representative. The IDs are those with a day row.
 */
public final class DaySeries {

    private static final int SLOTS_PER_DAY = 96;
    private static final int LOW_SLOTS = 64;
    private static final int ROUTE_ONLY = SLOTS_PER_DAY;

    private DaySeries() {}

    public static Series build(Facts facts, Map<Integer, Integer> aliasOf) {
        IdDays rows = facts.idDays();
        RouteClasses classes = facts.storedRouteClasses();
        Map<Integer, int[]> byRep = rowsByRepresentative(rows, aliasOf);
        Map<Integer, Integer> firstSeen = firstSeen(facts, aliasOf, byRep);
        int t0Id = firstSeen.values().stream().mapToInt(Integer::intValue).min().orElse(facts.t0());
        Map<Integer, IdSeries> byId = new TreeMap<>();
        byRep.forEach((u, idx) -> byId.put(u, idSeries(rows, classes, idx, firstSeen.get(u), t0Id)));
        return new Series(t0Id, byId);
    }

    private static IdSeries idSeries(IdDays rows, RouteClasses classes, int[] idx, int firstSeen, int t0Id) {
        SortedSet<Integer> paced = new TreeSet<>();
        SortedSet<Integer> auto = new TreeSet<>();
        SortedSet<Integer> all = new TreeSet<>();
        Map<Long, Integer> pairFirst = new HashMap<>();
        for (int i : idx) {
            int day = rows.day()[i];
            int route = rows.routeNo()[i];
            RouteClass rc = classOf(classes, route);
            all.add(day);
            if (isPaced(rc, classes.lenient())) {
                paced.add(day);
            } else if (rc == RouteClass.S || rc == RouteClass.B) {
                auto.add(day);
                if (rc == RouteClass.B) {
                    pairFirst.merge(pair(route, ROUTE_ONLY), day, Math::min);
                } else {
                    for (int slot = 0; slot < SLOTS_PER_DAY; slot++) {
                        if (hasSlot(rows, i, slot)) pairFirst.merge(pair(route, slot), day, Math::min);
                    }
                }
            }
        }
        SortedSet<Integer> setup = new TreeSet<>();
        if (!pairFirst.isEmpty()) {
            int firstAuto = auto.first();
            for (int day : pairFirst.values()) {
                setup.add(day <= firstAuto + SETUP_PAIR_DAYS - 1 ? firstAuto : day);
            }
        }
        ActivityBasis basis;
        SortedSet<Integer> days = new TreeSet<>();
        if (!paced.isEmpty()) {
            basis = classes.lenient() ? ActivityBasis.LENIENT : ActivityBasis.HUMAN;
            days.addAll(paced);
            days.addAll(setup);
        } else if (!auto.isEmpty()) {
            basis = ActivityBasis.UNATTENDED;
            days.addAll(auto);
            days.addAll(setup);
        } else {
            basis = ActivityBasis.MACHINE;
            days.addAll(all);
        }
        return new IdSeries(
                firstSeen,
                List.copyOf(days),
                basis,
                List.copyOf(paced),
                List.copyOf(auto),
                List.copyOf(setup),
                firstSeen <= t0Id + 1,
                medianGap(auto));
    }

    private static @Nullable Double medianGap(SortedSet<Integer> days) {
        if (days.size() < 2) return null;
        Integer[] d = days.toArray(new Integer[0]);
        double[] gaps = new double[d.length - 1];
        for (int i = 0; i < gaps.length; i++) gaps[i] = d[i + 1] - d[i];
        return Stats.median(gaps);
    }

    /** The stored first day of the ID, the earliest over its alias members. */
    private static Map<Integer, Integer> firstSeen(
            Facts facts, Map<Integer, Integer> aliasOf, Map<Integer, int[]> byRep) {
        Map<Integer, Integer> first = new HashMap<>();
        byRep.keySet().forEach(u -> first.put(u, facts.ids().get(u).firstDay()));
        aliasOf.forEach((member, rep) -> {
            if (first.containsKey(rep)) first.merge(rep, facts.ids().get(member).firstDay(), Math::min);
        });
        return first;
    }

    /**
     * Row indexes per representative, in day order. Rows are ordered by key index and day, so only a representative
     * with alias members needs a sort.
     */
    static Map<Integer, int[]> rowsByRepresentative(IdDays rows, Map<Integer, Integer> aliasOf) {
        Map<Integer, List<int[]>> ranges = new TreeMap<>();
        int n = rows.id().length;
        int start = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || rows.id()[i] != rows.id()[start]) {
                int id = rows.id()[start];
                ranges.computeIfAbsent(aliasOf.getOrDefault(id, id), u -> new ArrayList<>())
                        .add(new int[] {start, i});
                start = i;
            }
        }
        Map<Integer, int[]> byRep = new TreeMap<>();
        ranges.forEach((u, parts) -> {
            int size = parts.stream().mapToInt(p -> p[1] - p[0]).sum();
            int[] idx = new int[size];
            int at = 0;
            for (int[] p : parts) {
                for (int i = p[0]; i < p[1]; i++) idx[at++] = i;
            }
            if (parts.size() > 1) {
                idx = Arrays.stream(idx)
                        .boxed()
                        .sorted(Comparator.comparingInt(i -> rows.day()[i]))
                        .mapToInt(Integer::intValue)
                        .toArray();
            }
            byRep.put(u, idx);
        });
        return byRep;
    }

    static RouteClass classOf(RouteClasses classes, int routeNo) {
        Route route = classes.byRoute().get(routeNo);
        if (route == null) throw new IllegalStateException("route " + routeNo + " has day rows but no class");
        return route.routeClass();
    }

    static boolean isPaced(RouteClass rc, boolean lenient) {
        return rc == RouteClass.A || rc == RouteClass.A2 || (lenient && (rc == RouteClass.B || rc == RouteClass.M));
    }

    private static boolean hasSlot(IdDays rows, int i, int slot) {
        return slot < LOW_SLOTS
                ? (rows.slotMaskLow()[i] >>> slot & 1L) != 0
                : (rows.slotMaskHigh()[i] >>> (slot - LOW_SLOTS) & 1) != 0;
    }

    private static long pair(int route, int slot) {
        return (long) route * (SLOTS_PER_DAY + 1) + slot;
    }
}
