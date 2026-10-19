// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import java.util.List;
import java.util.Map;

/** One planned read: its SQL, the values bound to it, and which measures each output column carries. */
public record MetricsStatement(Shape shape, String sql, Map<String, Object> params, List<Column> columns) {

    public enum Shape {
        TOTAL,
        SERIES,
        GROUPED,
        GROUPED_SERIES;

        public boolean hasAxis() {
            return this == SERIES || this == GROUPED_SERIES;
        }

        public boolean hasDimension() {
            return this == GROUPED || this == GROUPED_SERIES;
        }
    }

    /** One SQL column; {@code measures} has one entry for a scalar and up to three for the quantile array. */
    public record Column(String alias, List<MetricsMeasure> measures) {
        public boolean array() {
            return measures.get(0).quantile();
        }
    }
}
