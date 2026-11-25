// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.ALWAYS_PRESENT_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.REACH_SHARE;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Hits and reach of spec 8.1 and 8.2. Every evidence day of an ID is kept, because each hit renews its exposure. Refs
 * with no user ID still count for the first and last evidence day and for the reach (reading 8). An alias member's
 * hits and traces are read under its representative.
 */
public final class IssueHits {

    private IssueHits() {}

    public static HitSet build(Facts facts, Map<Integer, Integer> aliasOf) {
        List<IssueInput> issues = facts.issues();
        List<Evidence> evidence = new ArrayList<>();
        issues.forEach(i -> evidence.add(new Evidence()));
        for (HitRow row : facts.hits()) {
            Evidence e = evidence.get(row.issue());
            Integer route = row.routeNo();
            Integer id = row.id();
            if (route != null) {
                e.routes.merge(route, (double) row.refs(), Double::sum);
            } else {
                double total = row.sessionRoutes().values().stream()
                        .mapToInt(Integer::intValue)
                        .sum();
                new TreeMap<>(row.sessionRoutes())
                        .forEach((r, n) -> e.routes.merge(r, row.refs() * n / total, Double::sum));
            }
            Integer first = e.first;
            Integer last = e.last;
            e.first = first == null ? row.day() : Math.min(first, row.day());
            e.last = last == null ? row.day() : Math.max(last, row.day());
            if (id != null) {
                e.days.computeIfAbsent(aliasOf.getOrDefault(id, id), u -> new TreeSet<>())
                        .add(row.day());
            }
        }
        Map<Integer, Integer> routeFirstDay = new HashMap<>();
        for (NullDay n : facts.nullDays()) routeFirstDay.merge(n.routeNo(), n.day(), Math::min);
        Map<String, LineageHits> byLineage = new LinkedHashMap<>();
        for (int i = 0; i < issues.size(); i++) {
            Evidence e = evidence.get(i);
            List<Integer> reach = reach(e.routes);
            Map<Integer, List<Integer>> hitDays = new TreeMap<>();
            e.days.forEach((u, ds) -> hitDays.put(u, List.copyOf(ds)));
            byLineage.put(
                    issues.get(i).lineageKey(),
                    new LineageHits(
                            hitDays,
                            e.first,
                            e.last,
                            reach,
                            issues.get(i).complete(),
                            alwaysPresent(e.first, reach, routeFirstDay, facts.t0())));
        }
        return new HitSet(byLineage);
    }

    /** The routes of the evidence, most first, until 90% of it is covered; ties keep the order the evidence came in. */
    public static List<Integer> reach(Map<Integer, Double> evidenceByRoute) {
        double total = evidenceByRoute.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(evidenceByRoute.entrySet());
        sorted.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
        List<Integer> out = new ArrayList<>();
        double covered = 0;
        for (Map.Entry<Integer, Double> e : sorted) {
            out.add(e.getKey());
            covered += e.getValue();
            if (covered >= REACH_SHARE * total) break;
        }
        return out;
    }

    /** {@code dose(u, d)}: the traces of each ID of {@code ids} on the reach, per day with one. */
    public static Map<Integer, NavigableMap<Integer, Integer>> dose(
            Facts facts, Map<Integer, Integer> aliasOf, List<Integer> reach, Set<Integer> ids) {
        Set<Integer> routes = new HashSet<>(reach);
        IdDays rows = facts.idDays();
        Map<Integer, NavigableMap<Integer, Integer>> out = new TreeMap<>();
        for (int i = 0; i < rows.id().length; i++) {
            int u = aliasOf.getOrDefault(rows.id()[i], rows.id()[i]);
            if (routes.contains(rows.routeNo()[i]) && ids.contains(u)) {
                out.computeIfAbsent(u, k -> new TreeMap<>()).merge(rows.day()[i], rows.traces()[i], Integer::sum);
            }
        }
        return out;
    }

    /** The first evidence is within 14 days of the first traffic on the reach (else of {@code t0}), or of {@code t0}. */
    private static boolean alwaysPresent(
            @Nullable Integer first, List<Integer> reach, Map<Integer, Integer> routeFirstDay, int t0) {
        if (first == null) return false;
        Integer start = null;
        for (int r : reach) {
            Integer day = routeFirstDay.get(r);
            if (day != null) start = start == null ? day : Math.min(start, day);
        }
        if (start == null) start = t0;
        return first - start < ALWAYS_PRESENT_DAYS || first - t0 < ALWAYS_PRESENT_DAYS;
    }

    private static final class Evidence {
        final Map<Integer, Double> routes = new LinkedHashMap<>();
        final Map<Integer, TreeSet<Integer>> days = new TreeMap<>();

        @Nullable
        Integer first;

        @Nullable
        Integer last;
    }
}
