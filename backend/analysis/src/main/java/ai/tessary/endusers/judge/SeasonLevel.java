// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.OVERDUE_FLOOR;
import static ai.tessary.endusers.judge.JudgeConstants.SEASON_LEVEL;
import static ai.tessary.endusers.judge.JudgeConstants.SEASON_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.SEASON_MU_MAX;
import static ai.tessary.endusers.judge.JudgeConstants.SEASON_MU_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.SLOW_BUFFER_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.SLOW_MAX_WEEKS;
import static ai.tessary.endusers.judge.JudgeConstants.SLOW_MIN_EXPECTED;
import static ai.tessary.endusers.judge.JudgeConstants.SLOW_MIN_WEEKS;
import static ai.tessary.endusers.judge.JudgeConstants.SLOW_SATURATION_PER_WEEK;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.StopOdds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The season level {@code mu} of spec 7.1 steps 1 to 6 (reference lines 3180 to 3239, reading 15 of spec 5), for the
 * banner of board P6; the slowing mixture of steps 7 and 8 is not ported. A tested ID is covered, can be followed, has
 * six or more series days before the week-grid day {@code G}, is 35 or more days old, has {@code p_stop} under 0.3, and
 * still comes in the recent window. Its recent use {@code r} is read against {@code E} from its own baseline rate, in
 * hour slots when it comes 4.2 or more days a week. Free IDs are tested IDs with no first hit of an issue that is not
 * always present in the 56 days before {@code D}. With 30 or more free IDs {@code mu} is the median of {@code r / E},
 * clamped to 0.4 to 1.1, and 1 when that median is 0.85 or more; with fewer it is 1.
 */
public final class SeasonLevel {

    private static final int MIN_SERIES_DAYS = 6; // [S][P]
    private static final int MIN_AGE_DAYS = 35; // [S][P]
    private static final int BASELINE_START_DAYS = 7; // [S][P]
    private static final int MIN_BASELINE_DAYS = 21; // [S][P]
    private static final double TARGET_UNITS = 6.0; // [S][P]
    private static final int SINCE_WEEKS = 3; // reading 27 of spec 5
    private static final int HOURS = 24;

    private SeasonLevel() {}

    /**
     * @param median the median of {@code r / E} over free IDs; null with fewer than 30
     * @param sinceDay about when the level fell: three weeks before {@code G} (reading 27 of spec 5)
     */
    public record Season(double mu, @Nullable Double median, int freeIds, int sinceDay) {

        /** The page prints the season level as its own fact when {@code mu} is under 0.85. */
        public boolean banner() {
            return median != null && mu < SEASON_LEVEL;
        }
    }

    record Observation(int recent, double expected) {}

    public static Season level(
            Facts facts,
            Series series,
            Identity identity,
            IdChannelResult channel,
            SilenceResult silence,
            StopOdds odds,
            HitSet hits) {
        int asOf = facts.asOfDay();
        int g = asOf - asOf % DAYS_PER_WEEK;
        Map<Integer, List<Integer>> freshHits = new HashMap<>();
        for (LineageHits lineage : hits.byLineage().values()) {
            if (lineage.alwaysPresent()) continue;
            lineage.hitDays()
                    .forEach((u, days) ->
                            freshHits.computeIfAbsent(u, k -> new ArrayList<>()).add(days.getFirst()));
        }
        Map<Integer, int[]> rows = DaySeries.rowsByRepresentative(facts.idDays(), identity.aliasOf());
        List<Double> free = new ArrayList<>();
        for (Map.Entry<Integer, IdSeries> e : series.byId().entrySet()) {
            int u = e.getKey();
            IdSeries s = e.getValue();
            if (!identity.byId().get(u).covered()
                    || channel.cannotFollow().contains(u)
                    || odds.byId().get(u).pStop() >= OVERDUE_FLOOR) {
                continue;
            }
            int[] slots =
                    hourUnit(s, g) ? hourSlots(facts.idDays(), facts.storedRouteClasses(), rows.get(u)) : new int[0];
            Observation o = observe(asOf, s, slots, silence.clock().getOrDefault(u, List.of()));
            if (o == null) continue;
            boolean fresh = freshHits.getOrDefault(u, List.of()).stream()
                    .anyMatch(d -> asOf - SLOW_BUFFER_DAYS <= d && d <= asOf);
            if (!fresh) free.add(o.recent() / o.expected());
        }
        return fromFree(free, g - SINCE_WEEKS * DAYS_PER_WEEK);
    }

    /**
     * Steps 1 to 4 for one ID: its recent use against what its baseline expects, or null when the ID is not tested.
     *
     * @param hourSlots the hours ({@code day * 24 + hour}) with a trace on a paced route, ascending
     * @param held the spans in which the ID was silent in a shared silence; their days leave both window lengths
     */
    static @Nullable Observation observe(int asOfDay, IdSeries id, int[] hourSlots, List<ClockInterval> held) {
        List<Integer> sr = id.days();
        int g = asOfDay - asOfDay % DAYS_PER_WEEK;
        if (sr.size() < MIN_SERIES_DAYS || asOfDay - id.firstSeen() < MIN_AGE_DAYS) return null;
        int k = StopCurves.lowerBound(sr, g);
        if (k < MIN_SERIES_DAYS) return null;
        int f0 = sr.getFirst();
        double weeks = Math.max(1.0, (g - f0) / (double) DAYS_PER_WEEK);
        boolean hours = k / weeks >= SLOW_SATURATION_PER_WEEK && hourSlots.length > 0;
        int[] units =
                hours ? hourSlots : sr.stream().mapToInt(Integer::intValue).toArray();
        IntUnaryOperator scale = d -> hours ? d * HOURS : d;
        Window count = (lo, hi) -> lowerBound(units, scale.applyAsInt(hi)) - lowerBound(units, scale.applyAsInt(lo));

        double lamAll = count.of(f0, g) / weeks;
        if (lamAll <= 0) return null;
        int n = Math.min(SLOW_MAX_WEEKS, Math.max(SLOW_MIN_WEEKS, (int) Math.ceil(TARGET_UNITS / lamAll)));
        int w0 = g - DAYS_PER_WEEK * n;
        int b0 = f0 + BASELINE_START_DAYS;
        int b1 = Math.min(w0, Math.max(f0 + MIN_AGE_DAYS, w0 - SLOW_BUFFER_DAYS));
        int baselineDays = (b1 - b0) - heldDays(held, b0, b1);
        if (baselineDays < MIN_BASELINE_DAYS) return null;
        double lam = (count.of(b0, b1) + 0.5) / (baselineDays / (double) DAYS_PER_WEEK + 0.25);
        int recentDays = DAYS_PER_WEEK * n - heldDays(held, w0, g);
        double expected = lam * recentDays / DAYS_PER_WEEK;
        int recent = count.of(w0, g);
        boolean stillComes = StopCurves.lowerBound(sr, g) - StopCurves.lowerBound(sr, w0) >= 1;
        if (expected < SLOW_MIN_EXPECTED || !stillComes) return null;
        return new Observation(recent, expected);
    }

    /** Step 6 over the {@code r / E} of the free IDs. */
    static Season fromFree(List<Double> freeRatios, int sinceDay) {
        if (freeRatios.size() < SEASON_MIN_IDS) return new Season(1.0, null, freeRatios.size(), sinceDay);
        double median = Stats.median(
                freeRatios.stream().mapToDouble(Double::doubleValue).toArray());
        double mu = median >= SEASON_LEVEL ? 1.0 : Math.min(SEASON_MU_MAX, Math.max(SEASON_MU_MIN, median));
        return new Season(mu, median, freeRatios.size(), sinceDay);
    }

    private static boolean hourUnit(IdSeries s, int g) {
        int k = StopCurves.lowerBound(s.days(), g);
        double weeks = Math.max(1.0, (g - s.days().getFirst()) / (double) DAYS_PER_WEEK);
        return k / weeks >= SLOW_SATURATION_PER_WEEK;
    }

    private static int[] hourSlots(IdDays rows, RouteClasses classes, int[] idx) {
        List<Integer> slots = new ArrayList<>();
        for (int i : idx) {
            if (!DaySeries.isPaced(DaySeries.classOf(classes, rows.routeNo()[i]), classes.lenient())) continue;
            int mask = rows.hourMask()[i];
            for (int h = 0; h < HOURS; h++) {
                if ((mask & (1 << h)) != 0) slots.add(rows.day()[i] * HOURS + h);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
    }

    /** Days of {@code [lo, hi)} inside the held spans, each span counted on its own as the reference does. */
    private static int heldDays(List<ClockInterval> held, int lo, int hi) {
        int days = 0;
        for (ClockInterval iv : held) days += Math.max(0, Math.min(hi, iv.toDay()) - Math.max(lo, iv.fromDay()));
        return days;
    }

    /** The values are distinct, so a hit is the first index holding {@code value}. */
    private static int lowerBound(int[] sorted, int value) {
        int i = Arrays.binarySearch(sorted, value);
        return i < 0 ? -i - 1 : i;
    }

    @FunctionalInterface
    private interface Window {
        int of(int fromDay, int toDay);
    }
}
