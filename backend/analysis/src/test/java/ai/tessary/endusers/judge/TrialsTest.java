// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.Cells.Match;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Person;
import ai.tessary.endusers.judge.Trials.Trial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Person-time trials of spec 8.3. A window that is not renewed by each hit, a control whose later hit forces its
 * outcome to zero and drops it, an outcome read on a day that is not the last, a trial on day {@code D}, or a control
 * that is not weighted by its dose would give a wide-reach finding stops it did not cause.
 */
class TrialsTest {

    private static final int D = 300;

    @Test
    void h() {
        assertAll(
                () -> assertEquals(14, Trials.h(2.3)),
                () -> assertEquals(24, Trials.h(6.0)),
                () -> assertEquals(42, Trials.h(12.0)),
                () -> assertEquals(21, Trials.h(null), "undefined"),
                () -> assertEquals(22, Trials.h(5.625), "22.5 rounds half to even, as the reference"));
    }

    @Test
    void exposure_isRenewedByEachHit() {
        Person p = person(daily(100, 160), List.of(110, 130), 0.5);
        Map<Integer, Arm> arms = arms(Trials.build(Map.of(1, p), 110, 130, 14, D));
        assertAll(
                () -> assertEquals(Arm.UNEXPOSED, arms.get(109)),
                () -> assertTrue(IntStream.rangeClosed(110, 124).allMatch(d -> arms.get(d) == Arm.EXPOSED)),
                () -> assertTrue(IntStream.rangeClosed(125, 129).allMatch(d -> arms.get(d) == Arm.NEITHER)),
                () -> assertEquals(Arm.NEITHER, arms.get(127), "day 27 is in neither arm"),
                () -> assertTrue(IntStream.rangeClosed(130, 144).allMatch(d -> arms.get(d) == Arm.EXPOSED)),
                () -> assertEquals(Arm.NEITHER, arms.get(145)));
    }

    @Test
    void unexposedTrial_ofLaterHitId_hasOutcomeZero_andIsKept() {
        Map<Integer, Person> people = new TreeMap<>();
        for (int u = 1; u <= 5; u++) people.put(u, person(daily(100, 140), List.of(130), 0.9));
        List<Trial> trials = Trials.build(people, 130, 130, 14, D);
        Trial before = trials.stream()
                .filter(t -> t.id() == 1 && t.day() == 120)
                .findFirst()
                .orElseThrow();
        Match pool = new Pools(trials).match(before.week(), before.cell());
        assertNotNull(pool);
        assertAll(
                () -> assertEquals(Arm.UNEXPOSED, before.arm()),
                () -> assertEquals(0.0, before.yLo()),
                () -> assertEquals(0.0, before.yHi()),
                () -> assertTrue(pool.pool().rows().contains(before), "the control trial is in the pool"));
    }

    @Test
    void outcome() {
        Person p = person(List.of(100, 105, 110), List.of(), 0.7);
        Map<Integer, Trial> byDay = byDay(Trials.build(Map.of(1, p), 100, 100, 14, D));
        assertAll(
                () -> assertEquals(0.7, byDay.get(110).yLo(), "the last series day: p_stop"),
                () -> assertEquals(1.0, byDay.get(110).yHi()),
                () -> assertEquals(0.0, byDay.get(100).yLo()),
                () -> assertEquals(0.0, byDay.get(100).yHi()),
                () -> assertEquals(0.0, byDay.get(105).yLo()),
                () -> assertEquals(0.0, byDay.get(105).yHi()));
    }

    @Test
    void noTrialOnDayD_soAnIdActiveToday_neverStops() {
        Person p = person(List.of(D - 10, D - 5, D), List.of(), 0.7);
        List<Trial> trials = Trials.build(Map.of(1, p), D - 10, D - 10, 14, D);
        assertAll(
                () -> assertEquals(
                        List.of(D - 10, D - 5), trials.stream().map(Trial::day).toList()),
                () -> assertTrue(trials.stream().allMatch(t -> t.yHi() == 0)));
    }

    @Test
    void weights_ofControlsAreTheirDose_exposedTrialsWeighOne() {
        NavigableMap<Integer, Integer> dose = new TreeMap<>(Map.of(100, 3, 120, 5));
        Person p = new Person(
                CellsTest.series(100, List.of(100, 110, 120), false),
                false,
                0.5,
                null,
                dose,
                List.of(120),
                List.of(120));
        Map<Integer, Trial> byDay = byDay(Trials.build(Map.of(1, p), 120, 120, 14, D));
        assertAll(
                () -> assertEquals(3.0, byDay.get(100).weight()),
                () -> assertEquals(1.0, byDay.get(110).weight(), "no trace on the reach that day"),
                () -> assertEquals(0, byDay.get(110).cell().dose()),
                () -> assertEquals(Arm.EXPOSED, byDay.get(120).arm()),
                () -> assertEquals(1.0, byDay.get(120).weight()));
    }

    @Test
    void userOfReach_needsATraceOnTheReachInThe28DaysBefore() {
        NavigableMap<Integer, Integer> dose = new TreeMap<>(Map.of(100, 1));
        Person p = new Person(
                CellsTest.series(90, List.of(90, 99, 100, 128, 129), false),
                false,
                0.5,
                null,
                dose,
                List.of(),
                List.of());
        List<Trial> trials = Trials.build(Map.of(1, p), 110, 110, 14, D);
        assertEquals(List.of(100, 128), trials.stream().map(Trial::day).toList());
    }

    @Test
    void window_isTheStartLess28_toTheLastEvidencePlusHPlus7() {
        Person p = person(daily(50, 250), List.of(), 0.5);
        List<Trial> trials = Trials.build(Map.of(1, p), 110, 130, 14, D);
        assertAll(
                () -> assertEquals(82, trials.getFirst().day()),
                () -> assertEquals(151, trials.getLast().day()),
                () -> assertEquals(70, trials.size()));
    }

    @Test
    void otherIssue_countsAnotherIssuesHitsInTheWindow_sameDayIncluded() {
        Person sameDay = person(daily(100, 115), List.of(110), List.of(110, 110));
        Person alone = person(daily(100, 115), List.of(110), List.of(110));
        Person earlier = person(daily(100, 115), List.of(110), List.of(90, 110));
        Person notHit = person(daily(100, 115), List.of(), List.of(105));
        assertAll(
                () -> assertTrue(byDay(Trials.build(Map.of(1, sameDay), 110, 110, 14, D))
                        .get(110)
                        .cell()
                        .otherIssue()),
                () -> assertFalse(byDay(Trials.build(Map.of(1, alone), 110, 110, 14, D))
                        .get(110)
                        .cell()
                        .otherIssue()),
                () -> assertFalse(
                        byDay(Trials.build(Map.of(1, earlier), 110, 110, 14, D))
                                .get(110)
                                .cell()
                                .otherIssue(),
                        "20 days before with H 14"),
                () -> assertTrue(byDay(Trials.build(Map.of(1, notHit), 110, 110, 14, D))
                        .get(105)
                        .cell()
                        .otherIssue()),
                () -> assertFalse(byDay(Trials.build(Map.of(1, notHit), 110, 110, 14, D))
                        .get(104)
                        .cell()
                        .otherIssue()));
    }

    @Test
    void trial_carriesItsCellAndAccount() {
        NavigableMap<Integer, Integer> dose = new TreeMap<>(Map.of(100, 2));
        Person p = new Person(CellsTest.series(100, List.of(100), true), false, 0.4, 9, dose, List.of(), List.of());
        Trial t = Trials.build(Map.of(4, p), 100, 100, 14, D).getFirst();
        assertAll(
                () -> assertEquals(4, t.id()),
                () -> assertEquals(14, t.week()),
                () -> assertEquals(new Cells.Cell(5, 0, 0, 1, false, 9), t.cell()));
    }

    private static Map<Integer, Arm> arms(List<Trial> trials) {
        Map<Integer, Arm> out = new TreeMap<>();
        trials.forEach(t -> out.put(t.day(), t.arm()));
        return out;
    }

    private static Map<Integer, Trial> byDay(List<Trial> trials) {
        Map<Integer, Trial> out = new TreeMap<>();
        trials.forEach(t -> out.put(t.day(), t));
        return out;
    }

    private static Person person(List<Integer> days, List<Integer> evidence, double pStop) {
        return person(days, evidence, evidence, pStop);
    }

    private static Person person(List<Integer> days, List<Integer> evidence, List<Integer> allEvidence) {
        return person(days, evidence, allEvidence, 0.5);
    }

    private static Person person(List<Integer> days, List<Integer> evidence, List<Integer> allEvidence, double pStop) {
        NavigableMap<Integer, Integer> dose = new TreeMap<>();
        days.forEach(d -> dose.put(d, 1));
        return new Person(
                CellsTest.series(days.getFirst(), days, false), false, pStop, null, dose, evidence, allEvidence);
    }

    private static List<Integer> daily(int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int d = from; d <= to; d++) out.add(d);
        return out;
    }
}
