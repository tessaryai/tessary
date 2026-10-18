// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.MetricsQueryValidator;
import ai.tessary.metrics.dashboard.MetricDashboardDefinition.MetricWidgetDefinition;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether a dashboard definition is storable. Rules run per widget in a fixed order and the first failure
 * throws INVALID_WIDGET with the widget id, so the user can find the widget. Each widget also passes the query
 * validator over a one-week dummy window, so a stored widget is always a query the server will answer.
 */
public final class MetricDashboardValidator {

    public static final int COLUMNS = 12;
    public static final int MIN_W = 3;
    public static final int MIN_H = 2;
    public static final int MAX_H = 8;
    public static final int MAX_TITLE = 80;
    static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");
    static final String DUMMY_FROM = "2026-01-01T00:00:00Z";
    static final String DUMMY_TO = "2026-01-08T00:00:00Z";

    private MetricDashboardValidator() {}

    public static void validate(MetricDashboardDefinition def) {
        if (def.widgets().size() > MetricsQueryValidator.MAX_WIDGETS) {
            throw new TessaryException(MetricsError.TOO_MANY_WIDGETS, MetricsQueryValidator.MAX_WIDGETS);
        }
        List<MetricWidgetDefinition> seen = new ArrayList<>();
        for (MetricWidgetDefinition w : def.widgets()) {
            check(w, seen);
            seen.add(w);
        }
    }

    public static MetricsQueryRequest toRequest(MetricWidgetDefinition w) {
        return new MetricsQueryRequest(
                w.view(),
                w.measures(),
                w.dimension(),
                w.filters(),
                DUMMY_FROM,
                DUMMY_TO,
                "timeseries".equals(w.chart()) ? "auto" : null,
                w.topN(),
                w.compare());
    }

    private static void check(MetricWidgetDefinition w, List<MetricWidgetDefinition> seen) {
        if (!ID.matcher(w.id()).matches()) refuse(w, "id must be 1 to 40 lowercase letters, digits or dashes");
        for (MetricWidgetDefinition other : seen) {
            if (other.id().equals(w.id())) refuse(w, "id is used twice");
        }
        String title = w.title();
        if (title != null && title.length() > MAX_TITLE) refuse(w, "title is longer than 80 characters");
        if (w.x() < 0 || w.y() < 0 || w.w() < MIN_W || w.x() + w.w() > COLUMNS || w.h() < MIN_H || w.h() > MAX_H) {
            refuse(w, "placement is outside the 12-column grid");
        }
        for (MetricWidgetDefinition other : seen) {
            if (overlap(w, other)) refuse(w, "overlaps widget '" + other.id() + "'");
        }
        checkChart(w);
        try {
            MetricsQueryValidator.resolve(toRequest(w));
        } catch (TessaryException e) {
            throw new TessaryException(MetricsError.INVALID_WIDGET, e, w.id(), e.getMessage());
        }
    }

    private static void checkChart(MetricWidgetDefinition w) {
        int measures = w.measures().size();
        boolean dimension = w.dimension() != null;
        switch (w.chart()) {
            case "number" -> {
                if (measures < 1 || measures > 2 || dimension || w.topN() != null) {
                    refuse(w, "a number has one or two measures and no dimension");
                }
            }
            case "timeseries" -> {
                if (measures != 1
                        || !topNWithin(w.topN(), MetricsQueryValidator.MAX_TOP_N_WITH_AXIS)
                        || w.compare() != null) {
                    refuse(w, "a time series has one measure, top_n up to 5 and no compare");
                }
            }
            case "table" -> {
                if (measures < 1
                        || measures > MetricsQueryValidator.MAX_MEASURES
                        || !dimension
                        || !topNWithin(w.topN(), MetricsQueryValidator.MAX_TOP_N)
                        || w.compare() != null) {
                    refuse(w, "a table has 1 to 4 measures, a dimension and top_n up to 100");
                }
            }
            default -> refuse(w, "chart must be number, timeseries or table");
        }
    }

    private static boolean topNWithin(@Nullable Integer topN, int max) {
        return topN == null || (topN >= 1 && topN <= max);
    }

    private static boolean overlap(MetricWidgetDefinition a, MetricWidgetDefinition b) {
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    private static void refuse(MetricWidgetDefinition w, String reason) {
        throw new TessaryException(MetricsError.INVALID_WIDGET, w.id(), reason);
    }
}
