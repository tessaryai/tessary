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
 * {@code facets} / keyword {@code search} over the substrate (span / tool_call) and classifier
 * events. This is the project's established cross-feature <b>read</b> pattern: a
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
 * <p><b>Time column.</b> Range, keyset and bucket predicates all run on {@link QueryDataset#timeColumn()},
 * each dataset's own event clock — {@code started_at} for {@code span}/{@code tool_call},
 * {@code subject_started_at} for {@code classifier_events}, {@code bucket_start} for
 * {@code metric_rollups} — never a literal {@code "created_at"}. Per {@link QueryDataset#timeIsTimestamptz()} the
 * predicates cast the bound time params with {@code ::timestamptz} for the timestamptz datasets (and read
 * the column back as an ISO-8601 instant), while the TEXT datasets compare lexicographically; both stay
 * served by the {@code (project_id, <time column>)} indexes. {@code created_at} (ingest time) still rides
 * on every search row for display, read separately from the event clock.
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
     *
     * <p>{@code createdAt} is always the row's literal {@code created_at} (ingest time), for display.
     * {@code eventTs} is the dataset's own {@link QueryDataset#timeColumn()} value for THIS row — the
     * keyset actually orders and tiebreaks on it — and is package-private: {@link QueryDtos} never
     * projects it onto the wire, only {@link #search} reads it, to mint the next cursor.
     */
    public record SearchRow(String id, String createdAt, String eventTs, Map<String, String> fields) {}

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
     * Bucketed {@code COUNT(*)} over the dataset's time column at {@code interval}, ascending. The bucket
     * key is {@code date_trunc(:unit, <time column>::timestamptz)} — the cast lives in the SELECT, never
     * the WHERE.
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

    /** The cursor version prefix: bumped whenever the token's shape or the clock it orders on changes. */
    private static final String CURSOR_VERSION = "e1";

    /**
     * A bounded, keyset-paginated page of matching rows (not aggregated). The optional keyword {@code q}
     * is matched case-insensitively (ILIKE) across the dataset's allow-listed text columns; {@code
     * filters} and {@code range} apply as in the aggregations. Keyset cursor is
     * {@code (<time column>, <keyColumns…>)} descending — newest first — mirroring
     * {@code SubstrateReadRepository}'s gap-free keyset shape. For {@code spans} that tuple is
     * {@code (started_at, trace_id, id)}: the span's own id does not break ties on its own, because two
     * different traces may legitimately contain a span with the same producer id.
     *
     * <p>A cursor minted by an earlier release, or one with the wrong tuple arity, names a position that
     * no longer exists or ranges on a clock this release does not use. It is not an error — an unprefixed
     * or malformed cursor is ignored and the caller silently gets page one, which is the same degradation
     * the traces list chose, and strictly better than a 500 on a bookmarked page token, or silently paging
     * on the wrong column.
     *
     * @param displayColumns trusted {@code wire field -> column identifier} projection for each row's
     *     {@code fields} map (the column is SELECTed {@code AS} the wire field)
     * @param cursor opaque {@code "e1|<eventTs>|<handle>"} token from a previous page, or null for page one
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

        // Keyset: rows strictly before the cursor in (timeColumn DESC, keyColumns… DESC) order. Only a
        // cursor stamped with this release's version is trusted; anything else (unprefixed, or a future
        // version this build predates) falls back to page one rather than paging on the wrong clock.
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

        // Fetched limit+1 to know whether another page exists; trim and mint the next cursor.
        String next = null;
        if (rows.size() > capped) {
            SearchRow last = rows.get(capped - 1);
            rows = rows.subList(0, capped);
            next = versionPrefix + last.eventTs() + "|" + last.id();
        }
        return new SearchPage(List.copyOf(rows), next);
    }

    // ---- row handles -----------------------------------------------------------------------------

    /**
     * Split an opaque row handle back into its identity values. A single-key dataset's handle is the value
     * itself; a composite handle splits on the FIRST {@code ':'} for each leading key, so the last
     * component keeps any colon a producer id might contain rather than being silently truncated.
     * Returns an empty list when the handle does not carry {@code arity} components — the keyset cursor
     * treats that as "no usable cursor" rather than guessing.
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
     * identifiers; their values are bound. Range bounds compare on the dataset's own {@link
     * QueryDataset#timeColumn()}, index-served either way (timestamptz or the lexicographic TEXT form).
     */
    private static Map<String, Object> basePredicate(Scope scope, List<String> where) {
        Map<String, Object> params = new LinkedHashMap<>();
        // The time column is a trusted, allow-list-resolved identifier, per dataset — never a request string.
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

    /** The row's literal {@code created_at} (ingest time) as an ISO-8601 instant, read back via {@link
     *  ai.tessary.storage.Timestamps}. Always {@code created_at}, regardless of the dataset's {@link
     *  QueryDataset#timeColumn()}. Every searchable dataset's time columns are timestamptz. */
    private static String readTime(ResultSet rs) throws SQLException {
        String ts = ai.tessary.storage.Timestamps.iso(rs, "created_at");
        return ts == null ? "" : ts;
    }

    /** The row's {@code event_ts} alias — its value of {@link QueryDataset#timeColumn()} — the clock the
     *  keyset actually orders on and the cursor is minted from. Same read as {@link #readTime}. */
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
