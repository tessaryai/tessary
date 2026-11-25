// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.TRACER_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_END_SLACK_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_LOOK_AHEAD_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_RECENT_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_REPLACED_LOW;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_SAMPLE;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_SOME_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_SOME_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.TRACER_Z;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.TracerVerdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The tracer test of spec 3.6, reading 6 of spec 5. A job key is (route, job key of the fact row) on a route of class
 * S; the row's job key already holds the input hash and the 15-minute slot. Owners are alias representatives. An ID's
 * first trace is its first seen day; its last trace is its last fact row day.
 */
public final class Tracer {

    private Tracer() {}

    public static TracerResult test(Facts facts, Series series, Identity identity) {
        int asOfDay = facts.asOfDay();
        IdDays rows = facts.idDays();
        RouteClasses classes = facts.storedRouteClasses();
        Map<Key, Map<Integer, Holding>> holdings = new HashMap<>();
        Map<Integer, Integer> lastDay = new HashMap<>();
        DaySeries.rowsByRepresentative(rows, identity.aliasOf()).forEach((u, idx) -> {
            int last = Integer.MIN_VALUE;
            for (int i : idx) {
                int day = rows.day()[i];
                last = Math.max(last, day);
                int route = rows.routeNo()[i];
                if (DaySeries.classOf(classes, route) != RouteClass.S) continue;
                for (long job : rows.jobKeys()[i]) {
                    holdings.computeIfAbsent(new Key(route, job), k -> new HashMap<>())
                            .computeIfAbsent(u, x -> new Holding())
                            .add(day);
                }
            }
            lastDay.put(u, last);
        });

        Map<Integer, Ending> endings = new TreeMap<>();
        for (Map<Integer, Holding> byOwner : holdings.values()) {
            List<Owner> owners = new ArrayList<>();
            byOwner.forEach((u, h) -> {
                if (h.days >= TRACER_DAYS) owners.add(new Owner(h.first, h.last, u));
            });
            if (owners.isEmpty() || !exclusive(owners)) continue;
            for (Owner o : owners) {
                int ownerLast = lastDay.get(o.u());
                if (o.last() > asOfDay - TRACER_LOOK_AHEAD_DAYS || ownerLast > o.last() + TRACER_END_SLACK_DAYS) {
                    continue;
                }
                Integer successor = null;
                for (Owner next : owners) {
                    if (next.u() != o.u()
                            && next.first() > o.last()
                            && next.first() <= o.last() + TRACER_LOOK_AHEAD_DAYS
                            && series.byId().get(next.u()).firstSeen() > ownerLast) {
                        successor = successor == null ? next.first() : Math.min(successor, next.first());
                    }
                }
                endings.computeIfAbsent(o.u(), u -> new Ending()).add(o.last(), successor);
            }
        }

        int recentFrom = asOfDay - TRACER_RECENT_DAYS;
        int recentEnded = 0;
        int recentContinued = 0;
        int allContinued = 0;
        Integer firstContinued = null;
        for (Ending e : endings.values()) {
            boolean continued = e.successor != null;
            if (continued) {
                allContinued++;
                firstContinued = firstContinued == null ? e.successor : Math.min(firstContinued, e.successor);
            }
            if (e.lastKeyDay >= recentFrom) {
                recentEnded++;
                if (continued) recentContinued++;
            }
        }
        boolean recent = recentEnded >= TRACER_SAMPLE;
        int ended = recent ? recentEnded : endings.size();
        int continued = recent ? recentContinued : allContinued;
        TracerVerdict verdict = verdict(ended, continued);
        if (verdict != TracerVerdict.SOME_IDS_REPLACED || firstContinued == null) {
            return new TracerResult(verdict, ended, continued, recent, null, null, Set.of());
        }
        int from = firstContinued;
        Set<Integer> born = new TreeSet<>();
        series.byId().forEach((u, s) -> {
            if (s.firstSeen() >= from) born.add(u);
        });
        return new TracerResult(
                verdict, ended, continued, recent, from, 1 - Stats.wilsonUp(continued, ended, TRACER_Z), born);
    }

    static TracerVerdict verdict(int ended, int continued) {
        if (ended >= TRACER_SAMPLE && Stats.wilsonLow(continued, ended, TRACER_Z) >= TRACER_REPLACED_LOW) {
            return TracerVerdict.IDS_REPLACED;
        }
        if (continued >= TRACER_SOME_MIN && ended > 0 && (double) continued / ended >= TRACER_SOME_SHARE) {
            return TracerVerdict.SOME_IDS_REPLACED;
        }
        return ended >= TRACER_SAMPLE ? TracerVerdict.NO_CHANGE_SEEN : TracerVerdict.CANNOT_TEST;
    }

    /** No two owners' first-to-last key day ranges overlap: sorted by first day, each starts after the last ends. */
    private static boolean exclusive(List<Owner> owners) {
        owners.sort(Comparator.comparingInt(Owner::first)
                .thenComparingInt(Owner::last)
                .thenComparingInt(Owner::u));
        for (int i = 0; i + 1 < owners.size(); i++) {
            if (owners.get(i).last() >= owners.get(i + 1).first()) return false;
        }
        return true;
    }

    private record Key(int route, long job) {}

    private record Owner(int first, int last, int u) {}

    /** Key days of one owner. Rows of a representative come in day order, so a repeated day is the previous one. */
    private static final class Holding {
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        int days;

        void add(int day) {
            if (day != last) days++;
            first = Math.min(first, day);
            last = Math.max(last, day);
        }
    }

    /** Per ID, the latest ending over its tracers, and whether any of them went on. */
    private static final class Ending {
        int lastKeyDay = Integer.MIN_VALUE;

        @Nullable
        Integer successor;

        void add(int keyLastDay, @Nullable Integer successorFirstDay) {
            lastKeyDay = Math.max(lastKeyDay, keyLastDay);
            if (successorFirstDay != null) {
                successor = successor == null ? successorFirstDay : Math.min(successor, successorFirstDay);
            }
        }
    }
}
