// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A query resolved against the catalog: every name is a catalog entry and every limit has been checked. */
public record MetricsQuery(
        MetricsView view,
        /** In request order, deduplicated. */
        List<MetricsMeasure> asked,
        /** Asked, then each companion once in encounter order. */
        List<MetricsMeasure> all,
        @Nullable MetricsDimension dimension,
        List<Filter> filters,
        Instant from,
        Instant to,
        /** Null, HOUR, DAY or WEEK; never AUTO. */
        @Nullable MetricsGranularity unit,
        int topN,
        boolean compare) {

    public record Filter(MetricsDimension dimension, boolean negated, List<String> values) {}

    public Duration length() {
        return Duration.between(from, to);
    }

    public boolean hasAxis() {
        return unit != null;
    }

    public boolean hasDimension() {
        return dimension != null;
    }
}
