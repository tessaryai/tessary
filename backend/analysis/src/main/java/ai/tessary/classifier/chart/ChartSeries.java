// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ClassifierChartDtos.ArmingView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartBaseline;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartDay;
import ai.tessary.classifier.chart.ClassifierChartDtos.HeadlineView;
import ai.tessary.classifier.detector.groundedness.GroundednessConfig;
import ai.tessary.classifier.frustration.FrustrationConfig;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.worker.ClassifierArming;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The pure rules behind a chart card: the dense day axis, the headline, the baseline and whether a classifier is on. */
final class ChartSeries {

    private ChartSeries() {}

    /** The ranges the page offers. */
    static final Set<Integer> RANGES = Set.of(7, 28, 90);

    /** The headline always covers the last seven UTC days, whatever the range. */
    static final int HEADLINE_DAYS = 7;

    /** The first day of a {@code days}-long range ending {@code today}. */
    static LocalDate fromDay(LocalDate today, int days) {
        return today.minusDays(days - 1L);
    }

    /** One entry per day from {@code from} to {@code today}, oldest first; a day with no row gets {@code empty}. */
    static List<ChartDay> dense(
            LocalDate from, LocalDate today, Map<LocalDate, ChartDay> rows, Function<String, ChartDay> empty) {
        List<ChartDay> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            ChartDay row = rows.get(d);
            out.add(row != null ? row : empty.apply(d.toString()));
        }
        return out;
    }

    static ChartDay emptyRate(String date) {
        return ChartDay.rate(date, 0, 0);
    }

    static ChartDay emptyRange(String date) {
        return ChartDay.range(date, 0, null, null);
    }

    static ChartDay emptyCount(String date) {
        return ChartDay.count(date, 0, 0);
    }

    private static List<ChartDay> lastWeek(List<ChartDay> days) {
        return days.subList(Math.max(0, days.size() - HEADLINE_DAYS), days.size());
    }

    /** Flagged over checked, pooled over the last seven days; null when nothing was checked. */
    static HeadlineView rateHeadline(List<ChartDay> days, @Nullable ChartBaseline baseline) {
        long checked = 0;
        long flagged = 0;
        for (ChartDay d : lastWeek(days)) {
            checked += nz(d.checked());
            flagged += nz(d.flagged());
        }
        if (checked == 0) return new HeadlineView(null, null);
        double value = (double) flagged / checked;
        Double rate = baseline == null ? null : baseline.rate();
        return new HeadlineView(value, rate == null ? null : value - rate);
    }

    /** The pooled p95 of the last seven days, against the baseline's p95. */
    static HeadlineView rangeHeadline(@Nullable Double pooledP95, @Nullable ChartBaseline baseline) {
        if (pooledP95 == null) return new HeadlineView(null, null);
        Double p95 = baseline == null ? null : baseline.p95();
        return new HeadlineView(pooledP95, p95 == null ? null : pooledP95 - p95);
    }

    /**
     * Every detection on the scope over the last seven days: the sum of {@code total}, not of what the bar counts. The
     * bar can count the whole project (a user classifier) or one pattern in its band (Secret Leak). A count has no
     * baseline, so no delta.
     */
    static HeadlineView countHeadline(List<ChartDay> days) {
        long count = 0;
        for (ChartDay d : lastWeek(days)) count += nz(d.total());
        return new HeadlineView((double) count, null);
    }

    /** Whether the range holds anything to draw: a checked trial, a sample or a detection. */
    static boolean hasData(String kind, List<ChartDay> days) {
        for (ChartDay d : days) {
            long n =
                    switch (kind) {
                        case ClassifierChartDtos.ChartCard.RATE -> nz(d.checked());
                        case ClassifierChartDtos.ChartCard.RANGE -> nz(d.n());
                        default -> nz(d.total());
                    };
            if (n > 0) return true;
        }
        return false;
    }

    /**
     * The reference a rate card draws, or null while learning. A Tool Errors reference a person accepted wins over
     * the learned one. A learned one counts only once it holds {@code minBaseline}: below that the detector stays
     * silent, so the card stays learning.
     */
    static @Nullable ChartBaseline rateBaseline(
            @Nullable CarriedState state, @Nullable AcceptedReference reference, long minBaseline) {
        if (reference != null) {
            return ChartBaseline.rate(
                    reference.calls(), reference.failures(), ToolErrorDetector.baselineRate(reference.asRate()), true);
        }
        ToolErrorRate learned = state == null ? null : state.baseline();
        if (learned == null || learned.calls() < minBaseline) return null;
        return ChartBaseline.rate(learned.calls(), learned.failures(), ToolErrorDetector.baselineRate(learned), false);
    }

    /** The minimum a rate classifier's reference must hold before it judges anything: never the freeze. */
    static long minBaseline(ClassifierRow row, ObjectMapper mapper) {
        return switch (row.detector()) {
            case BuiltInDetector.Kind.FRUSTRATION ->
                FrustrationConfig.of(mapper, row.configJson()).engine().minBaselineCalls();
            case BuiltInDetector.Kind.GROUNDEDNESS ->
                GroundednessConfig.of(mapper, row.configJson()).engine().minBaselineCalls();
            default -> ToolErrorConfig.of(mapper, row.configJson()).minBaselineCalls();
        };
    }

    /**
     * One count day as the arming bar sees it. With no bar, every detection counts.
     *
     * @param events detections the bar's band and scope admit that day
     * @param sessions distinct sessions among them
     * @param total every detection on the call site that day
     */
    static ChartDay countDay(
            String date, long events, long sessions, long total, ClassifierArming.@Nullable Config arming) {
        if (arming == null) return ChartDay.count(date, total, total);
        return ChartDay.count(date, arming.bySession() ? sessions : events, total);
    }

    /** The bar as the card shows it, with the band resolved against the row's mode. */
    static @Nullable ArmingView armingView(ClassifierArming.@Nullable Config arming, ClassifierRow row) {
        if (arming == null) return null;
        return new ArmingView(
                arming.threshold(), arming.windowSeconds(), arming.basis(), arming.highOnly(row) ? "high" : "any");
    }

    /** {@code on}, {@code off} or {@code waiting}, and why it waits. */
    record Availability(String state, @Nullable String reason) {

        static final String ON = "on";
        static final String OFF = "off";
        static final String WAITING = "waiting";

        static final Availability ON_NOW = new Availability(ON, null);
        static final Availability OFF_NOW = new Availability(OFF, null);

        static Availability waiting(String reason) {
            return new Availability(WAITING, reason);
        }

        boolean on() {
            return !OFF.equals(state);
        }
    }

    /** The waiting reason when Malformed Output's call site declares no output schema. */
    static final String NO_SCHEMA = "no_schema";

    /**
     * What a call-site rule needs to know about one classifier beyond its row.
     *
     * @param frustrationScope the call sites Frustration scores (its own pick list)
     * @param waitingReason why the row as a whole cannot judge: a pause, or Groundedness not scoring; null when it can
     * @param schemaCallSites the call sites that declare an output schema
     * @param measures the drift measures the row's config is live for
     */
    record Facts(
            Set<String> frustrationScope,
            @Nullable String waitingReason,
            Set<String> schemaCallSites,
            Set<String> measures) {}

    /**
     * Whether {@code row} runs on {@code callSiteId}, and if so whether it is waiting. Frustration reads its own pick
     * list, never the row's call sites. A drift classifier is on only for the measure its call-site card shows.
     */
    static Availability forCallSite(ClassifierRow row, String callSiteId, Facts facts) {
        if (!row.enabled()) return Availability.OFF_NOW;
        boolean runs =
                switch (row.detector()) {
                    case BuiltInDetector.Kind.FRUSTRATION ->
                        facts.frustrationScope().contains(callSiteId);
                    case BuiltInDetector.Kind.DURATION_DRIFT ->
                        row.runsOn(callSiteId) && facts.measures().contains(Measure.TURN_DURATION);
                    case BuiltInDetector.Kind.COST_DRIFT ->
                        row.runsOn(callSiteId) && facts.measures().contains(Measure.COST);
                    case BuiltInDetector.Kind.TOOL_ERROR -> false;
                    default -> row.runsOn(callSiteId);
                };
        if (!runs) return Availability.OFF_NOW;
        if (BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(row.detector())
                && !facts.schemaCallSites().contains(callSiteId)) {
            return Availability.waiting(NO_SCHEMA);
        }
        return facts.waitingReason() == null ? Availability.ON_NOW : Availability.waiting(facts.waitingReason());
    }

    /** Whether {@code row} has a tool card: Tool Errors when enabled, Duration Drift when it measures tools. */
    static Availability forTool(ClassifierRow row, Set<String> measures) {
        if (!row.enabled()) return Availability.OFF_NOW;
        if (BuiltInDetector.Kind.TOOL_ERROR.equals(row.detector())) return Availability.ON_NOW;
        return measures.contains(Measure.TOOL_DURATION) ? Availability.ON_NOW : Availability.OFF_NOW;
    }

    private static long nz(@Nullable Long v) {
        return v == null ? 0 : v;
    }
}
