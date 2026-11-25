// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.Isotonic.Block;
import ai.tessary.endusers.judge.PastCheck.AnchorRecord;
import ai.tessary.endusers.judge.PastCheck.Group;
import ai.tessary.endusers.judge.PastCheck.PooledBlock;
import ai.tessary.endusers.judge.PastCheck.Reliability;
import ai.tessary.endusers.judge.Survival.Observation;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.IdChannelResult;
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
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.Stratum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The past check calibrates the nominal stop odds on what IDs did after up to 12 past dates, and measures the result on
 * dates it was not fitted on (spec 6.1, 6.2, readings 11 to 13 of spec 5). A calibration with no cap before the first
 * date names IDs on a curve with no follow-up; one fitted in sample overstates its own reliability; a block value that
 * ignores the still-falling tail, or a calibrated value that falls as the odds rise, names the wrong IDs.
 */
class PastCheckTest {

    @Test
    void noAnchor_capsAt075() {
        Map<Group, List<Block>> cal = Map.of(
                new Group(Stratum.E, null), List.of(new Block(0.2, 0.3), new Block(0.8, 0.9)),
                new Group(Stratum.E, true), List.of(new Block(0.5, 0.95)));
        assertAll(
                () -> assertEquals(0.75, PastCheck.calibrate(cal, 0, Stratum.E, 0.9, false), "no anchor: capped"),
                () -> assertEquals(0.5, PastCheck.calibrate(cal, 0, Stratum.E, 0.5, false), "no anchor: nominal"),
                () -> assertEquals(
                        (2 * 0.5 + 0.6) / 3, PastCheck.calibrate(cal, 1, Stratum.E, 0.5, false), 1e-12, "weights 2, 1"),
                () -> assertEquals(
                        (0.5 + 2 * 0.6) / 3, PastCheck.calibrate(cal, 2, Stratum.E, 0.5, false), 1e-12, "weights 1, 2"),
                () -> assertEquals(0.6, PastCheck.calibrate(cal, 3, Stratum.E, 0.5, false), 1e-12, "calibrated only"),
                () -> assertEquals(
                        0.95, PastCheck.calibrate(cal, 3, Stratum.E, 0.5, true), "the beyond group wins over the type"),
                () -> assertEquals(
                        0.75, PastCheck.calibrate(cal, 3, Stratum.K1, 0.9, false), "a type with no blocks: capped"));
    }

    @Test
    void F_clamp() {
        assertAll(
                () -> assertEquals(14, PastCheck.returnCycle(List.of(10, 10)), "q99 10 rises to 14"),
                () -> assertEquals(42, PastCheck.returnCycle(List.of(50, 50)), "q99 50 falls to 42"),
                () -> assertEquals(29, PastCheck.returnCycle(List.of(10, 30)), "q99 29.8 is cut to whole days"),
                () -> assertEquals(14, PastCheck.returnCycle(List.of()), "no gap"));
    }

    @Test
    void reliability_isNull_withFewerThan4Anchors() {
        int asOf = 735;
        int f = 20;
        int[] anchorDays = {700, 707, 714, 721};
        List<AnchorRecord> records = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            add(records, 20, a, anchorDays[a], Stratum.E, 0.3, asOf - anchorDays[a] + 1, false, true);
        }
        for (int a = 2; a < 4; a++) {
            int day = anchorDays[a];
            add(records, 2, a, day, Stratum.E, 0.3, asOf - day + 1, false, true);
            add(records, 18, a, day, Stratum.E, 0.3, 1, true, false);
            add(records, a == 2 ? 5 : 3, a, day, Stratum.K1, 0.5, asOf - day + 1, false, true);
        }

        Reliability four = PastCheck.reliability(records, 4, asOf, f);
        Reliability three = PastCheck.reliability(records, 3, asOf, f);

        assertAll(
                () -> assertEquals(
                        0.1,
                        four.namedRight(),
                        1e-12,
                        "the older dates fit 0.95 for E at 0.3: 40 named, 4 stayed away; in sample it would be 0.49"),
                () -> assertEquals(
                        2 / 7.0,
                        four.namedFound(),
                        1e-12,
                        "7 stayed away 21 days before D, 2 named; the newest date is under F before D"),
                () -> assertNull(three.namedRight()),
                () -> assertNull(three.namedFound()));
    }

    @Test
    void anchors() {
        List<Integer> fifty = Collections.nCopies(50, 700);
        List<Integer> late = new ArrayList<>(Collections.nCopies(49, 700));
        late.add(854);
        assertAll(
                () -> assertEquals(
                        List.of(805, 812, 819, 826, 833, 840, 847, 854, 861, 868, 875, 882),
                        PastCheck.anchors(700, 900, fifty),
                        "the 12 latest week-grid days up to D - 14"),
                () -> assertEquals(List.of(714, 721, 728), PastCheck.anchors(700, 742, fifty), "t0_id + 14 to D - 14"),
                () -> assertEquals(
                        List.of(861, 868, 875, 882),
                        PastCheck.anchors(700, 900, late),
                        "the 50th ID is first seen on 854, so 854 has 49 IDs seen before it"),
                () -> assertEquals(List.of(), PastCheck.anchors(700, 900, Collections.nCopies(49, 700))));
    }

    @Test
    void calibration_blockValue() {
        List<Observation> block = new ArrayList<>();
        for (int i = 0; i < 25; i++) block.add(new Observation(1, true));
        for (int i = 0; i < 3; i++) block.add(new Observation(30, true));
        for (int i = 0; i < 22; i++) block.add(new Observation(30, false));
        List<AnchorRecord> one = new ArrayList<>();
        for (Observation o : block) one.add(record(0, 700, Stratum.E, 0.1, o.time(), o.event(), false));

        List<AnchorRecord> three = new ArrayList<>();
        add(three, 40, 0, 700, Stratum.E, 0.2, 30, false, true);
        add(three, 40, 0, 700, Stratum.E, 0.5, 1, true, false);
        add(three, 40, 0, 700, Stratum.E, 0.8, 30, false, true);
        List<PooledBlock> single = PastCheck.blocks(one);
        List<PooledBlock> pooled = PastCheck.blocks(three);

        assertAll(
                () -> assertEquals(0.38, PastCheck.blockValue(block), 1e-12, "R(30) 0.44 less the tail 0.50 - 0.44"),
                () -> assertEquals(1, single.size(), "50 records make one block of at least 40"),
                () -> assertEquals(0.1, single.getFirst().centre(), 1e-12),
                () -> assertEquals(
                        (50 * 0.38 + 3 * 0.1) / 53,
                        single.getFirst().value(),
                        1e-12,
                        "3 prior records at the block mean p_nominal"),
                () -> assertEquals(50, single.getFirst().n()),
                () -> assertEquals(2, pooled.size(), "the block at 0.5 never stays away, so it pools with 0.2"),
                () -> assertEquals(0.35, pooled.get(0).centre(), 1e-12),
                () -> assertEquals((40 + 3 * 0.2 + 3 * 0.5) / 86, pooled.get(0).value(), 1e-12),
                () -> assertEquals(80, pooled.get(0).n()),
                () -> assertEquals(0.2, pooled.get(0).pLow()),
                () -> assertEquals(0.5, pooled.get(0).pHigh()),
                () -> assertEquals((40 + 3 * 0.8) / 43, pooled.get(1).value(), 1e-12),
                () -> assertTrue(pooled.get(0).value() <= pooled.get(1).value(), "values never fall"));
    }

    @Test
    void beyond() {
        List<Integer> four = List.of(0, 5, 15, 20);
        assertAll(
                () -> assertTrue(PastCheck.beyond(four, 31), "silent 11 days, longest gap 10"),
                () -> assertFalse(PastCheck.beyond(four, 30), "silent 10 days is not longer than 10"),
                () -> assertFalse(PastCheck.beyond(List.of(0, 5, 15), 100), "3 series days"));
    }

    @Test
    void check_scalesPStopByIdLoss_andGivesOpenSilenceMembersTheirCalendarValue() {
        int asOf = 1010;
        Map<Integer, List<Integer>> days = Map.of(0, List.of(1000, 1001), 1, List.of(1000), 2, List.of(1009));
        Series series = series(days);
        Identity identity = identity(days.keySet());
        IdChannelResult channel = new IdChannelResult(
                new IdLoss(List.of(), 1005, false, 1.0, 0.4, Map.of(1, 0.5), 0.0, List.of(), 0.3, 0.0),
                null,
                Map.of(1, 1005),
                Set.of(),
                Set.of());
        SilenceResult silence = new SilenceResult(
                List.of(new SilenceEvent(
                        new Scope(ScopeKind.APP, null),
                        1009,
                        null,
                        SilenceState.OPEN,
                        true,
                        30,
                        0.3,
                        0.1,
                        0.5,
                        0.5,
                        List.of(2),
                        asOf + 1)),
                Map.of(2, List.of(new ClockInterval(1009, 1010, 0.5))));
        PageStateResult page = new PageStateResult(PageState.OK, PageState.OK, null, Map.of(), false, List.of());
        Facts facts = facts(asOf);

        StopOdds odds = PastCheck.check(
                facts,
                series,
                identity,
                channel,
                silence,
                page,
                StopCurves.now(facts, series, identity, channel, silence, page));

        assertAll(
                () -> assertEquals(
                        new IdOdds(Stratum.K1, 1.0, 0.75, 0.75 * (1 - 0.4 * 0.5), null, false, false, 10, 5.0),
                        odds.byId().get(1),
                        "K1 steps to 0.5 at 1 day; the ID-loss silence ends at its onset; no anchor caps at 0.75"),
                () -> assertEquals(
                        new IdOdds(Stratum.K1, 0.5, 0.5, 0.5, 0.75, false, false, 1, 0.5),
                        odds.byId().get(2),
                        "half the day is held on the clock; on the calendar K1 steps to 2/3 at 1 day"),
                () -> assertEquals(
                        new IdOdds(Stratum.K2, 0.0, 0.0, 0.0, null, false, false, 9, 9.0),
                        odds.byId().get(0),
                        "K2 has no completed gap"),
                () -> assertEquals(14, odds.f()),
                () -> assertEquals(List.of(), odds.anchors()),
                () -> assertNull(odds.mApp()),
                () -> assertNull(odds.namedRight()),
                () -> assertEquals(Map.of(Stratum.K1, 0.75, Stratum.K2, 0.0, Stratum.E, 0.0), odds.plateau()),
                () -> assertEquals(Map.of(Stratum.K1, 1, Stratum.K2, 1, Stratum.E, 1), odds.tVis()),
                () -> assertEquals(Map.of(Stratum.K1, 1), odds.t50()));
    }

    /**
     * The bug: an ID censored after the anchor keeps a return that came after its censor day, or a silence that runs to
     * D, so the past check counts an ID it could no longer follow as one that came back or stayed away.
     */
    @Test
    void anchorRecords_censorAnIdAtItsCensorDay_afterTheAnchor() {
        int a = 1020;
        int asOf = 1050;
        Map<Integer, List<Integer>> days = Map.of(
                1, List.of(1000, 1010, 1030),
                2, List.of(1000, 1010, 1045),
                3, List.of(1000, 1010),
                4, List.of(1000, 1010),
                5, List.of(1000, 1010));
        Series series = series(days);
        IdChannelResult channel =
                new IdChannelResult(null, null, Map.of(1, 1040, 2, 1040, 3, 1040, 4, 1015), Set.of(), Set.of());

        List<AnchorRecord> records = PastCheck.anchorRecords(
                0,
                a,
                asOf,
                series.byId(),
                days.keySet(),
                days.keySet(),
                channel,
                new SilenceResult(List.of(), Map.of()));

        assertEquals(
                List.of(
                        record(0, a, Stratum.K2, 0.0, 10, true, false),
                        record(0, a, Stratum.K2, 0.0, 20, false, false),
                        record(0, a, Stratum.K2, 0.0, 20, false, false),
                        record(0, a, Stratum.K2, 0.0, 31, false, true)),
                records,
                "1 came back before its censor day; 2 came back after it and 3 never did, so both end at the censor day,"
                        + " 20 days on, and neither stayed away; 4 was censored before the anchor; 5 is followed to D."
                        + " Two days before the anchor is K2, and no K2 gap ended, so p_nominal is 0");
    }

    private static void add(
            List<AnchorRecord> records,
            int count,
            int anchor,
            int anchorDay,
            Stratum stratum,
            double pNominal,
            double time,
            boolean returned,
            boolean stayedAway) {
        for (int i = 0; i < count; i++) {
            records.add(new AnchorRecord(anchor, anchorDay, stratum, pNominal, false, time, returned, stayedAway));
        }
    }

    private static AnchorRecord record(
            int anchor, int anchorDay, Stratum stratum, double pNominal, double time, boolean returned, boolean away) {
        return new AnchorRecord(anchor, anchorDay, stratum, pNominal, false, time, returned, away);
    }

    private static Series series(Map<Integer, List<Integer>> days) {
        Map<Integer, IdSeries> byId = new TreeMap<>();
        days.forEach((u, ds) -> byId.put(
                u, new IdSeries(ds.getFirst(), ds, ActivityBasis.HUMAN, ds, List.of(), List.of(), false, null)));
        return new Series(1000, byId);
    }

    private static Identity identity(Set<Integer> ids) {
        Map<Integer, IdIdentity> byId = new TreeMap<>();
        for (int u : ids) byId.put(u, new IdIdentity(false, false, false, false, false, "(app)", null, true, null));
        return new Identity(
                byId,
                Map.of(),
                Map.of(),
                new GroupStats(0, 0, null, null, null, null, false),
                Map.of(),
                1.0,
                0.0,
                0.0,
                1.0,
                1.0,
                0.0);
    }

    private static Facts facts(int asOf) {
        return new Facts(
                asOf,
                1000,
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
                List.of());
    }
}
