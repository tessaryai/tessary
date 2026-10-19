// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.metrics.MetricsQueryDtos.MetricsFilter;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a query request against the catalog and its limits. Checks run in a fixed order and the first failure
 * throws, so a request with several faults always names the same one.
 */
public final class MetricsQueryValidator {

    public static final int MAX_WINDOW_DAYS = 90;
    public static final int MAX_HOUR_WINDOW_DAYS = 14;
    public static final int MAX_TOP_N_WITH_AXIS = 5;
    public static final int MAX_TOP_N = 100;
    public static final int DEFAULT_TOP_N_WITH_AXIS = 5;
    public static final int DEFAULT_TOP_N = 20;
    public static final int MAX_MEASURES = 4;
    public static final int MAX_FILTERS = 3;
    public static final int MAX_FILTER_VALUES = 50;
    public static final int MAX_WIDGETS = 12;
    public static final int MAX_DASHBOARDS = 20;

    private MetricsQueryValidator() {}

    public static MetricsQuery resolve(MetricsQueryRequest req) {
        MetricsView view = MetricsView.fromWire(req.view());
        List<MetricsMeasure> asked = measures(view, req.measures());
        String dimensionWire = req.dimension();
        MetricsDimension dimension = dimensionWire == null ? null : view.dimension(dimensionWire);
        List<MetricsQuery.Filter> filters = filters(view, req.filters());

        Instant from;
        Instant to;
        try {
            from = Instant.parse(req.from());
            to = Instant.parse(req.to());
        } catch (DateTimeParseException e) {
            throw new TessaryException(MetricsError.INVALID_RANGE);
        }
        if (!from.isBefore(to)) throw new TessaryException(MetricsError.INVALID_RANGE);
        Duration length = Duration.between(from, to);
        if (length.compareTo(Duration.ofDays(MAX_WINDOW_DAYS)) > 0) {
            throw new TessaryException(MetricsError.WINDOW_TOO_LONG, MAX_WINDOW_DAYS);
        }

        MetricsGranularity unit = MetricsGranularity.fromWire(req.granularity());
        if (unit == MetricsGranularity.AUTO) unit = MetricsGranularity.autoFor(length);
        if (unit == MetricsGranularity.HOUR && length.compareTo(Duration.ofDays(MAX_HOUR_WINDOW_DAYS)) > 0) {
            throw new TessaryException(MetricsError.WINDOW_TOO_FINE, MAX_HOUR_WINDOW_DAYS);
        }
        boolean axis = unit != null;

        int maxTopN = axis ? MAX_TOP_N_WITH_AXIS : MAX_TOP_N;
        Integer askedTopN = req.topN();
        int topN = askedTopN == null ? (axis ? DEFAULT_TOP_N_WITH_AXIS : DEFAULT_TOP_N) : askedTopN;
        if (topN < 1 || topN > maxTopN) throw new TessaryException(MetricsError.TOP_N_TOO_LARGE, maxTopN);

        boolean compare = req.compare() != null;
        if (compare && axis) throw new TessaryException(MetricsError.COMPARE_NEEDS_NO_AXIS);
        if (compare && dimension != null) throw new TessaryException(MetricsError.COMPARE_NEEDS_NO_DIMENSION);

        return new MetricsQuery(
                view, asked, withCompanions(view, asked), dimension, filters, from, to, unit, topN, compare);
    }

    private static List<MetricsMeasure> measures(MetricsView view, List<String> wires) {
        if (wires.isEmpty() || wires.size() > MAX_MEASURES) {
            throw new TessaryException(MetricsError.TOO_MANY_MEASURES, MAX_MEASURES);
        }
        Set<MetricsMeasure> asked = new LinkedHashSet<>();
        for (String wire : wires) asked.add(view.measure(wire));
        return List.copyOf(asked);
    }

    private static List<MetricsQuery.Filter> filters(MetricsView view, @Nullable List<MetricsFilter> requested) {
        if (requested == null) return List.of();
        if (requested.size() > MAX_FILTERS) throw new TessaryException(MetricsError.TOO_MANY_FILTERS, MAX_FILTERS);
        List<MetricsQuery.Filter> out = new ArrayList<>();
        for (MetricsFilter f : requested) {
            MetricsDimension field = view.filterField(f.field());
            boolean negated =
                    switch (f.op()) {
                        case "in" -> false;
                        case "not_in" -> true;
                        default -> throw new TessaryException(MetricsError.BAD_OPERATOR, f.op());
                    };
            if (f.values().isEmpty() || f.values().size() > MAX_FILTER_VALUES) {
                throw new TessaryException(MetricsError.TOO_MANY_VALUES, MAX_FILTER_VALUES);
            }
            out.add(new MetricsQuery.Filter(field, negated, f.values()));
        }
        return List.copyOf(out);
    }

    private static List<MetricsMeasure> withCompanions(MetricsView view, List<MetricsMeasure> asked) {
        Set<MetricsMeasure> all = new LinkedHashSet<>(asked);
        for (MetricsMeasure m : asked) {
            for (String companion : m.companions()) all.add(view.measure(companion));
        }
        return List.copyOf(all);
    }
}
