// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierRowBuilder;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ChartSeries.Availability;
import ai.tessary.classifier.chart.ChartSeries.Facts;
import ai.tessary.classifier.chart.ClassifierChartDtos.ArmingView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartBaseline;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartDay;
import ai.tessary.classifier.chart.ClassifierChartDtos.HeadlineView;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.worker.ClassifierArming;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure rules behind a chart card. */
class ChartSeriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.parse("2026-10-08");
    private static final Facts NO_FACTS = new Facts(null, Set.of(), Set.of());

    @Test
    void denseDays_fillsEveryDayAndEndsOnToday() {
        LocalDate from = ChartSeries.fromDay(TODAY, 28);
        Map<LocalDate, ChartDay> rows = Map.of(
                LocalDate.parse("2026-09-11"),
                ChartDay.rate("2026-09-11", 5, 1),
                TODAY,
                ChartDay.rate("2026-10-08", 3, 0));

        List<ChartDay> days = ChartSeries.dense(from, TODAY, rows, ChartSeries::emptyRate);

        List<ChartDay> expected = new ArrayList<>();
        expected.add(ChartDay.rate("2026-09-11", 5, 1));
        for (LocalDate d = LocalDate.parse("2026-09-12"); d.isBefore(TODAY); d = d.plusDays(1)) {
            expected.add(ChartDay.rate(d.toString(), 0, 0));
        }
        expected.add(ChartDay.rate("2026-10-08", 3, 0));
        assertEquals(28, expected.size(), "the hand-built axis: Sep 11 to Oct 8");
        assertEquals(expected, days, "every day of the range, zeros where nothing ran, today last");
    }

    @Test
    void rateHeadline_poolsSevenDaysNotMeanOfDailyRates() {
        List<ChartDay> days = new ArrayList<>();
        days.add(ChartDay.rate("2026-10-01", 1000, 1000)); // eight days back: outside the headline
        days.add(ChartDay.rate("2026-10-02", 10, 1));
        days.add(ChartDay.rate("2026-10-03", 100, 50));
        for (int d = 4; d <= 8; d++) days.add(ChartDay.rate("2026-10-0" + d, 0, 0));

        HeadlineView headline = ChartSeries.rateHeadline(days, ChartBaseline.rate(1000, 99, 0.1, false));

        double pooled = 51.0 / 110.0; // the mean of the two daily rates would be 0.30
        assertEquals(pooled, Objects.requireNonNull(headline.value()), 1e-12);
        assertEquals(pooled - 0.1, Objects.requireNonNull(headline.delta()), 1e-12);
    }

    @Test
    void rateHeadline_isNullWhenNothingChecked() {
        List<ChartDay> days = new ArrayList<>();
        for (int d = 2; d <= 8; d++) days.add(ChartDay.rate("2026-10-0" + d, 0, 0));

        assertEquals(
                new HeadlineView(null, null),
                ChartSeries.rateHeadline(days, ChartBaseline.rate(1000, 99, 0.1, false)),
                "no NaN on the wire");
    }

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
    void countWithoutArming_hasNullArmingAndCountsTotal() {
        ClassifierRow legacy = ClassifierRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .config("{\"pattern\":\"refund\"}")
                .build();
        ClassifierArming.Config arming = ClassifierArming.parse(MAPPER, legacy.configJson());

        assertNull(arming);
        assertNull(ChartSeries.armingView(arming, legacy));
        assertEquals(ChartDay.count("2026-10-08", 3, 3), ChartSeries.countDay("2026-10-08", 1, 1, 3, arming));
    }

    @Test
    void countDay_countsWhatTheBarCounts() {
        ClassifierRow bySession = ClassifierRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .discovery()
                .config("{\"arming\":{\"basis\":\"distinct_users\",\"threshold\":0}}")
                .build();
        ClassifierArming.Config arming = ClassifierArming.parse(MAPPER, bySession.configJson());

        assertEquals(new ArmingView(1, 86_400, "distinct_users", "any"), ChartSeries.armingView(arming, bySession));
        assertEquals(ChartDay.count("2026-10-08", 2, 5), ChartSeries.countDay("2026-10-08", 4, 2, 5, arming));
    }

    /**
     * The bar can count more than the call site holds (a user classifier counts the whole project) or less (Secret
     * Leak counts its busiest pattern in the band). The headline counts the call site's own detections.
     */
    @Test
    void countHeadline_sumsEveryDetectionOfTheLastWeekNotWhatTheBarCounts() {
        LocalDate from = ChartSeries.fromDay(TODAY, 28);
        Map<LocalDate, ChartDay> rows = Map.of(
                TODAY.minusDays(7),
                ChartDay.count(TODAY.minusDays(7).toString(), 9, 9),
                TODAY.minusDays(3),
                ChartDay.count(TODAY.minusDays(3).toString(), 6, 2),
                TODAY,
                ChartDay.count(TODAY.toString(), 1, 3));
        List<ChartDay> days = ChartSeries.dense(from, TODAY, rows, ChartSeries::emptyCount);

        assertEquals(new HeadlineView(5.0, null), ChartSeries.countHeadline(days));
    }

    private static CarriedState state(long calls, long failures) {
        ToolErrorRate baseline = new ToolErrorRate();
        baseline.addCounts(calls, failures);
        return new CarriedState("cs-a", State.EMPTY, baseline, null, "epoch", null, null, null);
    }
}
