// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ClassifierChartDtos.ArmingView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartBaseline;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartPoint;
import ai.tessary.classifier.chart.ClassifierChartDtos.HeadlineView;
import ai.tessary.classifier.chart.ClassifierChartRepository.CountRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RangeRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RateRow;
import ai.tessary.classifier.detector.groundedness.GroundednessConfig;
import ai.tessary.classifier.frustration.FrustrationConfig;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.worker.ClassifierArming;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/** The pure rules behind a chart card: its points, the headline, the baseline and whether a classifier is on. */
final class ChartSeries {

    private ChartSeries() {}

    /** The ranges the page offers. */
    static final Set<Integer> RANGES = Set.of(7, 28);

    /** The headline always covers the last seven UTC days, whatever the range. */
    static final int HEADLINE_DAYS = 7;

    /**
     * A rate or range point closes once it holds this many trials or samples, or before it would span more than
     * {@link #POINT_MAX}. Both mirror the metric-drift window (500 samples or one day), so a busy call site draws
     * several points a day and a quiet one about one.
     */
    static final long POINT_TARGET = 500;

    static final Duration POINT_MAX = Duration.ofHours(24);

    private static final Duration HOUR = Duration.ofHours(1);

    /** The first day of a {@code days}-long range ending {@code today}. */
    static LocalDate fromDay(LocalDate today, int days) {
        return today.minusDays(days - 1L);
    }

    /** A count card's fixed bucket: an hour on 7 days, six hours (from 00, 06, 12 and 18 UTC) on 28. */
    static Duration countWidth(int days) {
        return days == 7 ? HOUR : Duration.ofHours(6);
    }

    // ---- the merge rule -----------------------------------------------------------------------------

    /** One merged stretch of hours, {@code [start, end)}, and whether it is still filling. */
    record Window<T>(Instant start, Instant end, T tally, boolean open) {}

    /**
     * Merges the hours from {@code from} to the hour holding {@code now} into points. An hour with no items neither
     * starts a point nor moves its end. An hour that would stretch the open point past {@link #POINT_MAX} closes it
     * first; a point that reaches {@link #POINT_TARGET} items closes after the hour that took it there. A point is
     * open while it can still change: it holds the current hour, or it is the last one and the current hour could
     * still join it.
     */
    static <T> List<Window<T>> merge(
            Instant from, Instant now, Map<Instant, T> hours, ToLongFunction<T> items, BinaryOperator<T> plus) {
        Instant last = now.truncatedTo(ChronoUnit.HOURS);
        List<Window<T>> out = new ArrayList<>();
        Instant start = null;
        Instant end = null;
        T tally = null;
        for (Instant hour = from; !hour.isAfter(last); hour = hour.plus(HOUR)) {
            T t = hours.get(hour);
            if (t == null || items.applyAsLong(t) == 0) continue;
            Instant hourEnd = hour.plus(HOUR);
            if (start != null && Duration.between(start, hourEnd).compareTo(POINT_MAX) > 0) {
                out.add(new Window<>(start, Objects.requireNonNull(end), Objects.requireNonNull(tally), false));
                start = null;
            }
            if (start == null) {
                start = hour;
                tally = t;
            } else {
                tally = plus.apply(Objects.requireNonNull(tally), t);
            }
            end = hourEnd;
            if (items.applyAsLong(tally) >= POINT_TARGET) {
                out.add(new Window<>(start, end, tally, end.isAfter(last)));
                start = null;
            }
        }
        if (start != null) {
            boolean open = Duration.between(start, last.plus(HOUR)).compareTo(POINT_MAX) <= 0;
            out.add(new Window<>(start, Objects.requireNonNull(end), Objects.requireNonNull(tally), open));
        }
        return out;
    }

    private record Trials(long checked, long flagged) {

        Trials plus(Trials o) {
            return new Trials(checked + o.checked, flagged + o.flagged);
        }
    }

    /** A rate card's points from its hourly rows. */
    static List<ChartPoint> ratePoints(Instant from, Instant now, List<RateRow> rows) {
        Map<Instant, Trials> hours = new HashMap<>();
        for (RateRow r : rows) hours.merge(r.hour(), new Trials(r.checked(), r.flagged()), Trials::plus);
        return merge(from, now, hours, Trials::checked, Trials::plus).stream()
                .map(w -> ChartPoint.rate(
                        w.start(),
                        w.end(),
                        w.open(),
                        w.tally().checked(),
                        w.tally().flagged()))
                .toList();
    }

    /** A range card's points from its hourly histogram rows: the samples per bin of {@code grid}. */
    static List<ChartPoint> rangePoints(Instant from, Instant now, Grid grid, List<RangeRow> rows) {
        Map<Instant, SortedMap<Integer, Long>> hours = new HashMap<>();
        for (RangeRow r : rows) {
            hours.computeIfAbsent(r.hour(), h -> new TreeMap<>()).merge(r.bin(), r.n(), Long::sum);
        }
        return merge(from, now, hours, ChartSeries::samples, ChartSeries::plusBins).stream()
                .map(w -> ChartPoint.range(
                        w.start(),
                        w.end(),
                        w.open(),
                        samples(w.tally()),
                        quantile(grid, w.tally(), 0.5),
                        quantile(grid, w.tally(), 0.95)))
                .toList();
    }

    private static long samples(SortedMap<Integer, Long> bins) {
        long n = 0;
        for (long c : bins.values()) n += c;
        return n;
    }

    private static SortedMap<Integer, Long> plusBins(SortedMap<Integer, Long> a, SortedMap<Integer, Long> b) {
        SortedMap<Integer, Long> out = new TreeMap<>(a);
        b.forEach((bin, n) -> out.merge(bin, n, Long::sum));
        return out;
    }

    /**
     * The {@code q} quantile of samples counted per bin of {@code grid}, interpolated inside its bin in log space as
     * {@code MetricHistogram#quantile} does; null with no sample.
     */
    static @Nullable Double quantile(Grid grid, SortedMap<Integer, Long> bins, double q) {
        long n = samples(bins);
        if (n == 0) return null;
        double rank = q * n;
        long below = 0;
        for (Map.Entry<Integer, Long> e : bins.entrySet()) {
            long c = e.getValue();
            if (c == 0) continue;
            if (rank <= below + c) {
                double within = (rank - below) / c;
                return Math.exp(grid.logLo() + (e.getKey() + within) * grid.slotWidthLog());
            }
            below += c;
        }
        return Math.exp(grid.logHi());
    }

    // ---- count points -------------------------------------------------------------------------------

    /**
     * One count point per {@code width} bucket from {@code from} to the bucket holding {@code now}, empty ones
     * included. Without a bar every detection counts and nothing is reached. With one, {@code count} is the bar's
     * count in the bucket, and {@code reached} says whether a window of the bar overlapping it held the threshold.
     *
     * @param own the call site's detections per bucket: the total
     * @param bar what the bar counts per bucket: the busiest facet, or the whole project; ignored without a bar
     * @param windows what the bar counts per window of the bar
     */
    static List<ChartPoint> countPoints(
            Instant from,
            Instant now,
            Duration width,
            List<CountRow> own,
            @Nullable List<CountRow> bar,
            List<CountRow> windows,
            ClassifierArming.@Nullable Config arming) {
        Map<Instant, Long> totals = new HashMap<>();
        for (CountRow r : own) totals.merge(r.start(), r.total(), Long::sum);
        Map<Instant, Long> counted = arming == null || bar == null ? Map.of() : barCounts(bar, arming);
        Map<Instant, Long> perWindow = arming == null ? Map.of() : barCounts(windows, arming);
        List<ChartPoint> out = new ArrayList<>();
        for (Instant start = from; !start.isAfter(now); start = start.plus(width)) {
            Instant end = start.plus(width);
            boolean open = now.isBefore(end);
            long total = totals.getOrDefault(start, 0L);
            if (arming == null) {
                out.add(ChartPoint.count(start, end, open, total, total, null));
            } else {
                out.add(ChartPoint.count(
                        start,
                        end,
                        open,
                        counted.getOrDefault(start, 0L),
                        total,
                        reached(start, end, arming, perWindow)));
            }
        }
        return out;
    }

    private static Map<Instant, Long> barCounts(List<CountRow> rows, ClassifierArming.Config arming) {
        Map<Instant, Long> out = new HashMap<>();
        for (CountRow r : rows) out.merge(r.start(), arming.bySession() ? r.sessions() : r.events(), Long::sum);
        return out;
    }

    /** Whether a window of the bar that overlaps {@code [start, end)} reached the threshold. */
    private static boolean reached(
            Instant start, Instant end, ClassifierArming.Config arming, Map<Instant, Long> perWindow) {
        long win = arming.windowSeconds();
        for (long w = Math.floorDiv(start.getEpochSecond(), win) * win; w < end.getEpochSecond(); w += win) {
            if (perWindow.getOrDefault(Instant.ofEpochSecond(w), 0L) >= arming.threshold()) return true;
        }
        return false;
    }

    /** Where a count read must start so the bar's first window is whole: the start of the window holding {@code from}. */
    static Instant windowStart(Instant from, ClassifierArming.Config arming) {
        long win = arming.windowSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(from.getEpochSecond(), win) * win);
    }

    // ---- headlines ----------------------------------------------------------------------------------

    /** Flagged over checked, pooled over the hours since {@code headFrom}; null when nothing was checked. */
    static HeadlineView rateHeadline(List<RateRow> rows, Instant headFrom, @Nullable ChartBaseline baseline) {
        long checked = 0;
        long flagged = 0;
        for (RateRow r : rows) {
            if (r.hour().isBefore(headFrom)) continue;
            checked += r.checked();
            flagged += r.flagged();
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
     * Every detection on the scope since {@code headFrom}: the sum of {@code total}, not of what the bar counts. The
     * bar can count the whole project (a user classifier) or one pattern in its band (Secret Leak). A count has no
     * baseline, so no delta.
     */
    static HeadlineView countHeadline(List<CountRow> own, Instant headFrom) {
        long count = 0;
        for (CountRow r : own) {
            if (!r.start().isBefore(headFrom)) count += r.total();
        }
        return new HeadlineView((double) count, null);
    }

    /** Whether the range holds anything to draw: a checked trial, a sample or a detection. */
    static boolean hasData(String kind, List<ChartPoint> points) {
        for (ChartPoint p : points) {
            long n =
                    switch (kind) {
                        case ClassifierChartDtos.ChartCard.RATE -> nz(p.checked());
                        case ClassifierChartDtos.ChartCard.RANGE -> nz(p.n());
                        default -> nz(p.total());
                    };
            if (n > 0) return true;
        }
        return false;
    }

    /**
     * The grid a range card bins {@code measure} on: the one the row's drift config sketches it on, so the chart
     * bins as the detector does.
     */
    static Grid grid(MetricDriftConfig config, String measure) {
        return config.measured().stream()
                .filter(m -> measure.equals(m.measure()))
                .findFirst()
                .map(MetricDriftConfig.Measured::grid)
                .orElse(Measure.COST.equals(measure) ? Grid.cost() : Grid.duration());
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
     * @param waitingReason why the row as a whole cannot judge: a pause, or Groundedness not scoring; null when it can
     * @param schemaCallSites the call sites that declare an output schema
     * @param measures the drift measures the row's config is live for
     */
    record Facts(@Nullable String waitingReason, Set<String> schemaCallSites, Set<String> measures) {}

    /**
     * Whether {@code row} runs on {@code callSiteId}, and if so whether it is waiting. A drift classifier is on only
     * for the measure its call-site card shows.
     */
    static Availability forCallSite(ClassifierRow row, String callSiteId, Facts facts) {
        if (!row.enabled()) return Availability.OFF_NOW;
        boolean runs =
                switch (row.detector()) {
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
