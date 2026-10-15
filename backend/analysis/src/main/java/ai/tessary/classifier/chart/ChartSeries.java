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

final class ChartSeries {

    private ChartSeries() {}

    static final Set<Integer> RANGES = Set.of(7, 28);

    static final int HEADLINE_DAYS = 7;

    /** Both mirror the metric-drift window (500 samples or one day). */
    static final long POINT_TARGET = 500;

    static final Duration POINT_MAX = Duration.ofHours(24);

    private static final Duration HOUR = Duration.ofHours(1);

    static LocalDate fromDay(LocalDate today, int days) {
        return today.minusDays(days - 1L);
    }

    static Duration countWidth(int days) {
        return days == 7 ? HOUR : Duration.ofHours(6);
    }

    record Window<T>(Instant start, Instant end, T tally, boolean open) {}

    /**
     * An hour with no items neither starts a point nor moves its end. A point is open while it holds the current hour
     * or the current hour could still join it.
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

    /** @param bar the busiest facet or the whole project; ignored without a bar */
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

    private static boolean reached(
            Instant start, Instant end, ClassifierArming.Config arming, Map<Instant, Long> perWindow) {
        long win = arming.windowSeconds();
        for (long w = Math.floorDiv(start.getEpochSecond(), win) * win; w < end.getEpochSecond(); w += win) {
            if (perWindow.getOrDefault(Instant.ofEpochSecond(w), 0L) >= arming.threshold()) return true;
        }
        return false;
    }

    static Instant windowStart(Instant from, ClassifierArming.Config arming) {
        long win = arming.windowSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(from.getEpochSecond(), win) * win);
    }

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

    static HeadlineView rangeHeadline(@Nullable Double pooledP95, @Nullable ChartBaseline baseline) {
        if (pooledP95 == null) return new HeadlineView(null, null);
        Double p95 = baseline == null ? null : baseline.p95();
        return new HeadlineView(pooledP95, p95 == null ? null : pooledP95 - p95);
    }

    /** The sum of {@code total}, not of what the bar counts. A count has no baseline, so no delta. */
    static HeadlineView countHeadline(List<CountRow> own, Instant headFrom) {
        long count = 0;
        for (CountRow r : own) {
            if (!r.start().isBefore(headFrom)) count += r.total();
        }
        return new HeadlineView((double) count, null);
    }

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

    /** The detector's own grid, so the chart bins as the detector does. */
    static Grid grid(MetricDriftConfig config, String measure) {
        return config.measured().stream()
                .filter(m -> measure.equals(m.measure()))
                .findFirst()
                .map(MetricDriftConfig.Measured::grid)
                .orElse(Measure.COST.equals(measure) ? Grid.cost() : Grid.duration());
    }

    /**
     * A person-accepted Tool Errors reference wins. A learned one counts only once it holds {@code minBaseline},
     * matching the detector's silence.
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

    static long minBaseline(ClassifierRow row, ObjectMapper mapper) {
        return switch (row.detector()) {
            case BuiltInDetector.Kind.FRUSTRATION ->
                FrustrationConfig.of(mapper, row.configJson()).engine().minBaselineCalls();
            case BuiltInDetector.Kind.GROUNDEDNESS ->
                GroundednessConfig.of(mapper, row.configJson()).engine().minBaselineCalls();
            default -> ToolErrorConfig.of(mapper, row.configJson()).minBaselineCalls();
        };
    }

    static @Nullable ArmingView armingView(ClassifierArming.@Nullable Config arming, ClassifierRow row) {
        if (arming == null) return null;
        return new ArmingView(
                arming.threshold(), arming.windowSeconds(), arming.basis(), arming.highOnly(row) ? "high" : "any");
    }

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

    static final String NO_SCHEMA = "no_schema";

    record Facts(@Nullable String waitingReason, Set<String> schemaCallSites, Set<String> measures) {}

    /** A drift classifier is on only for the measure its call-site card shows. */
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

    static Availability forTool(ClassifierRow row, Set<String> measures) {
        if (!row.enabled()) return Availability.OFF_NOW;
        if (BuiltInDetector.Kind.TOOL_ERROR.equals(row.detector())) return Availability.ON_NOW;
        return measures.contains(Measure.TOOL_DURATION) ? Availability.ON_NOW : Availability.OFF_NOW;
    }

    private static long nz(@Nullable Long v) {
        return v == null ? 0 : v;
    }
}
