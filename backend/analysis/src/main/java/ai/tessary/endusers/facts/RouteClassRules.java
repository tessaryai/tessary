// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static ai.tessary.endusers.judge.JudgeConstants.APP_SENDS_INPUT_MIN_TRACES;
import static ai.tessary.endusers.judge.JudgeConstants.APP_SENDS_INPUT_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.FOLLOW_CUT;
import static ai.tessary.endusers.judge.JudgeConstants.FOLLOW_DELAYS_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.PACE_DQ90_SECONDS;
import static ai.tessary.endusers.judge.JudgeConstants.PACE_SPREAD;
import static ai.tessary.endusers.judge.JudgeConstants.PAIR_C60_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.PAIR_CONCENTRATION;
import static ai.tessary.endusers.judge.JudgeConstants.PAIR_SCHEDULE_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.PAIR_TEST_MIN_TRACES;
import static ai.tessary.endusers.judge.JudgeConstants.ROUTE_CLASS_MARGIN;
import static ai.tessary.endusers.judge.JudgeConstants.ROUTE_CO_ANCHOR;
import static ai.tessary.endusers.judge.JudgeConstants.ROUTE_INPUT_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.ROUTE_NULL_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.ROUTE_PARENT_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.SCHEDULE_MIN5_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.SCHEDULE_SEC0_SHARE;

import ai.tessary.endusers.judge.Stats;
import ai.tessary.endusers.judge.Stats.CircPairs;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.model.RouteClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The route classes of spec section 1 as pure functions: the statistics of a route, the pair test, class rules 1 to 6,
 * the stored-class margin, the anchor test and the lenient fallback.
 */
public final class RouteClassRules {

    private RouteClassRules() {}

    /** The pair statistic of one ID on one route: pairs of its traces on different days. */
    public static PairTest pairTest(int[] minutes, int[] days) {
        CircPairs all = Stats.circPairs(minutes);
        int within60 = all.within60();
        int within2 = all.within2();
        Map<Integer, List<Integer>> byDay = new TreeMap<>();
        for (int i = 0; i < days.length; i++) {
            byDay.computeIfAbsent(days[i], d -> new ArrayList<>()).add(minutes[i]);
        }
        for (List<Integer> sameDay : byDay.values()) {
            CircPairs pairs =
                    Stats.circPairs(sameDay.stream().mapToInt(Integer::intValue).toArray());
            within60 -= pairs.within60();
            within2 -= pairs.within2();
        }
        return new PairTest(within60, within2);
    }

    /** The 10% and 90% quantiles of the reply delays; null with fewer than 20 delays. */
    public static @Nullable Quantiles delayQuantiles(double[] delays) {
        if (delays.length < FOLLOW_DELAYS_MIN) return null;
        return new Quantiles(Stats.quantile(delays, 0.1), Stats.quantile(delays, 0.9));
    }

    /**
     * The statistics of one route. {@code sched_r} is the share of the sampled traces of IDs with 4 or more that belong
     * to IDs on a schedule; the concentration pools the pair counts of those IDs. The anchor share is set later.
     */
    public static RouteStatistics statistics(RouteTally tally, List<RouteSample> samples) {
        double n = tally.traces();
        long within60 = 0;
        long within2 = 0;
        long sampled = 0;
        long scheduled = 0;
        for (RouteSample sample : samples) {
            int traces = sample.minutes().length;
            if (traces < PAIR_TEST_MIN_TRACES) continue;
            PairTest pairs = pairTest(sample.minutes(), sample.days());
            within60 += pairs.within60();
            within2 += pairs.within2();
            sampled += traces;
            if (pairs.onSchedule()) scheduled += traces;
        }
        Quantiles q = delayQuantiles(tally.delays());
        return new RouteStatistics(
                tally.traces(),
                tally.nullIds() / n,
                tally.withInput() / n,
                tally.withParent() / n,
                tally.second0() / n,
                tally.minute5() / n,
                tally.delays().length / n,
                q == null ? null : q.q10(),
                q == null ? null : q.q90(),
                within60 == 0 ? null : (double) within2 / within60,
                sampled == 0 ? 0.0 : (double) scheduled / sampled,
                null);
    }

    /** At least one route with 30 or more traces has input on more than half of them. */
    public static boolean appSendsInput(Collection<RouteTally> tallies) {
        return tallies.stream()
                .anyMatch(t -> t.traces() >= APP_SENDS_INPUT_MIN_TRACES
                        && (double) t.withInput() / t.traces() > APP_SENDS_INPUT_SHARE);
    }

    /** Class rules 1 to 6, the first that applies. */
    public static RouteClass ruleClass(RouteStatistics s, boolean appSendsInput) {
        if (s.nullShare() >= ROUTE_NULL_SHARE || s.parentShare() >= ROUTE_PARENT_SHARE) return RouteClass.C;
        Double dq10 = s.delayQ10();
        Double dq90 = s.delayQ90();
        if (s.follow() >= FOLLOW_CUT
                && dq10 != null
                && dq90 != null
                && dq90 >= PACE_DQ90_SECONDS
                && dq90 / Math.max(1.0, dq10) >= PACE_SPREAD) return RouteClass.A;
        if (s.second0Share() >= SCHEDULE_SEC0_SHARE
                || s.minute5Share() >= SCHEDULE_MIN5_SHARE
                || s.scheduleShare() >= PAIR_SCHEDULE_SHARE) return RouteClass.S;
        if (s.follow() >= FOLLOW_CUT) return RouteClass.M;
        if (s.inputShare() <= ROUTE_INPUT_SHARE && appSendsInput) return RouteClass.C;
        return RouteClass.B;
    }

    /** A route near a cut keeps its stored class; any other route takes the class of the rules. */
    public static RouteClass keptClass(RouteStatistics s, boolean appSendsInput, @Nullable RouteClass stored) {
        return stored != null && nearCut(s) ? stored : ruleClass(s, appSendsInput);
    }

    /** {@code follow_r} within the margin of its cut, or {@code sched_r} within the margin of its cut. */
    public static boolean nearCut(RouteStatistics s) {
        return Math.abs(s.follow() - FOLLOW_CUT) <= ROUTE_CLASS_MARGIN
                || Math.abs(s.scheduleShare() - PAIR_SCHEDULE_SHARE) <= ROUTE_CLASS_MARGIN;
    }

    /**
     * The anchor test and the lenient fallback over every classed route. A B route with an anchor share of 0.4 or more
     * becomes A2 when the project has an A route. A route keeps its stored class-since day while its class is the same;
     * else the class dates from {@code day}.
     *
     * @param coAnchor the share of a B route's (ID, day) pairs that have a trace on an A route
     */
    public static RouteClasses anchor(
            Map<Integer, Route> routes, Map<Integer, Double> coAnchor, Map<Integer, Route> stored, int day) {
        boolean hasA = routes.values().stream().anyMatch(r -> r.routeClass() == RouteClass.A);
        Map<Integer, Route> classed = new TreeMap<>();
        routes.forEach((routeNo, route) -> {
            RouteClass routeClass = route.routeClass();
            RouteStatistics statistics = route.statistics();
            Double co = coAnchor.get(routeNo);
            if (routeClass == RouteClass.B && co != null) {
                statistics = withCoAnchor(statistics, co);
                if (hasA && co >= ROUTE_CO_ANCHOR) routeClass = RouteClass.A2;
            }
            Route before = stored.get(routeNo);
            Integer since =
                    before != null && before.routeClass() == routeClass ? before.classSinceDay() : Integer.valueOf(day);
            classed.put(routeNo, new Route(routeClass, since, statistics, route.nearCut()));
        });
        return new RouteClasses(classed, !hasA);
    }

    private static RouteStatistics withCoAnchor(RouteStatistics s, double co) {
        return new RouteStatistics(
                s.traces(),
                s.nullShare(),
                s.inputShare(),
                s.parentShare(),
                s.second0Share(),
                s.minute5Share(),
                s.follow(),
                s.delayQ10(),
                s.delayQ90(),
                s.concentration(),
                s.scheduleShare(),
                co);
    }

    /**
     * @param within60 pairs whose minutes of day differ by 60 or less, round the clock
     * @param within2 pairs whose minutes of day differ by 2 or less
     */
    public record PairTest(int within60, int within2) {

        /** {@code c2 / c60}; null with no pair within 60 minutes. */
        public @Nullable Double concentration() {
            return within60 == 0 ? null : (double) within2 / within60;
        }

        public boolean onSchedule() {
            return within60 >= PAIR_C60_MIN && (double) within2 / within60 >= PAIR_CONCENTRATION;
        }
    }

    public record Quantiles(double q10, double q90) {}
}
