// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.AGGREGATE_HOURS;
import static ai.tessary.endusers.judge.JudgeConstants.AGGREGATE_LONG_DAY_HOURS;
import static ai.tessary.endusers.judge.JudgeConstants.AGGREGATE_LONG_DAY_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.AGGREGATE_SESSIONS;
import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.FLAG_MIN_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.GROUP_HINT_HOURS;
import static ai.tessary.endusers.judge.JudgeConstants.GROUP_HINT_SESSIONS;
import static ai.tessary.endusers.judge.JudgeConstants.ODD_SHAPE_KEY_IDS_BELOW;
import static ai.tessary.endusers.judge.JudgeConstants.ODD_SHAPE_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.ODD_SHAPE_TRACE_FACTOR;
import static ai.tessary.endusers.judge.JudgeConstants.SERVICE_CV;
import static ai.tessary.endusers.judge.JudgeConstants.SERVICE_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.SERVICE_PRESENCE;
import static ai.tessary.endusers.judge.JudgeConstants.TOKEN_LINK_DAYS;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.RouteClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The per-ID flags of spec 3.1. The size tests read routes of class A, A2, M and B; the group hint reads A, A2 and M
 * (reading 2 of spec 5, lenient mode included). Hours and sessions of one day are joined over routes and versions.
 */
public final class IdentityFlags {

    private IdentityFlags() {}

    /**
     * @param sizeDays days with a trace on an A, A2, M or B route: the ID's weight in every {@code pd} share
     */
    public record Flags(
            boolean aggregate, boolean groupHint, boolean service, boolean oddShape, boolean tokenLink, int sizeDays) {}

    /** Flags per key index of {@code series}. */
    public static Map<Integer, Flags> flags(Facts facts, Map<Integer, Integer> aliasOf, Series series) {
        IdDays rows = facts.idDays();
        RouteClasses classes = facts.storedRouteClasses();
        Map<Integer, DayTallies> tallies = new TreeMap<>();
        DaySeries.rowsByRepresentative(rows, aliasOf).forEach((u, idx) -> tallies.put(u, tally(rows, classes, idx)));
        Set<Integer> oddShape = oddShape(facts.ids(), tallies);
        Map<Integer, Flags> flags = new TreeMap<>();
        tallies.forEach((u, t) -> {
            IdSeries s = series.byId().get(u);
            boolean aggregate = aggregate(t);
            flags.put(
                    u,
                    new Flags(
                            aggregate,
                            !aggregate && groupHint(t),
                            !aggregate && service(t, s),
                            oddShape.contains(u),
                            tokenLink(facts.ids().get(u), s),
                            t.sizeHours.size()));
        });
        return flags;
    }

    private static boolean aggregate(DayTallies t) {
        if (t.sizeHours.size() < FLAG_MIN_DAYS) return false;
        long longDays =
                t.sizeHours.stream().filter(h -> h >= AGGREGATE_LONG_DAY_HOURS).count();
        return median(t.sizeHours) >= AGGREGATE_HOURS
                || (double) longDays / t.sizeHours.size() >= AGGREGATE_LONG_DAY_SHARE
                || median(t.sizeSessions) >= AGGREGATE_SESSIONS;
    }

    private static boolean groupHint(DayTallies t) {
        return t.hintHours.size() >= FLAG_MIN_DAYS
                && (median(t.hintHours) >= GROUP_HINT_HOURS || median(t.hintSessions) >= GROUP_HINT_SESSIONS);
    }

    private static boolean service(DayTallies t, IdSeries s) {
        int days = t.dayTraces.size();
        if (days < SERVICE_DAYS) return false;
        int firstDay = t.days.getFirst();
        int life = t.days.getLast() - firstDay + 1;
        if ((double) days / life < SERVICE_PRESENCE) return false;
        double mean = t.dayTraces.stream().mapToInt(Integer::intValue).average().orElseThrow();
        double variance =
                t.dayTraces.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / days;
        double cv = Math.sqrt(variance) / mean;
        boolean lateSetup = s.setupDays().stream().anyMatch(d -> d > firstDay + DAYS_PER_WEEK);
        return cv < SERVICE_CV || (!t.personal && !lateSetup);
    }

    private static boolean tokenLink(IdInfo info, IdSeries s) {
        return info.sessions() > 0 && info.sessionTokenLink() && s.days().getLast() - s.firstSeen() < TOKEN_LINK_DAYS;
    }

    private static Set<Integer> oddShape(List<IdInfo> ids, Map<Integer, DayTallies> tallies) {
        Set<Integer> odd = new HashSet<>();
        if (tallies.size() < ODD_SHAPE_MIN_IDS) return odd;
        double medianTraces = Stats.median(
                tallies.values().stream().mapToDouble(t -> t.traces).toArray());
        Map<String, Integer> perShape = new HashMap<>();
        tallies.keySet().forEach(u -> perShape.merge(shapeKey(ids.get(u)), 1, Integer::sum));
        tallies.forEach((u, t) -> {
            if (perShape.get(shapeKey(ids.get(u))) < ODD_SHAPE_KEY_IDS_BELOW
                    && t.traces > ODD_SHAPE_TRACE_FACTOR * medianTraces) {
                odd.add(u);
            }
        });
        return odd;
    }

    static String shapeKey(IdInfo info) {
        return info.shapePrefix() + "/" + info.shapeLength();
    }

    /** The reference reads 0 for a median of no session days. */
    private static double median(List<Integer> xs) {
        return xs.isEmpty()
                ? 0
                : Stats.median(xs.stream().mapToDouble(Integer::intValue).toArray());
    }

    private static DayTallies tally(IdDays rows, RouteClasses classes, int[] idx) {
        DayTallies t = new DayTallies();
        Set<Integer> sizeSessions = new HashSet<>();
        Set<Integer> hintSessions = new HashSet<>();
        int at = 0;
        while (at < idx.length) {
            int day = rows.day()[idx[at]];
            int sizeHours = 0;
            int hintHours = 0;
            boolean sizeDay = false;
            boolean hintDay = false;
            int traces = 0;
            sizeSessions.clear();
            hintSessions.clear();
            for (; at < idx.length && rows.day()[idx[at]] == day; at++) {
                int i = idx[at];
                traces += rows.traces()[i];
                RouteClass rc = DaySeries.classOf(classes, rows.routeNo()[i]);
                if (rc == RouteClass.C || rc == RouteClass.S) continue;
                sizeDay = true;
                sizeHours |= rows.hourMask()[i];
                for (int h : rows.sessionHashes()[i]) sizeSessions.add(h);
                if (rc != RouteClass.B) {
                    hintDay = true;
                    hintHours |= rows.hourMask()[i];
                    for (int h : rows.sessionHashes()[i]) hintSessions.add(h);
                }
            }
            t.days.add(day);
            t.dayTraces.add(traces);
            t.traces += traces;
            if (sizeDay) {
                t.sizeHours.add(Integer.bitCount(sizeHours));
                if (!sizeSessions.isEmpty()) t.sizeSessions.add(sizeSessions.size());
            }
            if (hintDay) {
                t.personal = true;
                t.hintHours.add(Integer.bitCount(hintHours));
                if (!hintSessions.isEmpty()) t.hintSessions.add(hintSessions.size());
            }
        }
        return t;
    }

    /** One entry per day with any trace, in day order; size and hint lists hold only the days they read. */
    private static final class DayTallies {
        final List<Integer> days = new ArrayList<>();
        final List<Integer> dayTraces = new ArrayList<>();
        final List<Integer> sizeHours = new ArrayList<>();
        final List<Integer> sizeSessions = new ArrayList<>();
        final List<Integer> hintHours = new ArrayList<>();
        final List<Integer> hintSessions = new ArrayList<>();
        long traces;
        boolean personal;
    }
}
