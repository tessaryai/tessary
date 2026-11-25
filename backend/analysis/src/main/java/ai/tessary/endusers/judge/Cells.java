// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CONTROL_FLOOR;
import static ai.tessary.endusers.judge.JudgeConstants.OLD_ID_DAYS;

import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The cells of spec 8.4 and the control pool of each: unexposed trials of the same cell, widened in a fixed chain while
 * the pool has fewer than {@link JudgeConstants#CONTROL_FLOOR} trials.
 */
public final class Cells {

    private static final int RUN_DAYS = 7;

    private Cells() {}

    /**
     * @param age 0 first use of a one-day ID, 1 other first use, 2 for 1-2 days, 3 for 3-6, 4 for 7-27, 5 for 28+
     * @param earlier series days in the 28 days before: 0, 1-2, 3-6, 7-13, 14+
     * @param run series days in the 7 days before: 0, 1-2, 3+
     * @param dose traces on the reach that day, in the reference's cells: 0-1, 2-4, 5-9, 10-19, 20+
     * @param otherIssue another issue's evidence in the exposure window, the day itself included
     * @param account the account cluster; null when clusters do not exist or the ID names none
     */
    public record Cell(
            int age,
            int earlier,
            int run,
            int dose,
            boolean otherIssue,
            @Nullable Integer account) {

        Cell withoutAccount() {
            return new Cell(age, earlier, run, dose, otherIssue, null);
        }
    }

    public static Cell cell(
            IdSeries series, boolean bornInChange, int day, int dose, boolean otherIssue, @Nullable Integer account) {
        List<Integer> days = series.days();
        return new Cell(
                age(series, bornInChange, day), earlier(days, day), run(days, day), dose(dose), otherIssue, account);
    }

    /** Left-censored IDs and IDs born in an ID change were in use before their first day here, so they are 28+. */
    static int age(IdSeries series, boolean bornInChange, int day) {
        if (series.leftCensored() || bornInChange) return 5;
        int tenure = day - series.firstSeen();
        if (tenure <= 0) return series.days().size() == 1 ? 0 : 1;
        if (tenure <= 2) return 2;
        if (tenure <= 6) return 3;
        return tenure < OLD_ID_DAYS ? 4 : 5;
    }

    static int earlier(List<Integer> days, int day) {
        int n = lowerBound(days, day) - lowerBound(days, day - OLD_ID_DAYS);
        if (n <= 0) return 0;
        if (n <= 2) return 1;
        if (n <= 6) return 2;
        return n <= 13 ? 3 : 4;
    }

    static int run(List<Integer> days, int day) {
        int n = lowerBound(days, day) - lowerBound(days, day - RUN_DAYS);
        if (n <= 0) return 0;
        return n <= 2 ? 1 : 2;
    }

    static int dose(int traces) {
        if (traces <= 1) return 0;
        if (traces <= 4) return 1;
        if (traces <= 9) return 2;
        return traces <= 19 ? 3 : 4;
    }

    /** The index of the first value at or above {@code x} in an ascending list. */
    static int lowerBound(List<Integer> xs, int x) {
        int i = Collections.binarySearch(xs, x);
        if (i < 0) return -i - 1;
        while (i > 0 && xs.get(i - 1) == x) i--;
        return i;
    }

    /** The index after the last value at or below {@code x} in an ascending list. */
    static int upperBound(List<Integer> xs, int x) {
        return lowerBound(xs, x + 1);
    }

    /** A control pool. Its identity matters: the own null takes false hits off each pool object apart. */
    public static final class Pool {

        private final List<Trial> rows;
        private final double weight;
        private final double sumLo;
        private final double sumHi;
        private final double m;

        private Pool(List<Trial> rows) {
            this.rows = Collections.unmodifiableList(rows);
            double w = 0;
            double lo = 0;
            double hi = 0;
            double w2 = 0;
            for (Trial t : rows) {
                w += t.weight();
                lo += t.weight() * t.yLo();
                hi += t.weight() * t.yHi();
                w2 += t.weight() * t.weight();
            }
            this.weight = w;
            this.sumLo = lo;
            this.sumHi = hi;
            this.m = w * w / w2;
        }

        public List<Trial> rows() {
            return rows;
        }

        public double weight() {
            return weight;
        }

        double sumLo() {
            return sumLo;
        }

        /** {@code h_c} of {@code y_lo}: the weighted mean over the pool. */
        public double hLo() {
            return sumLo / weight;
        }

        public double hHi() {
            return sumHi / weight;
        }

        /** {@code m_c}, the effective pool size. */
        public double m() {
            return m;
        }
    }

    /**
     * @param level 0 exact cell; 1 no account; 2 age, earlier and dose; 3 the same over weeks w-1 to w+1; 4 age and dose
     *     over weeks w-1 to w+1
     */
    public record Match(Pool pool, int level) {}

    /** The unexposed trials by week and cell, with each (week, cell)'s pool kept once. */
    public static final class Pools {

        private final Map<Integer, Map<Cell, List<Trial>>> byWeek = new TreeMap<>();
        private final Map<WeekCell, @Nullable Match> memo = new HashMap<>();

        public Pools(List<Trial> trials) {
            for (Trial t : trials) {
                if (t.arm() == Arm.UNEXPOSED) {
                    byWeek.computeIfAbsent(t.week(), w -> new LinkedHashMap<>())
                            .computeIfAbsent(t.cell(), c -> new ArrayList<>())
                            .add(t);
                }
            }
        }

        public @Nullable Match match(int week, Cell cell) {
            WeekCell key = new WeekCell(week, cell);
            if (memo.containsKey(key)) return memo.get(key);
            Match found = null;
            for (int level = 0; level <= 4 && found == null; level++) {
                List<Trial> rows = rows(week, cell, level);
                if (rows.size() >= CONTROL_FLOOR) found = new Match(new Pool(rows), level);
            }
            memo.put(key, found);
            return found;
        }

        private List<Trial> rows(int week, Cell cell, int level) {
            List<Trial> out = new ArrayList<>();
            int span = level >= 3 ? 1 : 0;
            for (int w = week - span; w <= week + span; w++) {
                byWeek.getOrDefault(w, Map.of()).forEach((c, rows) -> {
                    if (like(c, cell, level)) out.addAll(rows);
                });
            }
            return out;
        }

        private static boolean like(Cell c, Cell cell, int level) {
            return switch (level) {
                case 0 -> c.equals(cell);
                case 1 -> c.withoutAccount().equals(cell.withoutAccount());
                case 2, 3 -> c.age() == cell.age() && c.earlier() == cell.earlier() && c.dose() == cell.dose();
                default -> c.age() == cell.age() && c.dose() == cell.dose();
            };
        }
    }

    private record WeekCell(int week, Cell cell) {}
}
