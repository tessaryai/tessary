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
 * The aggregation-first read surface for the query API: {@code count} / {@code timeseries} /
 * {@code facets} / keyword {@code search} over the substrate (span / tool_call) and
 * {@code signal_event}. This is the project's established cross-feature <b>read</b> pattern — a
 * dedicated {@code *Repository} holding raw {@code JdbcClient} SQL (like {@code SubstrateReadRepository}
 * and {@code AlertQueryRepository}); ArchUnit requires raw {@code JdbcClient} to live in a
 * {@code *Repository}, which this is. All query-API reads funnel through here, so callers
 * (service, controller, API client) never touch tables.
 *
 * <p><b>Injection safety.</b> Every dynamic identifier — the table, a facet column, a filter column, a
 * {@code date_trunc} unit — is a trusted constant resolved upstream by the {@link QueryDataset} /
 * {@link QueryInterval} allow-lists. Only <em>values</em> are bound as named parameters; no request
 * string is ever concatenated into SQL.
 *
 * <p><b>Row handles.</b> A search row's {@code id} is the dataset's {@link QueryDataset#idExpr()} — a bare
 * {@code id} for the surrogate-keyed datasets, and {@code "<trace_id>:<span_id>"} for {@code spans},
 * because a producer span id is unique only inside its trace and names nothing on its own. The keyset
 * cursor tiebreaks on {@link QueryDataset#keyColumns()} in the same order, so the cursor and the handle
 * always describe the same row.
 *
 * <p><b>Time column.</b> The {@code created_at} time column is native {@code timestamptz} for
 * {@code span} and ISO-8601 TEXT for the other datasets. Per
 * {@link QueryDataset#timeIsTimestamptz()} the range/keyset predicates cast the bound time params with
 * {@code ::timestamptz} for the timestamptz datasets (and read the column back as an ISO-8601 instant),
 * while the TEXT datasets compare lexicographically; both stay served by the {@code (project_id,
 * created_at)} indexes.
 */
@Repository
public class QueryRepository {

    /** Hard server-side caps so a caller can never request an unbounded scan. */
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
     * The FROM-clause source for a dataset: every {@link QueryDataset#table()} verbatim, except
     * {@link QueryDataset#CLASSIFIER_EVENTS}, whose {@code table()} is a marker string never read as a
     * real relation — resolved HERE instead to {@link DetectionTableRegistry}'s runtime-stitched union,
     * the query-time replacement for what used to be a view of that same six-arm shape. Every
     * downstream reference to a column in these queries is unqualified (no table alias), so a
     * derived-table alias works exactly like a bare table name would.
     */
    private String relation(QueryDataset dataset) {
        return dataset == QueryDataset.CLASSIFIER_EVENTS ? "(" + detectionTables.unionSql() + ") cde" : dataset.table();
    }

    /** A validated, resolved query scope: trusted column identifiers + the values to bind. */
    record Scope(
            QueryDataset dataset,
            String projectId,
            @Nullable String from,
            @Nullable String to,
            Map<String, String> filters) {}

    /** One timeseries bucket. */
    public record Bucket(String bucketStart, long count) {}

    /** One facet value and its count. */
    public record Facet(@Nullable String value, long count) {}

    /**
     * One matched search row. {@code id} is the dataset's opaque handle ({@link QueryDataset#idExpr()}) —
     * a bare row id for the surrogate-keyed datasets, {@code "<trace_id>:<span_id>"} for {@code spans}.
     * The {@code spans} projection also carries {@code trace_id} and {@code span_id} as ordinary fields,
     * so a caller feeding {@code get_span} never has to parse the handle.
     */
    public record SearchRow(String id, String createdAt, Map<String, String> fields) {}

    /** A bounded page of search rows with an optional keyset continuation token. */
    public record SearchPage(List<SearchRow> rows, @Nullable String nextCursor) {}

    // ---- count ---------------------------------------------------------------------------------

    /** {@code COUNT(*)} of rows in scope — or {@code SUM(measure)} for a pre-aggregated (measure) dataset. */
    public long count(Scope scope) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        var spec = jdbc.sql(
                "SELECT " + aggregateExpr(scope.dataset()) + " FROM " + relation(scope.dataset()) + whereClause(where));
        bind(spec, params);
        return spec.query(Long.class).single();
    }

    /**
     * The aggregate measure for a dataset: {@code COUNT(*)} by default, or {@code COALESCE(SUM(col), 0)} for a
     * pre-aggregated dataset that declares a {@link QueryDataset#measureColumn()} ({@code metric_rollups.value}).
     * The column is a trusted enum constant — never a request string — so this stays injection-safe.
     */
    private static String aggregateExpr(QueryDataset dataset) {
        String measure = dataset.measureColumn();
        return measure == null ? "COUNT(*)" : "COALESCE(SUM(" + measure + "), 0)";
    }

    // ---- timeseries ----------------------------------------------------------------------------

    /**
     * Bucketed {@code COUNT(*)} over {@code created_at} at {@code interval}, ascending. The bucket key is
     * {@code date_trunc(:unit, created_at::timestamptz)} — the cast lives in the SELECT, never the WHERE.
     */
    public List<Bucket> timeseries(Scope scope, QueryInterval interval) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        // The unit + time column are trusted enum constants, not bound params (date_trunc's first arg is a
        // literal; the column is allow-list-resolved). For a pre-aggregated dataset the time column is
        // the rollup's own bucket_start, re-truncated to the requested interval and SUMmed.
        String bucketExpr =
                "date_trunc('" + interval.truncUnit() + "', " + scope.dataset().timeColumn() + "::timestamptz)";
        // Alias the truncated bucket as `bkt` (not `bucket_start`): the metric_rollup dataset HAS a real
        // `bucket_start` column, and an unqualified `GROUP BY bucket_start` would bind to that input column
        // (grouping by the raw per-hour value) rather than the truncated SELECT expression. `bkt` collides
        // with no dataset column, so the GROUP BY always references the date_trunc expression.
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

    // ---- facets --------------------------------------------------------------------------------

    /**
     * Top-N {@code (value, count)} breakdown of a fixed dimension column, largest-measure first. The measure
     * is {@code COUNT(*)} by default, or {@code SUM(value)} for a pre-aggregated dataset — e.g. total
     * usage broken down by {@code metric}.
     */
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

    // ---- search --------------------------------------------------------------------------------

    /**
     * A bounded, keyset-paginated page of matching rows (not aggregated). The optional keyword {@code q}
     * is matched case-insensitively (ILIKE) across the dataset's allow-listed text columns; {@code
     * filters} and {@code range} apply as in the aggregations. Keyset cursor is
     * {@code (created_at, <keyColumns…>)} descending — newest first — mirroring
     * {@code SubstrateReadRepository}'s gap-free keyset shape. For {@code spans} that tuple is
     * {@code (created_at, trace_id, id)}: the span's own id does not break ties on its own, because two
     * different traces may legitimately contain a span with the same producer id.
     *
     * <p>A cursor minted by an earlier release names a position that no longer exists. It is not an
     * error — a cursor whose tuple has the wrong arity is ignored and the caller silently gets page one,
     * which is the same degradation the traces list chose, and strictly better than a 500 on a bookmarked
     * page token.
     *
     * @param displayColumns trusted {@code wire field -> column identifier} projection for each row's
     *     {@code fields} map (the column is SELECTed {@code AS} the wire field)
     * @param cursor opaque {@code "<createdAt>|<handle>"} token from a previous page, or null for page one
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

        // Keyset: rows strictly before the cursor in (created_at DESC, keyColumns… DESC) order.
        if (cursor != null && !cursor.isBlank()) {
            int sep = cursor.lastIndexOf('|');
            List<String> keyValues = sep > 0 ? splitHandle(cursor.substring(sep + 1), keyColumns.size()) : List.of();
            if (keyValues.size() == keyColumns.size()) {
                String tsCast = scope.dataset().timeIsTimestamptz() ? "::timestamptz" : "";
                List<String> placeholders = new ArrayList<>();
                placeholders.add(":cur_ts" + tsCast);
                for (int i = 0; i < keyColumns.size(); i++) {
                    placeholders.add(":cur_k" + i);
                    params.put("cur_k" + i, keyValues.get(i));
                }
                where.add("(created_at, " + String.join(", ", keyColumns) + ") < (" + String.join(", ", placeholders)
                        + ")");
                params.put("cur_ts", cursor.substring(0, sep));
            }
        }

        int capped = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        StringBuilder select = new StringBuilder("SELECT " + scope.dataset().idExpr() + " AS row_handle, created_at");
        for (Map.Entry<String, String> col : displayColumns.entrySet()) {
            select.append(", ").append(col.getValue()).append(" AS ").append(col.getKey());
        }
        var spec = jdbc.sql(select + " FROM " + relation(scope.dataset()) + whereClause(where)
                + " ORDER BY created_at DESC, " + descending(keyColumns) + " LIMIT " + (capped + 1));
        bind(spec, params);

        List<SearchRow> rows = spec.query((rs, n) -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (String field : displayColumns.keySet()) {
                        fields.put(field, rs.getString(field));
                    }
                    return new SearchRow(rs.getString("row_handle"), readTime(rs, scope), fields);
                })
                .list();

        // Fetched limit+1 to know whether another page exists; trim and mint the next cursor.
        String next = null;
        if (rows.size() > capped) {
            SearchRow last = rows.get(capped - 1);
            rows = rows.subList(0, capped);
            next = last.createdAt() + "|" + last.id();
        }
        return new SearchPage(List.copyOf(rows), next);
    }

    /**
     * Hydrate a kNN-ranked list of row handles into display rows, scoped to the project, <b>preserving the
     * input order</b> (the semantic-search ranking by cosine distance). ONE query fetches every row (no
     * N+1) — {@code id = ANY(:ids)} for a surrogate-keyed dataset, a row-constructor {@code IN} over the
     * identity tuple for a composite-keyed one; the result is then re-ordered in Java to the requested
     * ranking, since SQL has no inherent order over an id set. Handles with no row (e.g. churned since
     * indexing) are dropped, as are handles that do not parse. The page carries a null {@code nextCursor}:
     * kNN is top-k by distance, not keyset-paginable, so there is no continuation token.
     *
     * <p>The scope's time range and equality filters are honored here too — the same {@code basePredicate}
     * the keyword/aggregation paths use is applied on top of the {@code id = ANY(:ids)} restriction — so a
     * semantic search with a {@code range}/{@code filters} returns the kNN matches that <em>also</em>
     * satisfy those constraints, never the unfiltered nearest neighbours. (The kNN bound is applied first
     * over the whole namespace, so a restrictive filter can shrink the page below the requested top-k.)
     *
     * @param orderedIds the row handles, nearest-first; the returned rows follow this order
     * @param displayColumns trusted {@code wire field -> column identifier} projection for each row's
     *     {@code fields} map (the column is SELECTed {@code AS} the wire field)
     */
    public SearchPage searchByIds(Scope scope, List<String> orderedIds, Map<String, String> displayColumns) {
        if (orderedIds.isEmpty()) {
            return new SearchPage(List.of(), null);
        }
        StringBuilder select = new StringBuilder("SELECT " + scope.dataset().idExpr() + " AS row_handle, created_at");
        for (Map.Entry<String, String> col : displayColumns.entrySet()) {
            select.append(", ").append(col.getValue()).append(" AS ").append(col.getKey());
        }
        // project_id scope is enforced here too (defense in depth): the namespace already partitions by
        // project, but the row hydration must never cross a tenant boundary. The shared basePredicate adds
        // the project_id, time-range, and equality-filter clauses; the kNN id set is an extra restriction.
        List<String> where = new ArrayList<>();
        Map<String, Object> params = basePredicate(scope, where);
        addHandleRestriction(scope.dataset(), orderedIds, where, params);
        var spec = jdbc.sql(select + " FROM " + relation(scope.dataset()) + whereClause(where));
        bind(spec, params);
        Map<String, SearchRow> byId = new LinkedHashMap<>();
        spec.query((rs, n) -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (String field : displayColumns.keySet()) {
                        fields.put(field, rs.getString(field));
                    }
                    return new SearchRow(rs.getString("row_handle"), readTime(rs, scope), fields);
                })
                .list()
                .forEach(row -> byId.put(row.id(), row));
        // Re-apply the kNN ranking: SQL returned the set in arbitrary order.
        List<SearchRow> ranked = new ArrayList<>(byId.size());
        for (String id : orderedIds) {
            SearchRow row = byId.get(id);
            if (row != null) ranked.add(row);
        }
        return new SearchPage(List.copyOf(ranked), null);
    }

    // ---- row handles -----------------------------------------------------------------------------

    /**
     * Split an opaque row handle back into its identity values. A single-key dataset's handle is the value
     * itself; a composite handle splits on the FIRST {@code ':'} for each leading key, so the last
     * component keeps any colon a producer id might contain rather than being silently truncated.
     * Returns an empty list when the handle does not carry {@code arity} components — the caller treats
     * that as "no usable cursor / no such row" rather than guessing.
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

    /**
     * Restrict a hydration read to the rows named by {@code handles}. A single-key dataset keeps the
     * original {@code id = ANY(:ids)} shape. A composite-key dataset gets a row-constructor
     * {@code (trace_id, id) IN ((:h0k0, :h0k1), …)}, which is index-eligible on the span primary key —
     * concatenating the handle in SQL instead would defeat every index on the largest table in the schema.
     * Handles that do not parse are dropped: they name no row, and a malformed vector-index entry must not
     * take the whole page down.
     */
    private static void addHandleRestriction(
            QueryDataset dataset, List<String> handles, List<String> where, Map<String, Object> params) {
        List<String> keyColumns = dataset.keyColumns();
        if (keyColumns.size() == 1) {
            where.add(keyColumns.get(0) + " = ANY(:ids)");
            params.put("ids", handles.toArray(new String[0]));
            return;
        }
        List<String> tuples = new ArrayList<>();
        int row = 0;
        for (String handle : handles) {
            List<String> values = splitHandle(handle, keyColumns.size());
            if (values.size() != keyColumns.size()) continue;
            List<String> placeholders = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                String p = "h" + row + "_" + i;
                placeholders.add(":" + p);
                params.put(p, values.get(i));
            }
            tuples.add("(" + String.join(", ", placeholders) + ")");
            row++;
        }
        if (tuples.isEmpty()) {
            // Nothing addressable — a predicate that matches no row, rather than an empty IN () (a syntax error).
            where.add("FALSE");
            return;
        }
        where.add("(" + String.join(", ", keyColumns) + ") IN (" + String.join(", ", tuples) + ")");
    }

    /** {@code "a DESC, b DESC"} for the keyset's identity tuple (trusted identifiers). */
    private static String descending(List<String> columns) {
        List<String> out = new ArrayList<>(columns.size());
        for (String c : columns) out.add(c + " DESC");
        return String.join(", ", out);
    }

    // ---- shared predicate building -------------------------------------------------------------

    /**
     * The always-applied project + time-range + equality-filter predicate. Mutates {@code where} with
     * clause fragments and returns the param map to bind. Filter columns are pre-resolved trusted
     * identifiers; their values are bound. Range bounds compare on the raw TEXT column (index-served).
     */
    private static Map<String, Object> basePredicate(Scope scope, List<String> where) {
        Map<String, Object> params = new LinkedHashMap<>();
        // The time column is a trusted, allow-list-resolved identifier (created_at, or bucket_start for the
        // pre-aggregated metric_rollups dataset) — never a request string.
        String timeColumn = scope.dataset().timeColumn();
        // Bind-param cast for a native timestamptz time column; "" for the ISO-8601 TEXT
        // datasets (which compare lexicographically).
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
            // key is a trusted, allow-list-resolved column identifier; value is bound.
            where.add(f.getKey() + " = :" + p);
            params.put(p, f.getValue());
        }
        return params;
    }

    /** The row's {@code created_at} as an ISO-8601 instant: read a native timestamptz column back
     *  via {@link ai.tessary.storage.Timestamps}, else the ISO-8601 TEXT column verbatim. */
    private static String readTime(ResultSet rs, Scope scope) throws SQLException {
        String ts = scope.dataset().timeIsTimestamptz()
                ? ai.tessary.storage.Timestamps.iso(rs, "created_at")
                : rs.getString("created_at");
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
