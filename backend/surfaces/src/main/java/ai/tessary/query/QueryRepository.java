// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import ai.tessary.detection.DetectionTableRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Injection safety: every dynamic identifier is a trusted constant resolved by the {@link QueryDataset} /
 * {@link QueryInterval} allow-lists; only values are bound. Predicates run on {@link QueryDataset#timeColumn()}, each
 * dataset's own event clock, never {@code created_at}. A {@code spans} row handle is {@code "<trace_id>:<span_id>"}
 * because a producer span id is unique only inside its trace.
 */
@Repository
public class QueryRepository {

    static final int MAX_FACET_TOP_N = 100;

    static final int DEFAULT_FACET_TOP_N = 20;
    static final int MAX_SEARCH_LIMIT = 1000;
    static final int DEFAULT_SEARCH_LIMIT = 100;

    private final JdbcClient jdbc;
    private final DetectionTableRegistry detectionTables;

    public QueryRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        this.detectionTables = detectionTables;
    }

    /**
     * {@link QueryDataset#CLASSIFIER_EVENTS}'s {@code table()} is a marker, resolved here to
     * {@link DetectionTableRegistry}'s runtime union. Columns are unqualified downstream, so a derived-table alias
     * works like a bare table name.
     */
    private String relation(QueryDataset dataset) {
        return dataset == QueryDataset.CLASSIFIER_EVENTS ? "(" + detectionTables.unionSql() + ") cde" : dataset.table();
    }

    record Scope(
            QueryDataset dataset,
            String projectId,
            @Nullable String from,
            @Nullable String to,
            Map<String, String> filters) {}

    public record Bucket(String bucketStart, long count) {}

    public record Facet(@Nullable String value, long count) {}

    /**
     * {@code eventTs} is the timeColumn value the keyset orders on; it is never projected onto the wire, only read to
     * mint the next cursor. {@code createdAt} is ingest time, for display.
     */
    public record SearchRow(String id, String createdAt, String eventTs, Map<String, String> fields) {}

    public record SearchPage(List<SearchRow> rows, @Nullable String nextCursor) {}

    public long count(Scope scope) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        var spec = jdbc.sql(
                "SELECT " + aggregateExpr(scope.dataset()) + " FROM " + relation(scope.dataset()) + whereClause(where));
        bind(spec, params);
        return spec.query(Long.class).single();
    }

    private static String aggregateExpr(QueryDataset dataset) {
        String measure = dataset.measureColumn();
        return measure == null ? "COUNT(*)" : "COALESCE(SUM(" + measure + "), 0)";
    }

    /** The cast lives in the SELECT, never the WHERE. */
    public List<Bucket> timeseries(Scope scope, QueryInterval interval) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        // date_trunc's first arg must be a literal, so unit and column are trusted enum constants, not bound params.
        String bucketExpr =
                "date_trunc('" + interval.truncUnit() + "', " + scope.dataset().timeColumn() + "::timestamptz)";
        // Aliased `bkt`, not `bucket_start`: metric_rollup has a real `bucket_start` column, and an unqualified GROUP
        // BY would bind to it instead of the truncated expression.
        var spec = jdbc.sql("SELECT " + bucketExpr + " AS bkt, " + aggregateExpr(scope.dataset()) + " AS c FROM "
                + relation(scope.dataset()) + whereClause(where)
                + " GROUP BY bkt ORDER BY bkt ASC");
        bind(spec, params);
        return spec.query((rs, n) -> {
                    var ts = rs.getObject("bkt", java.time.OffsetDateTime.class);
                    return new Bucket(ts == null ? "" : ts.toInstant().toString(), rs.getLong("c"));
                })
                .list();
    }

    public List<Facet> facets(Scope scope, String column, int topN) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        int capped = Math.max(1, Math.min(topN, MAX_FACET_TOP_N));
        var spec = jdbc.sql("SELECT " + column + " AS v, " + aggregateExpr(scope.dataset()) + " AS c FROM "
                + relation(scope.dataset()) + whereClause(where)
                + " GROUP BY " + column + " ORDER BY c DESC, v ASC NULLS LAST LIMIT " + capped);
        bind(spec, params);
        return spec.query((rs, n) -> new Facet(rs.getString("v"), rs.getLong("c")))
                .list();
    }

    /** Bumped whenever the token's shape or the clock it orders on changes. */
    private static final String CURSOR_VERSION = "e1";

    /**
     * For {@code spans} the keyset is {@code (started_at, trace_id, id)}, since two traces may contain a span with the
     * same producer id. An old-release or malformed cursor is ignored and the caller gets page one, rather than a 500
     * or paging on the wrong clock.
     */
    public SearchPage search(
            Scope scope,
            @Nullable String q,
            List<String> searchColumns,
            Map<String, String> displayColumns,
            int limit,
            @Nullable String cursor) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        List<String> keyColumns = scope.dataset().keyColumns();
        String timeColumn = scope.dataset().timeColumn();

        if (q != null && !q.isBlank()) {
            List<String> ors = new ArrayList<>();
            int i = 0;
            for (String col : searchColumns) {
                String p = "q" + i++;
                ors.add(col + " ILIKE :" + p);
                params.put(p, "%" + q + "%");
            }
            if (!ors.isEmpty()) {
                where.add("(" + String.join(" OR ", ors) + ")");
            }
        }

        String versionPrefix = CURSOR_VERSION + "|";
        if (cursor != null && cursor.startsWith(versionPrefix)) {
            String rest = cursor.substring(versionPrefix.length());
            int sep = rest.lastIndexOf('|');
            List<String> keyValues = sep > 0 ? splitHandle(rest.substring(sep + 1), keyColumns.size()) : List.of();
            if (keyValues.size() == keyColumns.size()) {
                List<String> placeholders = new ArrayList<>();
                placeholders.add(":cur_ts::timestamptz");
                for (int i = 0; i < keyColumns.size(); i++) {
                    placeholders.add(":cur_k" + i);
                    params.put("cur_k" + i, keyValues.get(i));
                }
                where.add("(" + timeColumn + ", " + String.join(", ", keyColumns) + ") < ("
                        + String.join(", ", placeholders) + ")");
                params.put("cur_ts", rest.substring(0, sep));
            }
        }

        int capped = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        StringBuilder select = new StringBuilder(
                "SELECT " + scope.dataset().idExpr() + " AS row_handle, created_at, " + timeColumn + " AS event_ts");
        for (Map.Entry<String, String> col : displayColumns.entrySet()) {
            select.append(", ").append(col.getValue()).append(" AS ").append(col.getKey());
        }
        var spec = jdbc.sql(select + " FROM " + relation(scope.dataset()) + whereClause(where) + " ORDER BY "
                + timeColumn + " DESC, " + descending(keyColumns) + " LIMIT " + (capped + 1));
        bind(spec, params);

        List<SearchRow> rows = spec.query((rs, n) -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (String field : displayColumns.keySet()) {
                        fields.put(field, rs.getString(field));
                    }
                    return new SearchRow(rs.getString("row_handle"), readTime(rs), readEventTime(rs), fields);
                })
                .list();

        String next = null;
        if (rows.size() > capped) {
            SearchRow last = rows.get(capped - 1);
            rows = rows.subList(0, capped);
            next = versionPrefix + last.eventTs() + "|" + last.id();
        }
        return new SearchPage(List.copyOf(rows), next);
    }

    /**
     * A composite handle splits on the first {@code ':'} per leading key, so the last component keeps any colon a
     * producer id contains. Wrong arity yields an empty list.
     */
    private static List<String> splitHandle(String handle, int arity) {
        if (arity == 1) {
            return handle.isEmpty() ? List.of() : List.of(handle);
        }
        List<String> parts = new ArrayList<>(arity);
        String rest = handle;
        for (int i = 0; i < arity - 1; i++) {
            int sep = rest.indexOf(':');
            if (sep < 0) return List.of();
            parts.add(rest.substring(0, sep));
            rest = rest.substring(sep + 1);
        }
        if (rest.isEmpty()) return List.of();
        parts.add(rest);
        return parts;
    }

    private static String descending(List<String> columns) {
        List<String> out = new ArrayList<>(columns.size());
        for (String c : columns) out.add(c + " DESC");
        return String.join(", ", out);
    }

    private static Map<String, Object> basePredicate(Scope scope, List<String> where) {
        Map<String, Object> params = new LinkedHashMap<>();
        String timeColumn = scope.dataset().timeColumn();
        String tsCast = scope.dataset().timeIsTimestamptz() ? "::timestamptz" : "";
        where.add("project_id = :pid");
        params.put("pid", scope.projectId());
        if (scope.from() != null) {
            where.add(timeColumn + " >= :from" + tsCast);
            params.put("from", scope.from());
        }
        if (scope.to() != null) {
            where.add(timeColumn + " < :to" + tsCast);
            params.put("to", scope.to());
        }
        int i = 0;
        for (Map.Entry<String, String> f : scope.filters().entrySet()) {
            String p = "f" + i++;
            where.add(f.getKey() + " = :" + p);
            params.put(p, f.getValue());
        }
        return params;
    }

    /** Always {@code created_at}, regardless of the dataset's timeColumn. */
    private static String readTime(ResultSet rs) throws SQLException {
        String ts = ai.tessary.storage.Timestamps.iso(rs, "created_at");
        return ts == null ? "" : ts;
    }

    private static String readEventTime(ResultSet rs) throws SQLException {
        String ts = ai.tessary.storage.Timestamps.iso(rs, "event_ts");
        return ts == null ? "" : ts;
    }

    private static String whereClause(List<String> where) {
        return where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
    }

    private static void bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec.param(e.getKey(), e.getValue());
        }
    }
}
