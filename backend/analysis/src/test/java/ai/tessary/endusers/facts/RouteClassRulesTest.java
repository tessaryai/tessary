// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.model.RouteClass;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The route classes of spec section 1. A wrong class moves every trace of the route into or out of a person's activity,
 * so a timer reads as a loyal user, or a person's chat reads as automation and the person is never counted.
 */
class RouteClassRulesTest {

    @Test
    void pairTest_timer() {
        RouteClassRules.PairTest p = RouteClassRules.pairTest(new int[] {540, 540, 540, 540, 540, 540}, days(6));

        assertAll(
                () -> assertEquals(15, p.within60()),
                () -> assertEquals(15, p.within2()),
                () -> assertEquals(1.0, p.concentration()),
                () -> assertTrue(p.onSchedule()));
    }

    @Test
    void pairTest_habit() {
        RouteClassRules.PairTest p = RouteClassRules.pairTest(new int[] {540, 541, 560, 580, 600}, days(5));

        assertAll(
                () -> assertEquals(10, p.within60()),
                () -> assertEquals(1, p.within2()),
                () -> assertEquals(0.1, p.concentration()),
                () -> assertFalse(p.onSchedule(), "a person who comes at the same hour by habit"));
    }

    @Test
    void pairTest_tooFewPairs() {
        RouteClassRules.PairTest p = RouteClassRules.pairTest(new int[] {540, 545, 600, 480}, days(4));

        assertAll(() -> assertEquals(4, p.within60()), () -> assertFalse(p.onSchedule(), "c60 under 6"));
    }

    @Test
    void pairTest_countsOnlyPairsOnDifferentDays() {
        RouteClassRules.PairTest p =
                RouteClassRules.pairTest(new int[] {540, 540, 540, 900, 900, 900}, new int[] {1, 1, 1, 2, 2, 2});

        assertAll(
                () -> assertEquals(0, p.within60(), "a burst of turns in one sitting is not a schedule"),
                () -> assertNull(p.concentration()),
                () -> assertFalse(p.onSchedule()));
    }

    @Test
    void scheduleShare_countsTracesOfIdsWithFourOrMore_andPoolsTheConcentration() {
        RouteTally tally = tally(14, new double[0]);
        List<RouteSample> samples = List.of(
                sample(new int[] {540, 540, 540, 540, 540, 540}),
                sample(new int[] {540, 541, 560, 580, 600}),
                sample(new int[] {700, 700, 700}));

        RouteStatistics s = RouteClassRules.statistics(tally, samples);

        assertAll(
                () -> assertEquals(6.0 / 11, s.scheduleShare(), "the ID with 3 traces is not in the share"),
                () -> assertEquals(16.0 / 25, s.concentration(), "c2 and c60 summed over the IDs with 4 or more"));
    }

    @Test
    void statistics_sharesOfTheRoutesTraces() {
        RouteTally tally = new RouteTally("chat.turn", "cs1", 40, 4, 30, 2, 10, 12, new double[] {5, 6, 7, 8});

        RouteStatistics s = RouteClassRules.statistics(tally, List.of());

        assertEquals(
                new RouteStatistics(40, 0.1, 0.75, 0.05, 0.25, 0.3, 0.1, null, null, null, 0.0, null),
                s,
                "follow is the share of traces that start within the reply window, with too few delays for quantiles");
    }

    @Test
    void classOrder_paceBeforeSchedule() {
        assertAll(
                () -> assertEquals(RouteClass.A, rule(stats(0.4, 5.0, 45.0, 0.6, 0.0, 0.0))),
                () -> assertEquals(RouteClass.S, rule(stats(0.4, 20.0, 45.0, 0.6, 0.0, 0.0)), "ratio 2.25"),
                () -> assertEquals(RouteClass.M, rule(stats(0.4, 20.0, 45.0, 0.1, 0.1, 0.0))),
                () -> assertEquals(RouteClass.S, rule(stats(0.1, null, null, 0.1, 0.5, 0.0)), "min5"),
                () -> assertEquals(RouteClass.S, rule(stats(0.1, null, null, 0.1, 0.1, 0.5)), "sched_r"),
                () -> assertEquals(RouteClass.M, rule(stats(0.4, null, null, 0.1, 0.1, 0.0)), "too few delays"),
                () -> assertEquals(RouteClass.M, rule(stats(0.4, 0.5, 29.0, 0.1, 0.1, 0.0)), "dq90 under 30"),
                () -> assertEquals(RouteClass.A, rule(stats(0.4, 0.5, 30.0, 0.1, 0.1, 0.0)), "dq10 under 1 s"));
    }

    @Test
    void classC() {
        assertAll(
                () -> assertEquals(RouteClass.C, rule(shares(0.95, 0.0, 0.0)), "null_r 0.95"),
                () -> assertEquals(RouteClass.C, rule(shares(0.0, 0.5, 0.0)), "par_r 0.5"),
                () -> assertEquals(
                        RouteClass.C,
                        RouteClassRules.ruleClass(shares(0.0, 0.0, 0.05), true),
                        "inp_r 0.05 with app_sends_input"),
                () -> assertEquals(RouteClass.B, RouteClassRules.ruleClass(shares(0.0, 0.0, 0.05), false)),
                () -> assertEquals(RouteClass.B, RouteClassRules.ruleClass(shares(0.0, 0.0, 0.2), true)),
                () -> assertEquals(RouteClass.B, rule(shares(0.89, 0.49, 0.0))));
    }

    @Test
    void appSendsInput_needsARouteOf30TracesWithMostlyInput() {
        assertAll(
                () -> assertTrue(RouteClassRules.appSendsInput(List.of(inputs(30, 16), inputs(5, 0)))),
                () -> assertFalse(RouteClassRules.appSendsInput(List.of(inputs(30, 15))), "exactly half"),
                () -> assertFalse(RouteClassRules.appSendsInput(List.of(inputs(29, 29))), "under 30 traces"));
    }

    @Test
    void anchor_B_becomesA2_atCo04() {
        Map<Integer, Route> routes = Map.of(1, base(RouteClass.A), 2, base(RouteClass.B), 3, base(RouteClass.B));

        RouteClasses classes = RouteClassRules.anchor(routes, Map.of(2, 0.4, 3, 0.39), Map.of(), 120);

        assertAll(
                () -> assertEquals(RouteClass.A2, classOf(classes, 2)),
                () -> assertEquals(RouteClass.B, classOf(classes, 3)),
                () -> assertEquals(0.4, classes.byRoute().get(2).statistics().coAnchor()),
                () -> assertEquals(0.39, classes.byRoute().get(3).statistics().coAnchor()),
                () -> assertNull(classes.byRoute().get(1).statistics().coAnchor()),
                () -> assertFalse(classes.lenient()));
    }

    @Test
    void lenientFallback_whenNoARoute() {
        Map<Integer, Route> routes = Map.of(1, base(RouteClass.M), 2, base(RouteClass.B), 3, base(RouteClass.S));

        RouteClasses classes = RouteClassRules.anchor(routes, Map.of(2, 0.5), Map.of(), 120);

        assertAll(
                () -> assertTrue(classes.lenient()),
                () -> assertEquals(RouteClass.B, classOf(classes, 2), "no A route, so no B route is anchored"),
                () -> assertEquals(RouteClass.M, classOf(classes, 1)));
    }

    @Test
    void classSince_changesOnlyWithTheClass() {
        Map<Integer, Route> stored = Map.of(
                1, new Route(RouteClass.A, 100, stats(0.4, 5.0, 45.0, 0.0, 0.0, 0.0), false),
                2, new Route(RouteClass.B, 100, stats(0.0, null, null, 0.0, 0.0, 0.0), false),
                3, new Route(RouteClass.A2, 90, stats(0.0, null, null, 0.0, 0.0, 0.0), false));
        Map<Integer, Route> routes = Map.of(1, base(RouteClass.A), 2, base(RouteClass.B), 3, stored.get(3));

        RouteClasses classes = RouteClassRules.anchor(routes, Map.of(2, 0.5), stored, 120);

        assertAll(
                () -> assertEquals(100, classes.byRoute().get(1).classSinceDay()),
                () -> assertEquals(120, classes.byRoute().get(2).classSinceDay(), "B became A2 on day 120"),
                () -> assertEquals(stored.get(3), classes.byRoute().get(3), "a route with no traces keeps its row"));
    }

    @Test
    void margin_keepsStoredClass() {
        assertAll(
                () -> assertEquals(RouteClass.A, kept(0.27, RouteClass.A)),
                () -> assertEquals(RouteClass.B, kept(0.24, RouteClass.A)),
                () -> assertEquals(RouteClass.B, kept(0.27, null), "no stored class: the plain cut"),
                () -> assertEquals(RouteClass.B, kept(0.33, RouteClass.B), "a stored B stays B inside the margin"),
                () -> assertEquals(RouteClass.A, kept(0.36, RouteClass.B)));
    }

    @Test
    void nearCut_isWithinTheMarginOfTheFollowOrScheduleCut() {
        assertAll(
                () -> assertTrue(RouteClassRules.nearCut(stats(0.27, null, null, 0.0, 0.0, 0.0))),
                () -> assertTrue(RouteClassRules.nearCut(stats(0.0, null, null, 0.0, 0.0, 0.53))),
                () -> assertFalse(RouteClassRules.nearCut(stats(0.24, null, null, 0.0, 0.0, 0.56))));
    }

    @Test
    void followDelays_need20() {
        double[] nineteen = IntStream.rangeClosed(1, 19).asDoubleStream().toArray();
        double[] twenty = IntStream.rangeClosed(1, 20).asDoubleStream().toArray();

        RouteClassRules.Quantiles q = RouteClassRules.delayQuantiles(twenty);

        assertAll(
                () -> assertNull(RouteClassRules.delayQuantiles(nineteen)),
                () -> assertEquals(2.9, q.q10(), 1e-9),
                () -> assertEquals(18.1, q.q90(), 1e-9));
    }

    private static RouteClass rule(RouteStatistics s) {
        return RouteClassRules.ruleClass(s, false);
    }

    private static RouteClass kept(double follow, @Nullable RouteClass stored) {
        return RouteClassRules.keptClass(stats(follow, 5.0, 45.0, 0.0, 0.0, 0.0), false, stored);
    }

    private static RouteClass classOf(RouteClasses classes, int routeNo) {
        return classes.byRoute().get(routeNo).routeClass();
    }

    private static Route base(RouteClass routeClass) {
        return new Route(routeClass, null, stats(0.0, null, null, 0.0, 0.0, 0.0), false);
    }

    private static RouteStatistics stats(
            double follow, @Nullable Double dq10, @Nullable Double dq90, double sec0, double min5, double sched) {
        return new RouteStatistics(100, 0.0, 0.0, 0.0, sec0, min5, follow, dq10, dq90, null, sched, null);
    }

    private static RouteStatistics shares(double nullShare, double parentShare, double inputShare) {
        return new RouteStatistics(100, nullShare, inputShare, parentShare, 0.0, 0.0, 0.0, null, null, null, 0.0, null);
    }

    private static RouteTally tally(int traces, double[] delays) {
        return new RouteTally("r", "cs", traces, 0, 0, 0, 0, 0, delays);
    }

    private static RouteTally inputs(int traces, int withInput) {
        return new RouteTally("r", "cs", traces, 0, withInput, 0, 0, 0, new double[0]);
    }

    private static RouteSample sample(int[] minutes) {
        return new RouteSample(1, minutes, days(minutes.length));
    }

    private static int[] days(int n) {
        return IntStream.range(0, n).toArray();
    }
}
