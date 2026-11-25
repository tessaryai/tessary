// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.PageNotes.Inputs;
import ai.tessary.endusers.judge.SeasonLevel.Season;
import ai.tessary.endusers.judge.StopStageRule.Decision;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Guards;
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
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.LimitCode;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.TracerVerdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Banners, limit lines and trust numbers of spec 9 and the level 2 guards: one banner per fact in a fixed order, limit
 * lines only for conditions that hold, at most 4 of them by the count of IDs they concern plus every line that is never
 * cut, and a trust line that says when it checks IDs, not people. A cut that drops a never-cut line, a banner out of
 * order, or a trust line printed while some IDs are replaced hides the one warning a reader needs.
 */
class PageNotesTest {

    private static final int D = 1000;
    private static final Map<String, Double> CANNOT_NAME = Map.of(
            "stopped_expected", 30.6, "stopped_low", 25.0, "stopped_high", 36.0, "stopped_named", 4.0, "plateau", 0.64);

    @Test
    void limits_keepNeverCutLines_andAtMost4Others() {
        LimitLine group = line(LimitCode.GROUP_OR_KEY_IDS, true, 18);
        LimitLine tracer = line(LimitCode.TRACER_CANNOT_TEST, true, 0);
        LimitLine few = line(LimitCode.FEW_COVERED_IDS, true, 300);
        LimitLine cannotName = line(LimitCode.CANNOT_BE_NAMED_YET, false, 230);
        LimitLine namedRight = line(LimitCode.NAMED_RIGHT_LOW, false, 40);
        LimitLine noAnchor = line(LimitCode.NO_ANCHOR, false, 300);
        LimitLine lenient = line(LimitCode.LENIENT_BASIS, false, 300);
        LimitLine idLoss = line(LimitCode.ID_LOSS, false, 12);
        LimitLine account = line(LimitCode.ACCOUNT_EVENT, false, 300);
        assertEquals(
                List.of(group, tracer, few, noAnchor, lenient, account, cannotName),
                PageNotes.cut(List.of(group, tracer, few, cannotName, namedRight, noAnchor, lenient, idLoss, account)),
                "never-cut lines in order, then the 4 lines that concern the most IDs, ties in order");
    }

    @Test
    void banners_oneForEachFact_inFixedOrder() {
        Fixture f = everyFact();
        Season season = new Season(0.72, 0.72, 30, 979);
        List<Banner> expected = List.of(
                new Banner(BannerCode.GROUP_OR_KEY_IDS, Map.of("ids", 3.0, "id_day_share", 0.06 + 0.03)),
                new Banner(BannerCode.ID_LOSS, Map.of("onset_day", 950.0, "ids", 2.0)),
                new Banner(BannerCode.ID_CHANGE, Map.of("first_day", 960.0, "cannot_follow", 2.0)),
                new Banner(BannerCode.TRACER, Map.of("ended", 30.0, "continued", 5.0)),
                new Banner(
                        BannerCode.SHARED_SILENCE,
                        Map.of(
                                "start_day", 975.0,
                                "share_silent", 0.31,
                                "share_usual", 0.09,
                                "ids", 2.0,
                                "held_until_day", 1003.0,
                                "stopped_low", 0.2 + 0.3,
                                "stopped_high", 0.5 + 0.6)),
                new Banner(BannerCode.SEASON_LEVEL, Map.of("level", 0.72, "since_day", 979.0)),
                new Banner(BannerCode.CANNOT_BE_NAMED_YET, CANNOT_NAME),
                new Banner(BannerCode.OUTAGE, Map.of("day", 990.0, "days", 2.0)));
        assertEquals(
                expected,
                PageNotes.banners(f.inputs(), season, new Decision(StopStageState.CANNOT_BE_NAMED_YET, CANNOT_NAME)));

        f.page = new PageStateResult(
                PageState.IDS_UNRELIABLE,
                PageState.IDS_UNRELIABLE,
                PageStateReason.HEAVY_IDS,
                Map.of("ids", 22.0, "id_day_share", 0.4),
                true,
                List.of(0));
        List<BannerCode> refused = PageNotes.banners(f.inputs(), season, null).stream()
                .map(Banner::code)
                .toList();
        assertEquals(
                List.of(
                        BannerCode.PAGE_REFUSAL,
                        BannerCode.GROUP_OR_KEY_IDS,
                        BannerCode.ID_LOSS,
                        BannerCode.ID_CHANGE,
                        BannerCode.TRACER,
                        BannerCode.SHARED_SILENCE,
                        BannerCode.SEASON_LEVEL,
                        BannerCode.OUTAGE),
                refused,
                "a refusal comes first and carries its reason numbers");
        assertEquals(
                new Banner(BannerCode.PAGE_REFUSAL, Map.of("ids", 22.0, "id_day_share", 0.4)),
                PageNotes.banners(f.inputs(), season, null).getFirst());
    }

    @Test
    void sharedSilence_oneBannerPerEvent_skipsOneWithin7DaysOfALargerOne() {
        Fixture f = new Fixture();
        f.events = List.of(
                event(SilenceState.OPEN, 975, null, List.of(0, 1, 2, 3, 4)),
                event(SilenceState.PROVEN_BREAK, 978, 990, List.of(5, 6, 7)),
                event(SilenceState.PROVEN_BREAK, 930, 980, List.of(5, 6, 7, 8)),
                event(SilenceState.PROVEN_BREAK, 910, 960, List.of(5, 6, 7, 8)),
                event(SilenceState.NOT_A_BREAK, 950, null, List.of(0, 1)),
                event(SilenceState.TRAFFIC_ENDED, 900, null, List.of(0, 1)));
        List<Banner> banners = PageNotes.banners(f.inputs(), noSeason(), null);
        assertEquals(
                List.of(
                        new Banner(
                                BannerCode.SHARED_SILENCE,
                                Map.of(
                                        "start_day", 975.0,
                                        "share_silent", 0.31,
                                        "share_usual", 0.09,
                                        "ids", 5.0,
                                        "held_until_day", 1003.0,
                                        "stopped_low", 0.5,
                                        "stopped_high", 0.5)),
                        new Banner(BannerCode.SHARED_SILENCE, Map.of("returned_day", 980.0)),
                        new Banner(BannerCode.SHARED_SILENCE, Map.of("silent_since_day", 950.0))),
                banners,
                "a break that came back over 28 days ago and a closed event over 56 days ago give no banner");
    }

    @Test
    void outageBanner_onlyWithItsGuard() {
        Fixture f = new Fixture();
        f.outageDays = List.of(990);
        Banner outage = new Banner(BannerCode.OUTAGE, Map.of("day", 990.0, "days", 1.0));
        assertEquals(List.of(outage), PageNotes.banners(f.inputs(), noSeason(), null));
        f.guards = new Guards(true, true, true, true, true, true, false);
        assertEquals(List.of(), PageNotes.banners(f.inputs(), noSeason(), null), "outage-banner off");
    }

    @Test
    void trustNumbers() {
        assertAll(
                () -> assertEquals(
                        new TrustNumbers(0.93, 0.7, true),
                        PageNotes.trust(odds(0.93, 0.7), tracer(TracerVerdict.CANNOT_TEST)),
                        "this checks IDs, not people"),
                () -> assertEquals(
                        new TrustNumbers(0.93, 0.7, false),
                        PageNotes.trust(odds(0.93, 0.7), tracer(TracerVerdict.NO_CHANGE_SEEN))),
                () -> assertNull(
                        PageNotes.trust(odds(0.93, 0.7), tracer(TracerVerdict.SOME_IDS_REPLACED)),
                        "some IDs are replaced: no trust line"),
                () -> assertNull(
                        PageNotes.trust(odds(null, null), tracer(TracerVerdict.NO_CHANGE_SEEN)), "no past check"));
    }

    static Stream<Arguments> conditions() {
        LimitLine few10 = new LimitLine(LimitCode.FEW_COVERED_IDS, Map.of("ids", 10.0), true, 10);
        return Stream.of(
                Arguments.of("nothing holds", (Consumer<Fixture>) f -> {}, List.of(few10)),
                Arguments.of(
                        "group or key IDs",
                        (Consumer<Fixture>) f -> {
                            f.page = new PageStateResult(PageState.OK, PageState.OK, null, Map.of(), true, List.of());
                            f.groupHint(0).groupHint(1).aggregate(2);
                            f.pdGroupHint = 0.04;
                            f.pdAggregate = 0.02;
                        },
                        List.of(
                                new LimitLine(
                                        LimitCode.GROUP_OR_KEY_IDS,
                                        Map.of("ids", 3.0, "id_day_share", 0.04 + 0.02),
                                        true,
                                        3),
                                new LimitLine(LimitCode.FEW_COVERED_IDS, Map.of("ids", 9.0), true, 9))),
                Arguments.of(
                        "tracer cannot test",
                        (Consumer<Fixture>) f -> f.tracer = tracer(TracerVerdict.CANNOT_TEST),
                        List.of(new LimitLine(LimitCode.TRACER_CANNOT_TEST, Map.of(), true, 0), few10)),
                Arguments.of(
                        "stops cannot be named yet",
                        (Consumer<Fixture>) f -> {
                            f.stop = new Decision(StopStageState.CANNOT_BE_NAMED_YET, CANNOT_NAME);
                            f.counts = new StageCounts(6, 0, 0, 0, 4, 30.6, 25, 36, 10);
                        },
                        List.of(few10, new LimitLine(LimitCode.CANNOT_BE_NAMED_YET, CANNOT_NAME, false, 30))),
                Arguments.of(
                        "named_right under 0.8",
                        (Consumer<Fixture>) f -> {
                            f.namedRight = 0.79;
                            f.counts = new StageCounts(6, 0, 0, 0, 4, 4.4, 2, 6, 10);
                        },
                        List.of(
                                few10,
                                new LimitLine(LimitCode.NAMED_RIGHT_LOW, Map.of("named_right", 0.79), false, 4))),
                Arguments.of(
                        "named_found under 0.6",
                        (Consumer<Fixture>) f -> {
                            f.namedFound = 0.59;
                            f.counts = new StageCounts(6, 0, 0, 0, 4, 4.4, 2, 6, 10);
                        },
                        List.of(
                                few10,
                                new LimitLine(LimitCode.NAMED_FOUND_LOW, Map.of("named_found", 0.59), false, 4))),
                Arguments.of(
                        "named_right 0.8 and named_found 0.6",
                        (Consumer<Fixture>) f -> {
                            f.namedRight = 0.8;
                            f.namedFound = 0.6;
                        },
                        List.of(few10)),
                Arguments.of(
                        "no anchor",
                        (Consumer<Fixture>) f -> {
                            f.anchors = List.of();
                            f.t0Id = 990;
                        },
                        List.of(few10, new LimitLine(LimitCode.NO_ANCHOR, Map.of("days_needed", 18.0), false, 10))),
                Arguments.of(
                        "lenient basis",
                        (Consumer<Fixture>) f -> f.lenient = true,
                        List.of(few10, new LimitLine(LimitCode.LENIENT_BASIS, Map.of(), false, 10))),
                Arguments.of(
                        "unattended above 10%",
                        (Consumer<Fixture>) f -> f.unattended(0).automationOnly(1),
                        List.of(few10, new LimitLine(LimitCode.UNATTENDED_SHARE, Map.of("ids", 2.0), false, 10))),
                Arguments.of("unattended at 10%", (Consumer<Fixture>) f -> f.unattended(0), List.of(few10)),
                Arguments.of(
                        "ID loss",
                        (Consumer<Fixture>) f -> f.idLoss = idLoss(Map.of(0, 1.0, 1, 1.0, 2, 0.5)),
                        List.of(few10, new LimitLine(LimitCode.ID_LOSS, Map.of("ids", 3.0), false, 3))),
                Arguments.of(
                        "ID change",
                        (Consumer<Fixture>) f -> {
                            f.idChange = idChange();
                            f.cannotFollow = Set.of(4, 5);
                        },
                        List.of(few10, new LimitLine(LimitCode.ID_CHANGE, Map.of("ids", 2.0), false, 2))),
                Arguments.of(
                        "shared silence open",
                        (Consumer<Fixture>)
                                f -> f.events = List.of(event(SilenceState.OPEN, 975, null, List.of(0, 1, 2))),
                        List.of(few10, new LimitLine(LimitCode.SHARED_SILENCE, Map.of("ids", 3.0), false, 3))),
                Arguments.of(
                        "shared silence proven a break",
                        (Consumer<Fixture>)
                                f -> f.events = List.of(event(SilenceState.PROVEN_BREAK, 930, 950, List.of(0, 1, 2))),
                        List.of(few10)),
                Arguments.of(
                        "account event",
                        (Consumer<Fixture>) f -> f.events = List.of(new SilenceEvent(
                                new Scope(ScopeKind.CLUSTER, "7"),
                                900,
                                null,
                                SilenceState.TRAFFIC_ENDED,
                                false,
                                40,
                                0.5,
                                0.1,
                                0.9,
                                0.9,
                                List.of(0, 1),
                                928)),
                        List.of(few10, new LimitLine(LimitCode.ACCOUNT_EVENT, Map.of(), false, 10))),
                Arguments.of(
                        "return cycle at the clamp",
                        (Consumer<Fixture>) f -> f.f = 42,
                        List.of(
                                few10,
                                new LimitLine(LimitCode.RETURN_CYCLE_TOO_LONG, Map.of("days", 66.0), false, 10),
                                new LimitLine(LimitCode.REPEAT_CONTACT, Map.of(), false, 10))),
                Arguments.of(
                        "return cycle at the clamp, cannot-tell-yet off",
                        (Consumer<Fixture>) f -> {
                            f.f = 42;
                            f.guards = new Guards(true, false, true, true, true, true, true);
                        },
                        List.of(
                                few10,
                                new LimitLine(LimitCode.RETURN_CYCLE_TOO_LONG, Map.of("days", 66.0), false, 10))),
                Arguments.of(
                        "facts not kept",
                        (Consumer<Fixture>) f -> f.gapDays = List.of(900, 901, 905),
                        List.of(
                                few10,
                                new LimitLine(
                                        LimitCode.FACTS_NOT_KEPT,
                                        Map.of("from_day", 900.0, "to_day", 905.0, "days", 3.0),
                                        false,
                                        10))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conditions")
    void lines_eachConditionGivesItsLine(String name, Consumer<Fixture> change, List<LimitLine> expected) {
        Fixture f = new Fixture();
        change.accept(f);
        assertEquals(expected, PageNotes.lines(f.inputs(), f.counts, f.stop));
    }

    @Test
    void mixedIdFormats_guardMakesTheOneDayLineNeverCut() {
        Fixture guarded = new Fixture();
        guarded.oneDayShareByShape = Map.of("u/8", 0.41);
        Fixture plain = new Fixture();
        plain.oneDayShareByShape = Map.of("u/8", 0.41);
        plain.appOneDayShare = 0.41;
        plain.guards = new Guards(true, true, true, true, false, true, true);
        Fixture twoFormats = new Fixture();
        twoFormats.appOneDayShare = 0.41;
        twoFormats.guards = new Guards(true, true, true, true, false, true, true);
        twoFormats.shape(9, "v", 12);
        LimitLine few10 = new LimitLine(LimitCode.FEW_COVERED_IDS, Map.of("ids", 10.0), true, 10);
        assertAll(
                () -> assertEquals(
                        List.of(few10, new LimitLine(LimitCode.ONE_DAY_SHARE, Map.of("one_day_share", 0.41), true, 10)),
                        PageNotes.lines(guarded.inputs(), guarded.counts, guarded.stop),
                        "a covered format with one-day share 0.41: never cut"),
                () -> assertEquals(
                        List.of(
                                few10,
                                new LimitLine(LimitCode.ONE_DAY_SHARE, Map.of("one_day_share", 0.41), false, 10)),
                        PageNotes.lines(plain.inputs(), plain.counts, plain.stop),
                        "guard off: the v3 line of an app with one format"),
                () -> assertEquals(
                        List.of(few10),
                        PageNotes.lines(twoFormats.inputs(), twoFormats.counts, twoFormats.stop),
                        "guard off: v3 prints nothing for an app with two formats"));
    }

    @Test
    void refusedPage_hasNoLimitLines() {
        Fixture f = new Fixture();
        f.tracer = tracer(TracerVerdict.CANNOT_TEST);
        f.page = new PageStateResult(
                PageState.NO_USER_ID,
                PageState.NO_USER_ID,
                PageStateReason.LOW_ID_SHARE,
                Map.of("ident_share", 0.12, "ident_share_28", 0.1),
                false,
                List.of());
        assertEquals(List.of(), PageNotes.limits(f.inputs(), null, null));
    }

    private static Fixture everyFact() {
        Fixture f = new Fixture();
        f.page = new PageStateResult(PageState.OK, PageState.OK, null, Map.of(), true, List.of());
        f.groupHint(5).groupHint(6).aggregate(9);
        f.pdGroupHint = 0.06;
        f.pdAggregate = 0.03;
        f.idLoss = new IdLoss(List.of("r|1"), 950, false, 0.6, 0.5, Map.of(0, 1.0, 1, 1.0, 2, 1.0, 3, 1.0), 0);
        f.idChange = idChange();
        f.cannotFollow = Set.of(7, 8);
        f.tracer = new TracerResult(TracerVerdict.SOME_IDS_REPLACED, 30, 5, false, 900, 0.7, Set.of());
        f.calendar(0, 0.2, 0.5).calendar(1, 0.3, 0.6);
        f.events = List.of(event(SilenceState.OPEN, 975, null, List.of(0, 1, 9)));
        f.outageDays = List.of(940, 990);
        return f;
    }

    private static Season noSeason() {
        return new Season(1.0, null, 0, 979);
    }

    private static SilenceEvent event(SilenceState state, int start, @Nullable Integer end, List<Integer> members) {
        return new SilenceEvent(
                new Scope(ScopeKind.APP, null), start, end, state, false, 100, 0.31, 0.09, 0.8, 0.8, members, D + 1);
    }

    private static IdLoss idLoss(Map<Integer, Double> lostShare) {
        return new IdLoss(List.of("r|1"), 950, false, 0.6, 0.5, lostShare, 0);
    }

    private static IdChange idChange() {
        IdChangeEvent event =
                new IdChangeEvent(new Scope(ScopeKind.APP, null), 960, 30, 30, 100, 0, false, List.of(7, 8), List.of());
        return new IdChange(List.of(event), 960);
    }

    private static TracerResult tracer(TracerVerdict verdict) {
        return new TracerResult(verdict, 10, 0, false, null, null, Set.of());
    }

    private static StopOdds odds(@Nullable Double namedRight, @Nullable Double namedFound) {
        return new StopOdds(
                Map.of(), 7.0, 7.0, 21, List.of(931), List.of(), namedRight, namedFound, Map.of(), Map.of(), Map.of());
    }

    private static LimitLine line(LimitCode code, boolean neverCut, int ids) {
        return new LimitLine(code, Map.of(), neverCut, ids);
    }

    private static final class Fixture {
        final Map<Integer, IdSeries> series = new TreeMap<>();
        final Map<Integer, IdIdentity> identity = new TreeMap<>();
        final Map<Integer, IdOdds> odds = new TreeMap<>();
        final List<IdInfo> infos = new ArrayList<>();
        int t0Id = 800;
        Map<String, Double> oneDayShareByShape = Map.of();

        @Nullable
        Double appOneDayShare;

        double pdGroupHint;
        double pdAggregate;
        PageStateResult page = new PageStateResult(PageState.OK, PageState.OK, null, Map.of(), false, List.of());

        @Nullable
        IdLoss idLoss;

        @Nullable
        IdChange idChange;

        Set<Integer> cannotFollow = Set.of();
        TracerResult tracer = tracer(TracerVerdict.NO_CHANGE_SEEN);
        List<SilenceEvent> events = List.of();
        List<Integer> anchors = List.of(931, 938, 945, 952, 959, 966);
        int f = 21;

        @Nullable
        Double namedRight = 0.95;

        @Nullable
        Double namedFound = 0.9;

        boolean lenient;
        List<Integer> outageDays = List.of();
        List<Integer> gapDays = List.of();
        Guards guards = Guards.allOn();
        StageCounts counts = new StageCounts(10, 0, 0, 0, 0, 1.0, 0, 2.6, 10);
        Decision stop = new Decision(StopStageState.EXPECTED_AND_NAMED, Map.of());

        Fixture() {
            for (int u = 0; u < 10; u++) {
                List<Integer> days = List.of(900, 940, 980);
                series.put(u, new IdSeries(900, days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null));
                identity.put(u, new IdIdentity(false, false, false, false, false, "u/8", null, true, null));
                odds.put(u, new IdOdds(Stratum.E, 0.1, 0.1, 0.1, null, false, false, 20, 20));
                infos.add(new IdInfo("u", 8, null, 900, 980, 1, false));
            }
        }

        Fixture groupHint(int u) {
            identity.put(u, new IdIdentity(false, true, false, false, false, "u/8", null, true, null));
            return this;
        }

        Fixture aggregate(int u) {
            identity.put(
                    u, new IdIdentity(true, false, false, false, false, "u/8", null, false, SetAsideReason.AGGREGATE));
            return this;
        }

        Fixture unattended(int u) {
            odds.put(u, new IdOdds(Stratum.E, 0.1, 0.1, 0.1, null, true, false, 20, 20));
            return this;
        }

        Fixture automationOnly(int u) {
            List<Integer> days = List.of(900, 940, 980);
            series.put(u, new IdSeries(900, days, ActivityBasis.UNATTENDED, List.of(), days, List.of(), false, null));
            return this;
        }

        Fixture calendar(int u, double pStop, double pStopCalendar) {
            odds.put(u, new IdOdds(Stratum.E, pStop, pStop, pStop, pStopCalendar, false, false, 20, 20));
            return this;
        }

        Fixture shape(int u, String prefix, int length) {
            infos.set(u, new IdInfo(prefix, length, null, 900, 980, 1, false));
            return this;
        }

        Inputs inputs() {
            Facts facts = new Facts(
                    D,
                    t0Id,
                    null,
                    infos,
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
                    new RouteClasses(Map.of(), lenient),
                    outageDays,
                    gapDays,
                    List.of());
            Identity id = new Identity(
                    identity,
                    Map.of(),
                    Map.of(),
                    new GroupStats(10, 10, 0.9, 0.9, 0.5, appOneDayShare, false),
                    oneDayShareByShape,
                    0.9,
                    pdAggregate,
                    pdGroupHint,
                    1.0,
                    1.0,
                    0.0);
            return new Inputs(
                    facts,
                    new Series(t0Id, series),
                    id,
                    new IdChannelResult(idLoss, idChange, Map.of(), cannotFollow, Set.of()),
                    tracer,
                    page,
                    new SilenceResult(events, Map.of()),
                    new StopOdds(
                            odds,
                            7.0,
                            7.0,
                            f,
                            anchors,
                            List.of(),
                            namedRight,
                            namedFound,
                            Map.of(Stratum.E, 0.64),
                            Map.of(),
                            Map.of()),
                    guards);
        }
    }
}
