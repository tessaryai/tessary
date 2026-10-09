// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierRowBuilder;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ChartSeries.Availability;
import ai.tessary.classifier.chart.ChartSeries.Facts;
import ai.tessary.classifier.chart.ClassifierChartDtos.ArmingView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartBaseline;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartPoint;
import ai.tessary.classifier.chart.ClassifierChartDtos.HeadlineView;
import ai.tessary.classifier.chart.ClassifierChartRepository.CountRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RangeRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RateRow;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.worker.ClassifierArming;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The pure rules behind a chart card. */
class ChartSeriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Facts NO_FACTS = new Facts(null, Set.of(), Set.of());
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Grid GRID = Grid.duration();

    private static Instant at(int hour) {
        return FROM.plus(Duration.ofHours(hour));
    }

    private static RateRow rate(int hour, long checked, long flagged) {
        return new RateRow(at(hour), checked, flagged);
    }

    private static ChartPoint ratePoint(int start, int end, long checked, long flagged, boolean open) {
        return ChartPoint.rate(at(start), at(end), open, checked, flagged);
    }

    // ---- ranges -------------------------------------------------------------------------------------

    @Test
    void ranges_areSevenAndTwentyEightDaysOnly() {
        assertEquals(Set.of(7, 28), ChartSeries.RANGES, "90 days is gone");
    }

    // ---- the merge rule -----------------------------------------------------------------------------

    /** A busy stretch closes a point every time it reaches 500, long before a day has passed. */
    @Test
    void merge_busyStretchClosesAtFiveHundred() {
        List<RateRow> rows = new ArrayList<>();
        for (int h = 0; h < 6; h++) rows.add(rate(h, 250, 10));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(5).plusSeconds(1800), rows);

        assertEquals(
                List.of(
                        ratePoint(0, 2, 500, 20, false),
                        ratePoint(2, 4, 500, 20, false),
                        ratePoint(4, 6, 500, 20, true)),
                points,
                "three points of two hours each, closed at 500; the last holds the current hour, so it is still open");
    }

    /** A point that reached 500 in an hour already over is final; only one holding the current hour can still grow. */
    @Test
    void merge_pointClosedAtTargetIsOpenOnlyWhileItHoldsTheCurrentHour() {
        List<RateRow> rows = List.of(rate(0, 300, 3), rate(1, 300, 3));

        assertEquals(
                List.of(ratePoint(0, 2, 600, 6, true)),
                ChartSeries.ratePoints(FROM, at(1).plusSeconds(60), rows),
                "hour 1 is still filling: the next read can count more in it");
        assertEquals(
                List.of(ratePoint(0, 2, 600, 6, false)), ChartSeries.ratePoints(FROM, at(2), rows), "hour 1 is over");
    }

    /** The last point is open only while the current hour could still join it, within 24 hours of its start. */
    @Test
    void merge_lastPointTooOldToJoinIsClosed() {
        List<RateRow> rows = List.of(rate(10, 3, 0));

        assertEquals(List.of(ratePoint(10, 11, 3, 0, true)), ChartSeries.ratePoints(FROM, at(33), rows));
        assertEquals(
                List.of(ratePoint(10, 11, 3, 0, false)),
                ChartSeries.ratePoints(FROM, at(34), rows),
                "hour 34 would make it 25 hours long, so it starts a new point: this one can no longer change");
        assertEquals(
                List.of(ratePoint(10, 11, 3, 0, false)),
                ChartSeries.ratePoints(FROM, at(6 * 24 + 12).plusSeconds(1800), rows),
                "days old, so not still filling");
    }

    /** A quiet stretch never reaches 500: a point spans at most 24 hours, then the next one starts. */
    @Test
    void merge_quietStretchIsCappedAtTwentyFourHours() {
        List<RateRow> rows = new ArrayList<>();
        for (int h = 0; h < 30; h++) rows.add(rate(h, 1, 0));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(29), rows);

        assertEquals(
                List.of(ratePoint(0, 24, 24, 0, false), ratePoint(24, 30, 6, 0, true)),
                points,
                "hour 23 ends exactly 24h after the start and joins; hour 24 would make it 25h and starts a new one");
    }

    /** An empty hour neither starts a point nor stretches one: the point ends where its last item's hour ends. */
    @Test
    void merge_emptyHoursInsideAPointDoNotMoveItsEnd() {
        List<RateRow> rows = List.of(rate(0, 5, 1), rate(3, 5, 0), rate(7, 5, 2));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(10), rows);

        assertEquals(List.of(ratePoint(0, 8, 15, 3, true)), points);
    }

    /** After a point closes, empty hours leave a gap: the next point starts at the next hour with items. */
    @Test
    void merge_gapOfEmptyHoursBetweenPoints() {
        List<RateRow> rows = List.of(rate(0, 600, 6), rate(5, 10, 1));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(8), rows);

        assertEquals(List.of(ratePoint(0, 1, 600, 6, false), ratePoint(5, 6, 10, 1, true)), points);
    }

    /** One hour that alone holds 500 or more closes the point it joins, whatever it held before. */
    @Test
    void merge_hourAloneAboveTargetClosesThePoint() {
        List<RateRow> rows = List.of(rate(0, 100, 1), rate(1, 700, 7), rate(2, 3, 0));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(2), rows);

        assertEquals(List.of(ratePoint(0, 2, 800, 8, false), ratePoint(2, 3, 3, 0, true)), points);
    }

    /** The walk starts at the range's first hour: an hour before it is not on the chart, the first hour is. */
    @Test
    void merge_startsAtTheRangeStart() {
        List<RateRow> rows = List.of(rate(-1, 400, 4), rate(0, 200, 2));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(0), rows);

        assertEquals(List.of(ratePoint(0, 1, 200, 2, true)), points);
    }

    /** An hour after the current one is not walked. */
    @Test
    void merge_stopsAtTheCurrentHour() {
        List<RateRow> rows = List.of(rate(0, 5, 0), rate(3, 5, 0));

        List<ChartPoint> points = ChartSeries.ratePoints(FROM, at(2).plusSeconds(3599), rows);

        assertEquals(List.of(ratePoint(0, 1, 5, 0, true)), points);
    }

    // ---- range points -------------------------------------------------------------------------------

    /** 1000 ms lands in bin floor(ln 1000 / ln 1.05) = 141; one sample reads half and 95% of the way through it. */
    @Test
    void rangePoint_singleSampleInterpolatesInsideItsBin() {
        List<ChartPoint> points = ChartSeries.rangePoints(FROM, at(0), GRID, List.of(new RangeRow(at(0), 141, 1)));

        ChartPoint p = points.getFirst();
        assertEquals(1L, p.n());
        assertEquals(Math.pow(1.05, 141.5), Objects.requireNonNull(p.p50()), 1e-9);
        assertEquals(Math.pow(1.05, 141.95), Objects.requireNonNull(p.p95()), 1e-9);
        assertTrue(p.open());
    }

    /** Every sample in one bin: both percentiles sit inside that bin, the p95 above the p50. */
    @Test
    void rangePoint_allSamplesInOneBin() {
        SortedMap<Integer, Long> bins = new TreeMap<>();
        bins.put(100, 40L);

        assertEquals(Math.pow(1.05, 100.5), Objects.requireNonNull(ChartSeries.quantile(GRID, bins, 0.5)), 1e-9);
        assertEquals(Math.pow(1.05, 100.95), Objects.requireNonNull(ChartSeries.quantile(GRID, bins, 0.95)), 1e-9);
    }

    /** A zero or negative value is clamped into the first bin, so it reads as the grid's floor, never NaN. */
    @Test
    void rangePoint_valuesAtOrBelowZeroReadInTheFirstBin() {
        SortedMap<Integer, Long> bins = new TreeMap<>();
        bins.put(0, 3L);

        assertEquals(Math.pow(1.05, 0.5), Objects.requireNonNull(ChartSeries.quantile(GRID, bins, 0.5)), 1e-12);
    }

    /** A value past the grid is clamped into the last bin, so it reads at the grid's ceiling. */
    @Test
    void rangePoint_overflowReadsInTheLastBin() {
        SortedMap<Integer, Long> bins = new TreeMap<>();
        bins.put(10, 1L);
        bins.put(GRID.bins() - 1, 19L);

        assertEquals(
                Math.pow(1.05, (GRID.bins() - 1) + (0.95 * 20 - 1) / 19),
                Objects.requireNonNull(ChartSeries.quantile(GRID, bins, 0.95)),
                1e-6);
    }

    @Test
    void quantile_isNullWithNoSample() {
        assertNull(ChartSeries.quantile(GRID, new TreeMap<>(), 0.5));
    }

    /** Read off the grid, a percentile is within one bin (5%) of the exact one. */
    @Test
    void rangePoint_percentilesAreWithinTheBinRatioOfExact() {
        Random random = new Random(42);
        int n = 2_000;
        double[] values = new double[n];
        List<RangeRow> rows = new ArrayList<>();
        SortedMap<Integer, Long> bins = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            values[i] = Math.exp(7 + random.nextGaussian());
            int bin = (int) Math.floor((Math.log(values[i]) - GRID.logLo()) / GRID.slotWidthLog());
            bins.merge(bin, 1L, Long::sum);
        }
        bins.forEach((bin, count) -> rows.add(new RangeRow(at(0), bin, count)));
        Arrays.sort(values);

        ChartPoint p = ChartSeries.rangePoints(FROM, at(0), GRID, rows).getFirst();

        assertEquals(n, Objects.requireNonNull(p.n()));
        double exactP50 = values[(int) Math.ceil(0.5 * n) - 1];
        double exactP95 = values[(int) Math.ceil(0.95 * n) - 1];
        double p50 = Objects.requireNonNull(p.p50());
        double p95 = Objects.requireNonNull(p.p95());
        assertTrue(p50 / exactP50 < 1.05 && exactP50 / p50 < 1.05, p50 + " vs " + exactP50);
        assertTrue(p95 / exactP95 < 1.05 && exactP95 / p95 < 1.05, p95 + " vs " + exactP95);
    }

    /** A range point closes on samples, not on bins: two hours of 300 samples close at 600. */
    @Test
    void rangePoint_mergesBinsAcrossHours() {
        List<RangeRow> rows =
                List.of(new RangeRow(at(0), 100, 200), new RangeRow(at(0), 120, 100), new RangeRow(at(1), 100, 300));

        List<ChartPoint> points = ChartSeries.rangePoints(FROM, at(3), GRID, rows);

        assertEquals(1, points.size());
        ChartPoint p = points.getFirst();
        assertEquals(at(0).toString(), p.startAt());
        assertEquals(at(2).toString(), p.endAt());
        assertEquals(600L, p.n());
        assertFalse(p.open(), "600 samples reached the target");
        // 500 of 600 in bin 100: the p50 (rank 300) is 300/500 through it, the p95 (rank 570) is 70/100 through 120.
        assertEquals(Math.pow(1.05, 100.6), Objects.requireNonNull(p.p50()), 1e-9);
        assertEquals(Math.pow(1.05, 120.7), Objects.requireNonNull(p.p95()), 1e-9);
    }

    // ---- headlines ----------------------------------------------------------------------------------

    @Test
    void rateHeadline_poolsTheLastWeekNotMeanOfRates() {
        Instant headFrom = Instant.parse("2026-10-02T00:00:00Z");
        List<RateRow> rows = List.of(
                new RateRow(Instant.parse("2026-10-01T23:00:00Z"), 1000, 1000), // before the headline's week
                new RateRow(Instant.parse("2026-10-02T00:00:00Z"), 10, 1),
                new RateRow(Instant.parse("2026-10-03T05:00:00Z"), 100, 50));

        HeadlineView headline = ChartSeries.rateHeadline(rows, headFrom, ChartBaseline.rate(1000, 99, 0.1, false));

        double pooled = 51.0 / 110.0; // the mean of the two rates would be 0.30
        assertEquals(pooled, Objects.requireNonNull(headline.value()), 1e-12);
        assertEquals(pooled - 0.1, Objects.requireNonNull(headline.delta()), 1e-12);
    }

    @Test
    void rateHeadline_isNullWhenNothingChecked() {
        assertEquals(
                new HeadlineView(null, null),
                ChartSeries.rateHeadline(List.of(), FROM, ChartBaseline.rate(1000, 99, 0.1, false)),
                "no NaN on the wire");
    }

    /**
     * The bar can count more than the call site holds (a user classifier counts the whole project) or less (Secret
     * Leak counts its busiest pattern in the band). The headline counts the call site's own detections.
     */
    @Test
    void countHeadline_sumsEveryDetectionOfTheLastWeekNotWhatTheBarCounts() {
        Instant headFrom = Instant.parse("2026-10-02T00:00:00Z");
        List<CountRow> own = List.of(
                new CountRow(Instant.parse("2026-10-01T18:00:00Z"), 9, 9, 9),
                new CountRow(Instant.parse("2026-10-05T06:00:00Z"), 6, 2, 2),
                new CountRow(Instant.parse("2026-10-08T12:00:00Z"), 1, 1, 3));

        assertEquals(new HeadlineView(5.0, null), ChartSeries.countHeadline(own, headFrom));
    }

    // ---- count points -------------------------------------------------------------------------------

    @Test
    void countWidth_isAnHourOnSevenDaysAndSixOnTwentyEight() {
        assertEquals(Duration.ofHours(1), ChartSeries.countWidth(7));
        assertEquals(Duration.ofHours(6), ChartSeries.countWidth(28));
    }

    /** Every bucket of the range is a point, zeros included, and the one holding now is still filling. */
    @Test
    void countPoints_emitEveryBucketAndMarkTheCurrentOneOpen() {
        Instant from = Instant.parse("2026-09-11T00:00:00Z");
        Instant now = Instant.parse("2026-10-08T12:30:00Z");
        List<CountRow> own = List.of(new CountRow(Instant.parse("2026-10-08T12:00:00Z"), 2, 2, 2));

        List<ChartPoint> points = ChartSeries.countPoints(from, now, Duration.ofHours(6), own, null, List.of(), null);

        assertEquals(27 * 4 + 3, points.size(), "Sep 11 to Oct 7 at four a day, then Oct 8 00, 06 and 12");
        assertEquals(
                ChartPoint.count(from, Instant.parse("2026-09-11T06:00:00Z"), false, 0, 0, null), points.getFirst());
        assertEquals(
                ChartPoint.count(
                        Instant.parse("2026-10-08T12:00:00Z"), Instant.parse("2026-10-08T18:00:00Z"), true, 2, 2, null),
                points.getLast(),
                "no bar: every detection counts and nothing is reached");
    }

    /** The bar's count, not the call site's, and reached when the bar's window held the threshold. */
    @Test
    void countPoints_countWhatTheBarCountsAndMarkTheReachedWindow() {
        ClassifierRow bySession = ClassifierRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .discovery()
                .config("{\"arming\":{\"basis\":\"distinct_users\",\"threshold\":3}}")
                .build();
        ClassifierArming.Config arming = ClassifierArming.parse(MAPPER, bySession.configJson());
        Instant from = Instant.parse("2026-10-05T00:00:00Z");
        Instant now = Instant.parse("2026-10-06T23:10:00Z");
        Instant oct5 = Instant.parse("2026-10-05T10:00:00Z");
        Instant oct6 = Instant.parse("2026-10-06T10:00:00Z");
        List<CountRow> own = List.of(new CountRow(oct5, 1, 1, 1), new CountRow(oct6, 4, 2, 5));
        List<CountRow> bar = List.of(new CountRow(oct5, 4, 2, 0), new CountRow(oct6, 4, 2, 0));
        // Two sessions on Oct 5 and three on Oct 6 over the day: hourly session counts do not add up to these.
        List<CountRow> windows = List.of(
                new CountRow(Instant.parse("2026-10-05T00:00:00Z"), 5, 2, 0),
                new CountRow(Instant.parse("2026-10-06T00:00:00Z"), 9, 3, 0));

        List<ChartPoint> points = ChartSeries.countPoints(from, now, Duration.ofHours(1), own, bar, windows, arming);

        assertEquals(new ArmingView(3, 86_400, "distinct_users", "any"), ChartSeries.armingView(arming, bySession));
        assertEquals(48, points.size());
        assertEquals(
                ChartPoint.count(oct5, oct5.plusSeconds(3600), false, 2, 1, false), points.get(10), "sessions, not 4");
        assertEquals(ChartPoint.count(oct6, oct6.plusSeconds(3600), false, 2, 5, true), points.get(34));
        Instant midnight = Instant.parse("2026-10-06T00:00:00Z");
        assertEquals(
                ChartPoint.count(midnight, midnight.plusSeconds(3600), false, 0, 0, true),
                points.get(24),
                "every point of a reached window, even an empty one");
        assertTrue(points.getLast().open());
    }

    /** A window that starts before the range still decides whether the range's first points reached. */
    @Test
    void countPoints_readAWindowThatStartedBeforeTheRange() {
        ClassifierArming.Config arming = new ClassifierArming.Config("event_count", 2, 172_800, null);
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        // Oct 1 is epoch day 20727, odd, so the two-day window holding it started on Sep 30.
        List<CountRow> windows = List.of(new CountRow(Instant.parse("2026-09-30T00:00:00Z"), 2, 2, 0));

        List<ChartPoint> points = ChartSeries.countPoints(
                from, from.plusSeconds(60), Duration.ofHours(1), List.of(), List.of(), windows, arming);

        assertEquals(List.of(ChartPoint.count(from, from.plusSeconds(3600), true, 0, 0, true)), points);
    }

    // ---- baselines and availability -----------------------------------------------------------------

    @Test
    void learning_isMinimumNotFreeze() {
        ClassifierRow frustration =
                ClassifierRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION).build();
        ClassifierRow raised = ClassifierRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION)
                .config("{\"min_baseline_conversations\":150,\"freeze_baseline_conversations\":1000}")
                .build();
        ClassifierRow malformed = ClassifierRowBuilder.of(BuiltInDetector.Kind.MALFORMED_OUTPUT)
                .config("{\"min_baseline_calls\":50,\"freeze_baseline_calls\":800}")
                .build();

        assertEquals(100, ChartSeries.minBaseline(frustration, MAPPER), "the default minimum, not the freeze");
        assertEquals(150, ChartSeries.minBaseline(raised, MAPPER));
        assertEquals(50, ChartSeries.minBaseline(malformed, MAPPER));
    }

    @Test
    void rateBaseline_staysLearningUntilTheReferenceHoldsTheMinimum() {
        assertNull(ChartSeries.rateBaseline(state(120, 6), null, 150), "the detector is silent below its minimum");
        assertNull(ChartSeries.rateBaseline(null, null, 150), "never swept");
        assertEquals(
                ChartBaseline.rate(150, 6, 6.5 / 151.0, false), ChartSeries.rateBaseline(state(150, 6), null, 150));
    }

    @Test
    void toolErrorBaseline_prefersPinnedReference() {
        AcceptedReference reference = new AcceptedReference("tool:search", 2000, 20, "2026-10-01T00:00:00Z");

        assertEquals(
                ChartBaseline.rate(2000, 20, 20.5 / 2001.0, true),
                ChartSeries.rateBaseline(state(1000, 30), reference, 500));
    }

    /** Frustration has no pick list of its own any more: no call-site list means every call site. */
    @Test
    void frustrationWithNoCallSiteList_isOnForEveryCallSite() {
        ClassifierRow frustration =
                ClassifierRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION).build();

        assertEquals(Availability.ON_NOW, ChartSeries.forCallSite(frustration, "cs-a", NO_FACTS));
        assertEquals(Availability.ON_NOW, ChartSeries.forCallSite(frustration, "cs-b", NO_FACTS));
    }

    @Test
    void frustrationLimitedElsewhere_isOffChip() {
        ClassifierRow frustration = ClassifierRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION)
                .onCallSites("cs-b")
                .build();

        assertEquals(Availability.OFF_NOW, ChartSeries.forCallSite(frustration, "cs-a", NO_FACTS));
        assertEquals(Availability.ON_NOW, ChartSeries.forCallSite(frustration, "cs-b", NO_FACTS));
    }

    @Test
    void malformedWithoutSchema_isWaitingChip() {
        ClassifierRow malformed =
                ClassifierRowBuilder.of(BuiltInDetector.Kind.MALFORMED_OUTPUT).build();
        Facts otherHasSchema = new Facts(null, Set.of("cs-b"), Set.of());

        assertEquals(
                Availability.waiting(ChartSeries.NO_SCHEMA),
                ChartSeries.forCallSite(malformed, "cs-a", otherHasSchema));
        assertEquals(Availability.ON_NOW, ChartSeries.forCallSite(malformed, "cs-b", otherHasSchema));
    }

    @Test
    void driftCallSiteCard_needsItsMeasureInTheConfig() {
        ClassifierRow duration =
                ClassifierRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT).build();
        Facts toolOnly = new Facts(null, Set.of(), Set.of(Measure.TOOL_DURATION));

        assertEquals(Availability.OFF_NOW, ChartSeries.forCallSite(duration, "cs-a", toolOnly));
        assertEquals(Availability.ON_NOW, ChartSeries.forTool(duration, toolOnly.measures()));
    }

    @Test
    void toolScope_toolErrorOff_isOffChip() {
        ClassifierRow off = ClassifierRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .disabled()
                .build();
        ClassifierRow on =
                ClassifierRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR).build();

        assertEquals(Availability.OFF_NOW, ChartSeries.forTool(off, Set.of()));
        assertEquals(Availability.ON_NOW, ChartSeries.forTool(on, Set.of()));
        assertEquals(Availability.OFF_NOW, ChartSeries.forCallSite(on, "cs-a", NO_FACTS), "no call-site card");
    }

    @Test
    void countWithoutArming_hasNullArming() {
        ClassifierRow legacy = ClassifierRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .config("{\"pattern\":\"refund\"}")
                .build();
        ClassifierArming.Config arming = ClassifierArming.parse(MAPPER, legacy.configJson());

        assertNull(arming);
        assertNull(ChartSeries.armingView(arming, legacy));
    }

    private static CarriedState state(long calls, long failures) {
        ToolErrorRate baseline = new ToolErrorRate();
        baseline.addCounts(calls, failures);
        return new CarriedState("cs-a", State.EMPTY, baseline, null, "epoch", null, null, null);
    }
}
