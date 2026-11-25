// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.H_DEFAULT;
import static ai.tessary.endusers.judge.JudgeConstants.H_FACTOR;
import static ai.tessary.endusers.judge.JudgeConstants.H_MAX;
import static ai.tessary.endusers.judge.JudgeConstants.H_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.REACH_USER_DAYS;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import org.jspecify.annotations.Nullable;

/**
 * Person-time trials of spec 8.3: each series day of a user of the reach asks "is this active day the last one?", in
 * the exposed arm when the issue hit the ID in the {@code H} days before, in the unexposed arm when it has not hit it
 * yet. Only trials in [{@code first_ev - 28}, min({@code D - 1}, {@code last_ev + H + 7})] are built (reading 18).
 */
public final class Trials {

    private static final int TAIL_DAYS = 7;

    private Trials() {}

    public enum Arm {
        UNEXPOSED,
        EXPOSED,
        /** After the exposure ended: in neither arm. */
        NEITHER
    }

    /**
     * @param weight {@code max(1, dose)} for an unexposed trial, 1 otherwise
     * @param yLo {@code p_stop} on the ID's last series day, else 0
     * @param yHi 1 on the ID's last series day, else 0
     */
    public record Trial(int id, int day, double weight, double yLo, double yHi, Cell cell, Arm arm) {

        public int week() {
            return Math.floorDiv(day, DAYS_PER_WEEK);
        }

        Trial withoutAccount() {
            return new Trial(id, day, weight, yLo, yHi, cell.withoutAccount(), arm);
        }
    }

    /**
     * One covered ID that can be followed and has a trace on the reach.
     *
     * @param dose traces on the reach per day, only days with one
     * @param evidence the issue's evidence days of the ID, ascending; empty when it was not hit
     * @param allEvidence the evidence days of every issue, ascending, one entry per issue and day
     */
    public record Person(
            IdSeries series,
            boolean bornInChange,
            double pStop,
            @Nullable Integer account,
            NavigableMap<Integer, Integer> dose,
            List<Integer> evidence,
            List<Integer> allEvidence) {}

    /** {@code H} = clamp(round(4 x {@code m_app}), 14, 42), rounded half to even as the reference; 21 undefined. */
    public static int h(@Nullable Double mAppCalendar) {
        if (mAppCalendar == null) return H_DEFAULT;
        return (int) Math.max(H_MIN, Math.min(H_MAX, Math.rint(H_FACTOR * mAppCalendar)));
    }

    /** The trials of every person, by key index (in the map's order) and day. */
    public static List<Trial> build(
            Map<Integer, Person> people, int firstEvidence, int lastEvidence, int h, int asOfDay) {
        int from = firstEvidence - REACH_USER_DAYS;
        int to = Math.min(asOfDay - 1, lastEvidence + h + TAIL_DAYS);
        List<Trial> out = new ArrayList<>();
        people.forEach((u, p) -> {
            List<Integer> days = p.series().days();
            int last = days.getLast();
            for (int i = Cells.lowerBound(days, from); i < Cells.upperBound(days, to); i++) {
                int d = days.get(i);
                Integer reachDay = p.dose().floorKey(d);
                if (reachDay == null || reachDay < d - REACH_USER_DAYS) continue;
                int dose = p.dose().getOrDefault(d, 0);
                Arm arm = arm(p.evidence(), d, h);
                int inWindow = count(p.evidence(), d - h, d);
                boolean other = count(p.allEvidence(), d - h, d) > inWindow;
                Cell cell = Cells.cell(p.series(), p.bornInChange(), d, dose, other, p.account());
                boolean isLast = d == last;
                out.add(new Trial(
                        u,
                        d,
                        arm == Arm.UNEXPOSED ? Math.max(1, dose) : 1,
                        isLast ? p.pStop() : 0,
                        isLast ? 1 : 0,
                        cell,
                        arm));
            }
        });
        return out;
    }

    private static Arm arm(List<Integer> evidence, int day, int h) {
        int seen = Cells.upperBound(evidence, day);
        if (seen == 0) return Arm.UNEXPOSED;
        return evidence.get(seen - 1) >= day - h ? Arm.EXPOSED : Arm.NEITHER;
    }

    private static int count(List<Integer> days, int from, int to) {
        return Cells.upperBound(days, to) - Cells.lowerBound(days, from);
    }
}
