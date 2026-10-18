// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public record MetricsMeasure(
        String wire,
        String label,
        /** The SQL column alias. Measures that share an alias share one SQL expression (the latency family). */
        String alias,
        String sql,
        /** Index into the float8[] the expression yields; null for a scalar. */
        @Nullable Integer arrayIndex,
        Unit unit,
        GapFill gapFill,
        boolean needsRoot,
        boolean needsTrace,
        /** Wire names of measures of the same view that the server appends to every answer naming this one. */
        List<String> companions) {

    public enum Unit {
        COUNT,
        USD,
        MS,
        TOKENS;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a missing bucket shows: 0 for counts and sums, null for percentiles and ratios. */
    public enum GapFill {
        ZERO,
        NONE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public boolean quantile() {
        return arrayIndex != null;
    }

    /** The expression that ranks groups by this measure; Postgres cannot subscript an output alias in ORDER BY. */
    public String rankSql() {
        // A local, so NullAway sees the null check; quantile() is opaque to it.
        Integer i = arrayIndex;
        return i == null ? sql : "(" + sql + ")[" + (i + 1) + "]";
    }
}
