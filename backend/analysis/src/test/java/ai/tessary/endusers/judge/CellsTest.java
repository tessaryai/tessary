// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.Cells.Match;
import ai.tessary.endusers.judge.Cells.Pool;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cells and control pools of spec 8.4. A one-day ID compared with IDs that came back, an age that ignores a start
 * before the data, a pool that widens in the wrong order, or a pool that takes an exposed trial would compare a hit ID
 * with the wrong like IDs.
 */
class CellsTest {

    private static final int WEEK = 10;
    private static final int DAY = 70;
    private static final Cell CELL = new Cell(4, 2, 1, 1, false, 7);

    @Test
    void ageCells() {
        IdSeries oneDay = series(100, List.of(100), false);
        IdSeries many = series(100, List.of(100, 101, 103, 107, 127, 128), false);
        IdSeries leftCensored = series(100, List.of(100, 101), true);
        assertAll(
                () -> assertEquals(0, Cells.age(oneDay, false, 100), "0 with one series day"),
                () -> assertEquals(1, Cells.age(many, false, 100), "0 other"),
                () -> assertEquals(2, Cells.age(many, false, 101)),
                () -> assertEquals(2, Cells.age(many, false, 102)),
                () -> assertEquals(3, Cells.age(many, false, 103)),
                () -> assertEquals(3, Cells.age(many, false, 106)),
                () -> assertEquals(4, Cells.age(many, false, 107)),
                () -> assertEquals(4, Cells.age(many, false, 127)),
                () -> assertEquals(5, Cells.age(many, false, 128), "28+"),
                () -> assertEquals(5, Cells.age(leftCensored, false, 100), "left-censored IDs use 28+"),
                () -> assertEquals(5, Cells.age(many, true, 100), "IDs born in an ID change use 28+"));
    }

    @Test
    void earlierActivity_countsSeriesDaysInThe28DaysBefore() {
        List<Integer> days = range(72, 100);
        assertAll(
                () -> assertEquals(0, Cells.earlier(days, 72), "the day itself is not before it"),
                () -> assertEquals(1, Cells.earlier(days, 73)),
                () -> assertEquals(1, Cells.earlier(days, 74)),
                () -> assertEquals(2, Cells.earlier(days, 75)),
                () -> assertEquals(2, Cells.earlier(days, 78)),
                () -> assertEquals(3, Cells.earlier(days, 79)),
                () -> assertEquals(3, Cells.earlier(days, 85)),
                () -> assertEquals(4, Cells.earlier(days, 86)),
                () -> assertEquals(4, Cells.earlier(days, 100), "28 days before"),
                () -> assertEquals(0, Cells.earlier(List.of(50, 100), 100), "day 50 is 50 days before"));
    }

    @Test
    void run_countsSeriesDaysInThe7DaysBefore() {
        List<Integer> days = List.of(90, 93, 94, 95, 100);
        assertAll(
                () -> assertEquals(0, Cells.run(days, 90)),
                () -> assertEquals(1, Cells.run(days, 93)),
                () -> assertEquals(1, Cells.run(days, 94)),
                () -> assertEquals(2, Cells.run(days, 95)),
                () -> assertEquals(2, Cells.run(days, 100)),
                () -> assertEquals(1, Cells.run(days, 102), "day 94 is 8 days before"));
    }

    @Test
    void doseCells_followTheReference() {
        assertAll(
                () -> assertEquals(0, Cells.dose(0)),
                () -> assertEquals(0, Cells.dose(1)),
                () -> assertEquals(1, Cells.dose(2)),
                () -> assertEquals(1, Cells.dose(4)),
                () -> assertEquals(2, Cells.dose(5)),
                () -> assertEquals(2, Cells.dose(9)),
                () -> assertEquals(3, Cells.dose(10)),
                () -> assertEquals(3, Cells.dose(19)),
                () -> assertEquals(4, Cells.dose(20)));
    }

    @Test
    void cell_joinsTheParts() {
        IdSeries s = series(80, range(80, 100), false);
        assertEquals(new Cell(4, 4, 2, 2, true, 3), Cells.cell(s, false, 100, 6, true, 3));
    }

    @Test
    void poolChain() {
        List<Trial> four = controls(1, 4, CELL, DAY);
        assertAll(
                () -> assertEquals(0, level(four, control(5, CELL, DAY + 1)), "exact cell"),
                () -> assertEquals(1, level(four, control(5, new Cell(4, 2, 1, 1, false, 8), DAY)), "no account"),
                () -> assertEquals(
                        2, level(four, control(5, new Cell(4, 2, 2, 1, true, 8), DAY)), "no other issue and no run"),
                () -> assertEquals(
                        3, level(four, control(5, new Cell(4, 2, 2, 1, true, 8), DAY + 7)), "weeks w-1 to w+1"),
                () -> assertEquals(
                        4, level(four, control(5, new Cell(4, 3, 0, 1, false, 7), DAY - 7)), "age and dose only"),
                () -> assertNull(new Pools(four).match(WEEK, CELL), "with none the trial is unmatched"),
                () -> assertNull(
                        new Pools(concat(four, control(5, new Cell(3, 2, 1, 1, false, 7), DAY))).match(WEEK, CELL),
                        "another age is never like"),
                () -> assertNull(
                        new Pools(concat(four, control(5, CELL, DAY + 14))).match(WEEK, CELL),
                        "two weeks away is never like"));
    }

    @Test
    void pool_takesUnexposedTrialsOnly() {
        List<Trial> trials = new ArrayList<>(controls(1, 4, CELL, DAY));
        trials.add(new Trial(5, DAY, 1, 0, 0, CELL, Arm.EXPOSED));
        trials.add(new Trial(6, DAY, 1, 0, 0, CELL, Arm.NEITHER));
        assertNull(new Pools(trials).match(WEEK, CELL));
    }

    @Test
    void pool_weightedMeanAndEffectiveSize() {
        List<Trial> trials = List.of(
                new Trial(1, DAY, 1, 0, 0, CELL, Arm.UNEXPOSED),
                new Trial(2, DAY, 1, 0, 0, CELL, Arm.UNEXPOSED),
                new Trial(3, DAY, 2, 0, 0, CELL, Arm.UNEXPOSED),
                new Trial(4, DAY, 2, 0, 0, CELL, Arm.UNEXPOSED),
                new Trial(5, DAY, 4, 0.5, 1, CELL, Arm.UNEXPOSED));
        Match match = new Pools(trials).match(WEEK, CELL);
        assertNotNull(match);
        Pool pool = match.pool();
        assertAll(
                () -> assertEquals(5, pool.rows().size()),
                () -> assertEquals(10, pool.weight(), 1e-12),
                () -> assertEquals(0.2, pool.hLo(), 1e-12),
                () -> assertEquals(0.4, pool.hHi(), 1e-12),
                () -> assertEquals(100.0 / 26, pool.m(), 1e-12));
    }

    @Test
    void pools_areKeptPerWeekAndCell() {
        Cell otherAccount = new Cell(4, 2, 1, 1, false, 8);
        Pools pools = new Pools(concat(controls(1, 4, CELL, DAY), control(5, otherAccount, DAY)));
        Match first = pools.match(WEEK, CELL);
        Match second = pools.match(WEEK, otherAccount);
        assertNotNull(first);
        assertNotNull(second);
        assertAll(
                () -> assertSame(first, pools.match(WEEK, CELL), "the same week and cell give the same pool"),
                () -> assertNotSame(first.pool(), second.pool(), "another cell keeps its own pool of the same rows"),
                () -> assertEquals(
                        first.pool().rows().size(), second.pool().rows().size()));
    }

    private static int level(List<Trial> base, Trial extra) {
        Match match = new Pools(concat(base, extra)).match(WEEK, CELL);
        assertNotNull(match);
        return match.level();
    }

    private static List<Trial> controls(int fromId, int count, Cell cell, int day) {
        List<Trial> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(control(fromId + i, cell, day));
        return out;
    }

    private static Trial control(int id, Cell cell, int day) {
        return new Trial(id, day, 1, 0, 0, cell, Arm.UNEXPOSED);
    }

    private static List<Trial> concat(List<Trial> base, Trial extra) {
        List<Trial> out = new ArrayList<>(base);
        out.add(extra);
        return out;
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int d = from; d <= to; d++) out.add(d);
        return out;
    }

    static IdSeries series(int firstSeen, List<Integer> days, boolean leftCensored) {
        return new IdSeries(firstSeen, days, ActivityBasis.HUMAN, days, List.of(), List.of(), leftCensored, null);
    }
}
