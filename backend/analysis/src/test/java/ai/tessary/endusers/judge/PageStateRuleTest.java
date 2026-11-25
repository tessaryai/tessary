// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.IdentityFlags.Flags;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChangeEvent;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.TracerVerdict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The page state decides whether the page may show numbers at all (spec 3.5, level 2 memory). A rule taken out of order
 * lets a later, milder rule hide a refusal; a refusal that waits a run shows one day of numbers that cannot be trusted;
 * a relaxation on one run makes the page flicker between a refusal and numbers.
 */
class PageStateRuleTest {

    private static final PageState OK = PageState.OK;
    private static final PageState PARTIAL = PageState.PARTIAL_COVERAGE;
    private static final PageState EARLY = PageState.TOO_EARLY;
    private static final PageState NO_ID = PageState.NO_USER_ID;
    private static final PageState UNRELIABLE = PageState.IDS_UNRELIABLE;
    private static final PageState NOT_COMPUTED = PageState.NOT_COMPUTED;

    @Test
    void pageState_firstRuleWins() {
        assertAll(
                () -> assertRule(
                        NO_ID,
                        PageStateReason.LOW_ID_SHARE_28,
                        new Inputs().identShare28(0.12).span(14),
                        "a low recent ID share wins over a short history"),
                () -> assertRule(NO_ID, PageStateReason.LOW_ID_SHARE, new Inputs().identShare(0.19)),
                () -> assertRule(EARLY, PageStateReason.SHORT_HISTORY, new Inputs().span(14)),
                () -> assertRule(EARLY, PageStateReason.FEW_IDS, new Inputs().ids(16)),
                () -> assertRule(
                        EARLY,
                        PageStateReason.SHORT_HISTORY,
                        new Inputs().span(20).ids(16).pdAggregate(0.40),
                        "the first condition of the row names the reason; too early wins over heavy IDs"),
                () -> assertRule(OK, null, new Inputs().span(21).ids(20)),
                () -> assertRule(UNRELIABLE, PageStateReason.HEAVY_IDS, new Inputs().pdAggregate(0.40)),
                () -> assertRule(UNRELIABLE, PageStateReason.HEAVY_IDS, new Inputs().pdAggregate(0.25)),
                () -> assertRule(OK, null, new Inputs().pdAggregate(0.24)),
                () -> assertRule(
                        UNRELIABLE,
                        PageStateReason.HEAVY_IDS,
                        new Inputs()
                                .pdAggregate(0.40)
                                .tracer(TracerVerdict.IDS_REPLACED)
                                .pdCovered(0.05),
                        "heavy IDs come before the tracer and before coverage"),
                () -> assertRule(
                        UNRELIABLE,
                        PageStateReason.IDS_REPLACED,
                        new Inputs().tracer(TracerVerdict.IDS_REPLACED).pdCovered(0.05),
                        "the tracer comes before coverage"),
                () -> assertRule(UNRELIABLE, PageStateReason.IDS_NOT_SEEN_AGAIN, new Inputs().pdCovered(0.09)),
                () -> assertRule(
                        UNRELIABLE,
                        PageStateReason.IDS_COPY_SESSION_TOKEN,
                        new Inputs().pdCovered(0.09).linkShare(0.5)),
                () -> assertRule(
                        UNRELIABLE,
                        PageStateReason.IDS_NOT_SEEN_AGAIN,
                        new Inputs().app(30, 0.04),
                        "30 old IDs of which under 5% return later"),
                () -> assertRule(OK, null, new Inputs().app(29, 0.04), "29 old IDs are too few"),
                () -> assertRule(OK, null, new Inputs().app(30, null)),
                () -> assertRule(
                        PARTIAL,
                        PageStateReason.IDS_SET_ASIDE,
                        new Inputs().pdCovered(0.85).identShare(0.6).tracer(TracerVerdict.SOME_IDS_REPLACED)),
                () -> assertRule(OK, null, new Inputs().pdCovered(0.90)),
                () -> assertRule(
                        PARTIAL,
                        PageStateReason.LOW_ID_SHARE,
                        new Inputs().identShare(0.69).idLoss()),
                () -> assertRule(
                        PARTIAL, PageStateReason.ID_LOSS, new Inputs().idLoss().idChange(50, 0)),
                () -> assertRule(PARTIAL, PageStateReason.ID_CHANGE, new Inputs().idChange(50, 0)),
                () -> assertRule(
                        PARTIAL,
                        PageStateReason.SOME_IDS_REPLACED,
                        new Inputs().tracer(TracerVerdict.SOME_IDS_REPLACED)),
                () -> assertRule(OK, null, new Inputs().tracer(TracerVerdict.NO_CHANGE_SEEN)),
                () -> assertRule(OK, null, new Inputs()));
    }

    @Test
    void pageState_youngIdChange_isTooEarly() {
        assertAll(
                () -> assertRule(
                        EARLY,
                        PageStateReason.RECENT_ID_CHANGE,
                        new Inputs().idChange(85, 15),
                        "15 days ago, 15 IDs vanished or born against 30 active"),
                () -> assertRule(
                        PARTIAL,
                        PageStateReason.ID_CHANGE,
                        new Inputs().idChange(79, 15),
                        "21 days ago is no longer young"),
                () -> assertRule(
                        PARTIAL,
                        PageStateReason.ID_CHANGE,
                        new Inputs().idChange(85, 14),
                        "14 against 30 active is under half"),
                () -> assertRule(
                        EARLY,
                        PageStateReason.RECENT_ID_CHANGE,
                        new Inputs().idChange(85, 14).inactive(2),
                        "an ID last seen before D - 28 is not active, so 14 against 28 is half"),
                () -> assertRule(
                        PARTIAL,
                        PageStateReason.ID_CHANGE,
                        new Inputs().solvedYoungIdChange(85, 15),
                        "a young change solved by aliases is not young; the old unsolved one is in force"));
    }

    @Test
    void pageState_reasonNumbers() {
        assertAll(
                () -> assertEquals(
                        Map.of("ident_share_28", 0.12),
                        new Inputs().identShare(0.5).identShare28(0.12).decide().reasonNumbers(),
                        "the text says in the last 28 days: only the share that fired"),
                () -> assertEquals(
                        Map.of("ident_share", 0.12),
                        new Inputs().identShare(0.12).identShare28(0.1).decide().reasonNumbers(),
                        "both fire: the share across all history is the one the text names"),
                () -> assertEquals(
                        Map.of("span_days", 14.0, "ids", 16.0, "span_days_needed", 21.0, "ids_needed", 20.0),
                        new Inputs().span(14).ids(16).decide().reasonNumbers()),
                () -> assertEquals(
                        Map.of("ids", 3.0, "id_day_share", 0.40, "hours_per_day", 18.0, "sessions_per_day", 31.0),
                        new Inputs()
                                .aggregates(5, 6, 7)
                                .heavyMedians(new double[] {18, 17, 20}, new Double[] {31.0, 30.0, 40.0})
                                .pdAggregate(0.40)
                                .decide()
                                .reasonNumbers(),
                        "the median over the heavy IDs of each ID's median day"),
                () -> assertEquals(
                        Map.of("ids", 3.0, "id_day_share", 0.40, "hours_per_day", 17.0, "sessions_per_day", 35.5),
                        new Inputs()
                                .aggregates(5, 6, 7)
                                .heavyMedians(new double[] {16, 17, 20}, new Double[] {31.0, null, 40.0})
                                .pdAggregate(0.40)
                                .decide()
                                .reasonNumbers(),
                        "an ID with no session day has no session median"),
                () -> assertEquals(
                        Map.of("ids", 2.0, "id_day_share", 0.40, "hours_per_day", 15.0),
                        new Inputs()
                                .aggregates(5, 6)
                                .heavyMedians(new double[] {14, 16}, new Double[] {null, null})
                                .pdAggregate(0.40)
                                .decide()
                                .reasonNumbers(),
                        "no heavy ID has a session day: no sessions number, never 0"),
                () -> assertEquals(
                        Map.of("ended", 35.0, "continued", 34.0),
                        new Inputs()
                                .tracer(TracerVerdict.IDS_REPLACED, 35, 34)
                                .decide()
                                .reasonNumbers()),
                () -> assertEquals(
                        Map.of("covered_share", 0.05, "token_link_share", 0.6),
                        new Inputs().pdCovered(0.05).linkShare(0.6).decide().reasonNumbers()),
                () -> assertEquals(
                        Map.of("covered_share", 1.0, "token_link_share", 0.0, "return_later", 0.04),
                        new Inputs().app(30, 0.04).decide().reasonNumbers()),
                () -> assertEquals(
                        Map.of("covered_share", 0.85, "ident_share", 1.0),
                        new Inputs().pdCovered(0.85).decide().reasonNumbers()),
                () -> assertEquals(
                        Map.of("covered_share", 1.0, "ident_share", 1.0, "ended", 20.0, "continued", 3.0),
                        new Inputs()
                                .tracer(TracerVerdict.SOME_IDS_REPLACED, 20, 3)
                                .decide()
                                .reasonNumbers()),
                () -> assertEquals(Map.of(), new Inputs().decide().reasonNumbers()));
    }

    @Test
    void pageState_refusesAtOnce_relaxesAfterTwoEqualRuns() {
        PageStateResult held = new Inputs().memory(UNRELIABLE, UNRELIABLE).decide();
        PageStateResult relaxed = new Inputs().memory(UNRELIABLE, OK).decide();
        PageStateResult refused = new Inputs().memory(OK, OK).pdAggregate(0.40).decide();

        assertAll(
                () -> assertEquals(UNRELIABLE, held.state(), "one ok run does not lift a refusal"),
                () -> assertEquals(OK, held.ruleState(), "the next run remembers what the rules gave"),
                () -> assertNull(held.reason(), "today's numbers give no reason for the held refusal"),
                () -> assertEquals(Map.of(), held.reasonNumbers()),
                () -> assertEquals(OK, relaxed.state(), "the second ok run in a row lifts it"),
                () -> assertEquals(OK, relaxed.ruleState()),
                () -> assertEquals(UNRELIABLE, refused.state(), "a refusal applies on its first run"),
                () -> assertEquals(PageStateReason.HEAVY_IDS, refused.reason()));
    }

    @Test
    void pageState_memory_readsOnlyTheLastTwoRuleStates() {
        assertAll(
                () -> assertEquals(OK, PageStateRule.remembered(OK, List.of()), "no memory on the first run"),
                () -> assertEquals(PARTIAL, PageStateRule.remembered(PARTIAL, List.of(OK)), "stricter at once"),
                () -> assertEquals(PARTIAL, PageStateRule.remembered(OK, List.of(PARTIAL)), "looser waits a run"),
                () -> assertEquals(OK, PageStateRule.remembered(OK, List.of(PARTIAL, OK))),
                () -> assertEquals(PARTIAL, PageStateRule.remembered(OK, List.of(PARTIAL, PARTIAL))),
                () -> assertEquals(EARLY, PageStateRule.remembered(EARLY, List.of(OK, OK))),
                () -> assertEquals(
                        UNRELIABLE,
                        PageStateRule.remembered(UNRELIABLE, List.of(EARLY, EARLY)),
                        "one refusal replaces another at once"),
                () -> assertEquals(
                        NO_ID,
                        PageStateRule.remembered(OK, List.of(NO_ID, PARTIAL)),
                        "a refusal shown last run needs two ok runs, not one ok after a partial"),
                () -> assertEquals(
                        PARTIAL,
                        PageStateRule.remembered(PARTIAL, List.of(UNRELIABLE, PARTIAL)),
                        "two partial runs in a row lift a refusal to partial"),
                () -> assertEquals(UNRELIABLE, PageStateRule.remembered(PARTIAL, List.of(UNRELIABLE, UNRELIABLE))),
                () -> assertEquals(
                        OK,
                        PageStateRule.remembered(OK, List.of(NOT_COMPUTED, NOT_COMPUTED)),
                        "a run that computed no state leaves no memory"),
                () -> assertEquals(UNRELIABLE, PageStateRule.remembered(OK, List.of(UNRELIABLE, NOT_COMPUTED))));
    }

    @Test
    void refusal_namesUpTo10HeavyIds_byCount() {
        Inputs inputs = new Inputs()
                .aggregates(12, 30, 25, 40, 8, 33, 19, 25, 50, 7, 41, 22)
                .pdAggregate(0.40)
                .heavyNotAggregate(99);

        PageStateResult result = inputs.decide();

        assertAll(
                () -> assertEquals(
                        List.of(8, 10, 3, 5, 1, 2, 7, 11, 6, 0),
                        result.namedIds(),
                        "most ID-days first, ties by key index; the two lightest and the heavier service ID are"
                                + " not named"),
                () -> assertEquals(
                        List.of(),
                        new Inputs()
                                .aggregates(12, 30)
                                .pdAggregate(0.40)
                                .identShare(0.1)
                                .decide()
                                .namedIds(),
                        "only a refusal on heavy IDs names them"),
                () -> assertEquals(
                        List.of(),
                        new Inputs()
                                .aggregates(12, 30)
                                .pdAggregate(0.20)
                                .decide()
                                .namedIds()),
                () -> assertEquals(
                        List.of(),
                        new Inputs()
                                .aggregates(12, 30)
                                .memory(UNRELIABLE, UNRELIABLE)
                                .decide()
                                .namedIds(),
                        "a held refusal names no IDs"));
    }

    @Test
    void groupBanner_at5PercentOfIdDays() {
        assertAll(
                () -> assertEquals(
                        true,
                        new Inputs()
                                .pdGroupHint(0.03)
                                .pdAggregate(0.02)
                                .decide()
                                .groupBanner()),
                () -> assertEquals(true, new Inputs().pdAggregate(0.05).decide().groupBanner()),
                () -> assertEquals(true, new Inputs().pdGroupHint(0.05).decide().groupBanner()),
                () -> assertEquals(
                        false,
                        new Inputs()
                                .pdGroupHint(0.0299)
                                .pdAggregate(0.02)
                                .decide()
                                .groupBanner()),
                () -> assertEquals(
                        true,
                        new Inputs().pdGroupHint(0.05).identShare(0.1).decide().groupBanner(),
                        "the banner does not depend on the state"));
    }

    private static void assertRule(PageState state, PageStateReason reason, Inputs inputs, String... message) {
        PageStateResult result = inputs.decide();
        String m = message.length == 0 ? null : message[0];
        assertEquals(state, result.state(), m);
        assertEquals(state, result.ruleState(), m);
        assertEquals(reason, result.reason(), m);
    }

    private static final class Inputs {

        private static final int D = 100;

        private int span = 100;
        private int ids = 30;
        private int inactive;
        private double pdCovered = 1.0;
        private double pdAggregate;
        private double pdGroupHint;
        private double identShare = 1.0;
        private double identShare28 = 1.0;
        private double linkShare;
        private int appOld;
        private Double returnLater;
        private TracerResult tracer = tracerResult(TracerVerdict.CANNOT_TEST, 0, 0);
        private IdLoss idLoss;
        private IdChange idChange;
        private int[] aggregateDays = new int[0];
        private double[] heavyHours = new double[0];
        private Double[] heavySessions = new Double[0];
        private int heavyNotAggregate;
        private List<PageState> memory = List.of();

        Inputs span(int days) {
            span = days;
            return this;
        }

        Inputs ids(int count) {
            ids = count;
            return this;
        }

        Inputs inactive(int count) {
            inactive = count;
            return this;
        }

        Inputs pdCovered(double share) {
            pdCovered = share;
            return this;
        }

        Inputs pdAggregate(double share) {
            pdAggregate = share;
            return this;
        }

        Inputs pdGroupHint(double share) {
            pdGroupHint = share;
            return this;
        }

        Inputs identShare(double share) {
            identShare = share;
            return this;
        }

        Inputs identShare28(double share) {
            identShare28 = share;
            return this;
        }

        Inputs linkShare(double share) {
            linkShare = share;
            return this;
        }

        Inputs app(int old, Double later) {
            appOld = old;
            returnLater = later;
            return this;
        }

        Inputs tracer(TracerVerdict verdict) {
            return tracer(verdict, 10, 5);
        }

        Inputs tracer(TracerVerdict verdict, int ended, int continued) {
            this.tracer = tracerResult(verdict, ended, continued);
            return this;
        }

        Inputs idLoss() {
            idLoss = new IdLoss(List.of("app"), 60, false, 0.5, 0.5, Map.of(), 1.0, List.of(1), 0.3, 0.0);
            return this;
        }

        Inputs idChange(int day, int movedIds) {
            idChange = new IdChange(List.of(event(day, movedIds, false)), day);
            return this;
        }

        Inputs solvedYoungIdChange(int day, int movedIds) {
            idChange = new IdChange(List.of(event(60, 1, false), event(day, movedIds, true)), 60);
            return this;
        }

        Inputs aggregates(int... sizeDays) {
            aggregateDays = sizeDays;
            return this;
        }

        Inputs heavyMedians(double[] hours, Double[] sessions) {
            heavyHours = hours;
            heavySessions = sessions;
            return this;
        }

        Inputs heavyNotAggregate(int sizeDays) {
            heavyNotAggregate = sizeDays;
            return this;
        }

        Inputs memory(PageState... states) {
            memory = List.of(states);
            return this;
        }

        PageStateResult decide() {
            Map<Integer, IdSeries> byId = new TreeMap<>();
            Map<Integer, IdIdentity> identities = new TreeMap<>();
            Map<Integer, Flags> flags = new TreeMap<>();
            for (int u = 0; u < ids; u++) {
                boolean aggregate = u < aggregateDays.length;
                boolean service = !aggregate && u == aggregateDays.length && heavyNotAggregate > 0;
                int last = u < inactive ? D - 29 : D - 1;
                List<Integer> days = List.of(D - span, last);
                byId.put(u, new IdSeries(D - span, days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null));
                boolean covered = !aggregate && !service;
                identities.put(
                        u, new IdIdentity(aggregate, false, service, false, false, "(app)", null, covered, null));
                int sizeDays = aggregate ? aggregateDays[u] : service ? heavyNotAggregate : 2;
                double hours = u < heavyHours.length ? heavyHours[u] : 2;
                Double sessions = u < heavySessions.length ? heavySessions[u] : null;
                flags.put(u, new Flags(aggregate, false, service, false, false, sizeDays, hours, sessions));
            }
            Facts facts = new Facts(
                    D,
                    0,
                    null,
                    List.of(),
                    new IdDays(
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new long[0],
                            new int[0],
                            new int[0][0],
                            new long[0][0]),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(Map.of(), false),
                    List.of(),
                    List.of(),
                    memory);
            Identity identity = new Identity(
                    identities,
                    Map.of(),
                    Map.of(),
                    new GroupStats(ids, appOld, returnLater, null, null, null, false),
                    Map.of(),
                    pdCovered,
                    pdAggregate,
                    pdGroupHint,
                    identShare,
                    identShare28,
                    linkShare);
            IdChannelResult channel = new IdChannelResult(idLoss, idChange, Map.of(), Set.of(), Set.of());
            return PageStateRule.decide(facts, new Series(D - span, byId), identity, flags, channel, tracer);
        }

        private static IdChangeEvent event(int day, int movedIds, boolean solved) {
            List<Integer> moved = IntStream.range(0, movedIds).boxed().toList();
            int half = movedIds / 2;
            return new IdChangeEvent(
                    new Scope(ScopeKind.APP, null),
                    day,
                    1.0,
                    1.0,
                    10.0,
                    0,
                    solved,
                    moved.subList(0, half),
                    moved.subList(half, movedIds));
        }

        private static TracerResult tracerResult(TracerVerdict verdict, int ended, int continued) {
            return new TracerResult(verdict, ended, continued, false, null, null, Set.of());
        }
    }
}
