// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.metrics.MetricsStatement.Column;
import ai.tessary.metrics.MetricsStatement.Shape;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a resolved query into one SQL statement. Every name in the query is already a catalog constant, so the SQL is
 * assembled from constants only; filter values, instants and limits are bound. A dimensioned query ranks its keys in a
 * CTE and folds those past top N into {@code __other__} in SQL, so every measure of the fold is exact. It first reads
 * its table, with the joins its measures need, once into a materialized {@code base} CTE of the group key and the
 * columns those measures declare, and ranks and aggregates from that, so the scan, the joins, the dimension and the
 * filters are paid once rather than once per pass.
 */
public final class MetricsQueryPlanner {

    private MetricsQueryPlanner() {}

    /** {@code from} and {@code to} are separate from the query so the previous window plans the same statement. */
    public static MetricsStatement plan(MetricsQuery q, String projectId, Instant from, Instant to) {
        MetricsView view = q.view();
        MetricsDimension dimension = q.dimension();
        MetricsGranularity unit = q.unit();
        List<Column> columns = columns(q.all());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pid", projectId);
        params.put("from", bound(view, from));
        params.put("to", bound(view, to));
        String where = where(q, params);
        if (unit != null) params.put("unit", unit.wire());

        String measures = columns.stream()
                        .map(c -> c.measures().get(0).sql() + " AS " + c.alias())
                        .collect(Collectors.joining(", "))
                + ", count(*) AS n";
        String bucket = "date_trunc(:unit::text, " + (view.textClock() ? view.clock() + "::timestamptz" : view.clock())
                + ", 'UTC') AS bkt, ";
        boolean anyRoot = q.all().stream().anyMatch(MetricsMeasure::needsRoot);
        boolean anyTrace = q.all().stream().anyMatch(MetricsMeasure::needsTrace);
        String source = from(view, anyRoot, anyTrace);

        if (dimension == null) {
            if (unit == null) {
                return new MetricsStatement(
                        Shape.TOTAL,
                        "SELECT " + measures + "\n  FROM " + source + "\n WHERE " + where,
                        params,
                        columns);
            }
            return new MetricsStatement(
                    Shape.SERIES,
                    "SELECT " + bucket + measures + "\n  FROM " + source + "\n WHERE " + where
                            + "\n GROUP BY 1\n ORDER BY 1",
                    params,
                    columns);
        }

        params.put("topN", q.topN());
        params.put("topNPlusOne", q.topN() + 1);
        MetricsMeasure ranking = q.all().get(0);
        String alias = view.alias();
        Set<String> reads = new LinkedHashSet<>();
        for (MetricsMeasure m : q.all()) reads.addAll(m.reads());
        Set<String> carried = new LinkedHashSet<>();
        carried.add(dimension.sql() + " AS k");
        if (unit != null) carried.add(view.clock());
        for (String column : reads) carried.add(owned(alias, column) ? column : column + " AS " + carriedName(column));
        String ranked = "WITH base AS MATERIALIZED (\n    SELECT " + String.join(", ", carried) + "\n      FROM "
                + source + "\n     WHERE " + where + "),\nranked AS (\n    SELECT k, row_number() OVER (ORDER BY "
                + ranking.rankSql() + " DESC NULLS LAST, k ASC) AS rn\n      FROM " + fromBase(alias, ranking.reads())
                + "\n     GROUP BY 1)\n";
        String keyed = "CASE WHEN r.rn <= :topN THEN r.k ELSE '" + MetricsSql.OTHER + "' END AS k,"
                + " LEAST(r.rn, :topNPlusOne) AS rank, " + measures + ", (SELECT count(*) FROM ranked) AS total_keys"
                + "\n  FROM " + fromBase(alias, reads) + " JOIN ranked r ON r.k = " + alias + ".k";
        if (unit == null) {
            return new MetricsStatement(
                    Shape.GROUPED, ranked + "SELECT " + keyed + "\n GROUP BY 1, 2\n ORDER BY 2", params, columns);
        }
        return new MetricsStatement(
                Shape.GROUPED_SERIES,
                ranked + "SELECT " + bucket + keyed + "\n GROUP BY 1, 2, 3\n ORDER BY 1, 3",
                params,
                columns);
    }

    /** Measures sharing an alias share one SQL expression, so each alias is one column, in first-appearance order. */
    private static List<Column> columns(List<MetricsMeasure> all) {
        Map<String, List<MetricsMeasure>> byAlias = new LinkedHashMap<>();
        for (MetricsMeasure m : all)
            byAlias.computeIfAbsent(m.alias(), a -> new ArrayList<>()).add(m);
        return byAlias.entrySet().stream()
                .map(e -> new Column(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    /**
     * Reads {@code base} under the view's own alias, so a measure reads the columns its view owns unchanged, and puts
     * each joined table's carried columns back under that table's alias. Postgres flattens these laterals: the plan has
     * no node for them.
     */
    private static String fromBase(String alias, Collection<String> reads) {
        Map<String, List<String>> joined = new LinkedHashMap<>();
        for (String column : reads) {
            if (owned(alias, column)) continue;
            String table = column.substring(0, column.indexOf('.'));
            String name = column.substring(column.indexOf('.') + 1);
            joined.computeIfAbsent(table, t -> new ArrayList<>())
                    .add(alias + "." + carriedName(column) + " AS " + name);
        }
        StringBuilder out = new StringBuilder("base ").append(alias);
        joined.forEach((table, columns) -> out.append("\n  CROSS JOIN LATERAL (SELECT ")
                .append(String.join(", ", columns))
                .append(") ")
                .append(table));
        return out.toString();
    }

    private static boolean owned(String alias, String column) {
        return column.startsWith(alias + ".");
    }

    /** A joined table's column is carried as {@code table_column}, so it cannot clash with one the view owns. */
    private static String carriedName(String column) {
        return column.replace('.', '_');
    }

    private static String from(MetricsView view, boolean root, boolean trace) {
        return view.base()
                + (root ? "\n  " + MetricsSql.ROOT_LATERAL : "")
                + (trace ? "\n  " + MetricsSql.TRACE_JOIN : "");
    }

    private static String where(MetricsQuery q, Map<String, Object> params) {
        String clock = q.view().clock();
        StringBuilder where = new StringBuilder(q.view().gate())
                .append("\n   AND ")
                .append(clock)
                .append(" >= :from AND ")
                .append(clock)
                .append(" < :to");
        List<MetricsQuery.Filter> filters = q.filters();
        for (int i = 0; i < filters.size(); i++) {
            MetricsQuery.Filter f = filters.get(i);
            String match = f.dimension().sql() + " = ANY(:f" + i + ")";
            where.append("\n   AND ").append(f.negated() ? "NOT (" + match + ")" : match);
            params.put("f" + i, f.values().toArray(String[]::new));
        }
        return where.toString();
    }

    /** The cases clock is ISO text, compared as text; the rest are timestamptz. */
    private static Object bound(MetricsView view, Instant at) {
        return view.textClock() ? MetricsSql.TEXT_CLOCK.format(at) : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
