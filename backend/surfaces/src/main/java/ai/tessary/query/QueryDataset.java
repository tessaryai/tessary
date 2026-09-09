// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The allow-listed datasets the query API reads, and, per dataset, the closed set of columns
 * that may be used as a facet/group-by <em>dimension</em> or scanned by keyword {@code search()}. This
 * enum is the SQL-injection firewall: a request names a dataset and a field as opaque snake_case wire
 * strings; the service resolves them <b>here</b> to fixed table/column identifiers that are the only
 * thing ever interpolated into SQL. A value outside the allow-list is a {@code 400}, never a query.
 *
 * <p>Every dataset is project-scoped on {@code project_id} and time-ordered on a {@code created_at}
 * TEXT column (ISO-8601), matching the substrate convention.
 * The {@code field -> column} maps are intentionally small and index-aware (see {@code QueryRepository})
 * so v1 stays index-served.
 *
 * <h2>Row identity is per-dataset</h2>
 *
 * <p>A span's identity is the producer triple {@code (project_id, trace_id, id)}, and its
 * {@code id} is unique only inside its trace, so a bare id names nothing. Two members carry that
 * fact: {@link #idExpr()} is the SQL expression that renders a row's handle (for {@code spans},
 * {@code trace_id || ':' || id}, the same handle {@code get_span} and the embedding namespace use),
 * and {@link #keyColumns()} is the ordered identity tuple the keyset cursor and the kNN hydration
 * compare on. Single-key datasets simply declare {@code id} for both.
 */
public enum QueryDataset {
    /**
     * Typed spans: facet/filter/search by {@code kind} and {@code name}, and facet/filter by
     * {@code call_site_id} / {@code session_id} / {@code status} /
     * {@code model_id} / {@code cost_source}. Faceting {@code call_site_id} answers "which call sites
     * have telemetry, and how much" in one request. The filter path is index-served
     * ({@code ix_span_call_site}, {@code ix_span_project_started}); the facet's
     * {@code GROUP BY} aggregates over the project-bounded row set those indexes select.
     *
     * <p>Wire name {@code spans} is the only spelling the query API and the MCP dataset enum accept;
     * any other is a {@code 400}.
     *
     * <p>Search reads previews, not payloads: {@code input}/{@code output} moved off-row to
     * {@code span_payload}, and this dataset stays a single-table read (no payload join), so keyword
     * search matches {@code name} and the two 200-char previews. Full-text over the payload is
     * the global-search surface's job (it has the GIN index for it); this one answers structured
     * questions cheaply.
     *
     * <p>Untagged spans form a null facet bucket: an untagged span has a null {@code call_site_id},
     * and {@code GROUP BY} emits it as a {@code Facet} with a {@code null} value, ranked by count
     * like any other bucket, so early on it is usually the largest. Two consequences for callers:
     * drop the null-valued bucket rather than treating it as a call site, and remember it consumes
     * one of the {@code top_n} slots, so a response holding {@code top_n} buckets may have evicted a
     * real, low-traffic call site. The equality filter is unaffected: SQL {@code =} never matches
     * NULL, so filtering by a call site excludes untagged rows outright.
     */
    SPANS(
            "spans",
            "span",
            "trace_id || ':' || id",
            List.of("trace_id", "id"),
            dims(
                    entry("kind", "kind"),
                    entry("name", "name"),
                    entry("call_site_id", "call_site_id"),
                    entry("session_id", "session_id"),
                    entry("status", "status"),
                    entry("model_id", "model_id"),
                    entry("cost_source", "cost_source")),
            dims(
                    entry("kind", "kind"),
                    entry("name", "name"),
                    // `model` is the producer's own model string (v1's observation.model); `model_id` is the
                    // resolved catalogue key the price book is stated in. Both are filterable because they
                    // answer different questions: "what did the SDK say" vs "what did we price it as".
                    entry("model", "provided_model_name"),
                    entry("model_id", "model_id"),
                    entry("trace_id", "trace_id"),
                    entry("span_id", "id"),
                    entry("call_site_id", "call_site_id"),
                    entry("session_id", "session_id"),
                    entry("status", "status"),
                    entry("cost_source", "cost_source")),
            Set.of("name", "input_preview", "output_preview"),
            dims(
                    entry("kind", "kind"),
                    entry("name", "name"),
                    // Both halves of the identity ride in the projection, so a caller can feed a search row
                    // straight into get_span without parsing the composite handle.
                    entry("trace_id", "trace_id"),
                    entry("span_id", "id"),
                    entry("cost_source", "cost_source")),
            true), // created_at is timestamptz

    /**
     * First-class tool calls: facet/filter/search by tool {@code name}, filter by the producer keys of
     * the span that made the call. The table keeps its own surrogate {@code id} (it is not part of the v2
     * substrate), so its handle is still a bare id; what changed is the <em>pointer</em>: it names a span
     * by {@code (trace_id, span_id)} rather than by a surrogate observation id.
     */
    TOOL_CALLS(
            "tool_calls",
            "tool_call",
            "id",
            List.of("id"),
            dims(entry("name", "name")),
            dims(entry("name", "name"), entry("trace_id", "trace_id"), entry("span_id", "span_id")),
            Set.of("name", "error_type"),
            dims(
                    entry("name", "name"),
                    entry("error_type", "error_type"),
                    entry("latency_ms", "latency_ms"),
                    entry("trace_id", "trace_id"),
                    entry("span_id", "span_id")),
            true), // created_at is timestamptz

    /**
     * Classifier detections: facet by {@code classifier_id}/{@code severity}/{@code confidence}/
     * {@code subject_kind}. Each per-span classifier writes its own detection table; this reads
     * {@code DetectionTableRegistry}'s runtime-stitched union over them, so {@code classifier_id} is
     * the classifier's key rather than the classifier row's id, and {@code severity} is its coarse
     * band. {@link #table()} for this dataset is never read as a real relation name: it is a marker,
     * {@link QueryRepository#relation}'s {@code dataset ==} switch resolves this one dataset to the
     * union instead.
     *
     * <p>Queries over a range before the classifier-pipeline cutover return nothing: earlier
     * detections lived as {@code verdict} rows that were deleted, not migrated into this table.
     *
     * <p>A subject span id is half a key: {@code subject_span_id} is a producer span id, unique only
     * inside its trace, so it's only meaningful read together with {@code subject_trace_id}, which is
     * why both are filterable and both are projected. {@code subject_kind} takes exactly three
     * values, {@code span}/{@code trace}/{@code session}; the producer-keyed fields are the only
     * spelling for the subject location.
     */
    CLASSIFIER_EVENTS(
            "classifier_events",
            "classifier_events_union", // marker only; see class javadoc + QueryRepository#relation
            "id",
            List.of("id"),
            dims(
                    entry("classifier_id", "classifier_id"),
                    entry("severity", "severity"),
                    entry("confidence", "confidence"),
                    entry("subject_kind", "subject_kind")),
            dims(
                    entry("classifier_id", "classifier_id"),
                    entry("severity", "severity"),
                    entry("confidence", "confidence"),
                    entry("subject_kind", "subject_kind"),
                    entry("subject_session_id", "subject_session_id"),
                    entry("subject_trace_id", "subject_trace_id"),
                    entry("subject_span_id", "subject_span_id")),
            Set.of("severity"),
            dims(
                    entry("classifier_id", "classifier_id"),
                    entry("severity", "severity"),
                    entry("confidence", "confidence"),
                    entry("subject_kind", "subject_kind"),
                    entry("subject_trace_id", "subject_trace_id"),
                    entry("subject_span_id", "subject_span_id")),
            true), // created_at is timestamptz on every detection table

    /**
     * Pre-aggregated usage rollups: the {@code metric_rollup} table the metering worker writes. This
     * dataset is fundamentally different from the three above: it is already aggregated, so its
     * measure is {@code SUM(value)} (a {@link #measureColumn}), not {@code COUNT(*)}, and its time
     * column is {@code bucket_start} (a {@link #timeColumn}), not {@code created_at}. Facet/filter by
     * {@code metric} and {@code granularity} (always filter {@code granularity} so a
     * {@code timeseries} does not mix the hour and day grains into one truncated bucket and
     * double-count). Search is unsupported ({@link #supportsSearch} false): a rollup row has no text
     * to match and no page to keyset. The aggregation results are consistent with the dedicated
     * {@code MeteringController} timeseries.
     */
    USAGE_ROLLUPS(
            "metric_rollups",
            "metric_rollup",
            "bucket_start",
            "value",
            false,
            "id",
            List.of("id"),
            dims(entry("metric", "metric"), entry("granularity", "granularity")),
            dims(entry("metric", "metric"), entry("granularity", "granularity")),
            Set.of(),
            dims(),
            false);

    private final String wireName;
    private final String table;
    private final String timeColumn;
    private final @org.jspecify.annotations.Nullable String measureColumn;
    private final boolean supportsSearch;
    private final String idExpr;
    private final List<String> keyColumns;
    private final Map<String, String> facetColumns;
    private final Map<String, String> filterColumns;
    private final Set<String> searchColumns;
    private final Map<String, String> displayColumns;
    private final boolean timeIsTimestamptz;

    /** The COUNT(*)-over-{@code created_at} datasets: the common case (every dataset but USAGE_ROLLUPS). */
    QueryDataset(
            String wireName,
            String table,
            String idExpr,
            List<String> keyColumns,
            Map<String, String> facetColumns,
            Map<String, String> filterColumns,
            Set<String> searchColumns,
            Map<String, String> displayColumns,
            boolean timeIsTimestamptz) {
        this(
                wireName,
                table,
                "created_at",
                null,
                true,
                idExpr,
                keyColumns,
                facetColumns,
                filterColumns,
                searchColumns,
                displayColumns,
                timeIsTimestamptz);
    }

    QueryDataset(
            String wireName,
            String table,
            String timeColumn,
            @org.jspecify.annotations.Nullable String measureColumn,
            boolean supportsSearch,
            String idExpr,
            List<String> keyColumns,
            Map<String, String> facetColumns,
            Map<String, String> filterColumns,
            Set<String> searchColumns,
            Map<String, String> displayColumns,
            boolean timeIsTimestamptz) {
        this.wireName = wireName;
        this.table = table;
        this.timeColumn = timeColumn;
        this.measureColumn = measureColumn;
        this.supportsSearch = supportsSearch;
        this.idExpr = idExpr;
        this.keyColumns = List.copyOf(keyColumns);
        this.facetColumns = facetColumns;
        this.filterColumns = filterColumns;
        this.searchColumns = searchColumns;
        this.displayColumns = displayColumns;
        this.timeIsTimestamptz = timeIsTimestamptz;
    }

    /**
     * Whether the dataset's {@code timeColumn} is a native {@code timestamptz} (the TEXT→timestamptz
     * cutover, {@code observation} first). When true the query SQL casts bound time params with
     * {@code ::timestamptz} and reads the time column back as an ISO-8601 instant; when false the column is
     * ISO-8601 TEXT and compares lexicographically. Never user input.
     */
    public boolean timeIsTimestamptz() {
        return timeIsTimestamptz;
    }

    /** The physical table name (a fixed identifier; never user input). */
    public String table() {
        return table;
    }

    /**
     * The SQL expression rendering a row's opaque handle: the value that comes back as a search
     * row's {@code id} and that a caller hands to a point-lookup. A fixed, trusted enum constant, never
     * user input. Bare {@code id} for every dataset with a surrogate key; {@code trace_id || ':' || id}
     * for {@code spans}, because a span id alone does not identify a span.
     */
    public String idExpr() {
        return idExpr;
    }

    /**
     * The identity columns behind {@link #idExpr()}, in the order the handle concatenates them. The keyset
     * cursor's tiebreak tuple and the kNN hydration's row-constructor predicate are both built from this,
     * so a dataset's identity is stated once and the two paths cannot disagree. Trusted identifiers.
     */
    public List<String> keyColumns() {
        return keyColumns;
    }

    /**
     * The dataset's time column: a trusted, fixed identifier the query SQL ranges + buckets on. Defaults to
     * {@code created_at} (the substrate convention); {@code metric_rollups} overrides it to {@code bucket_start}
     * (the rollup grain column). Never user input.
     */
    public String timeColumn() {
        return timeColumn;
    }

    /**
     * The numeric measure column to {@code SUM}, or {@code null} for the default {@code COUNT(*)}. A fixed,
     * trusted identifier (only {@code metric_rollups} sets one, {@code value}); never user input. A SUM
     * dataset's {@code count}/{@code timeseries}/{@code facets} return {@code SUM(measure)} in place of the
     * row count.
     */
    public @org.jspecify.annotations.Nullable String measureColumn() {
        return measureColumn;
    }

    /**
     * Whether keyword/keyset {@code search()} is supported. {@code false} for pre-aggregated datasets
     * ({@code metric_rollups}) that have no text columns and no row-level page; the service rejects a search
     * on them with a {@code 400} before the repository's {@code created_at} keyset cursor can run.
     */
    public boolean supportsSearch() {
        return supportsSearch;
    }

    /** The snake_case wire identifier for this dataset. */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolve a snake_case wire dataset name to the enum, or {@code 400} if unknown. The match is exact on
     * {@link #wireName()}: a dataset has exactly one spelling on the wire, and a writer that persists a
     * dataset name persists the resolved {@link #wireName()} rather than the caller's string.
     */
    public static QueryDataset fromWire(String wire) {
        for (QueryDataset d : values()) {
            if (d.wireName.equals(wire)) {
                return d;
            }
        }
        throw new TessaryException(QueryError.UNKNOWN_DATASET, wire);
    }

    /**
     * Resolve a facet/group-by field to its fixed column identifier, or {@code 400} if the field is not
     * an allow-listed dimension on this dataset. The returned string is a trusted, hard-coded column
     * name: the only group-by token that reaches SQL.
     */
    public String facetColumn(String field) {
        String col = facetColumns.get(field);
        if (col == null) {
            throw new TessaryException(QueryError.UNKNOWN_FIELD, field, wireName);
        }
        return col;
    }

    /**
     * Resolve an equality-filter field to its fixed column identifier, or {@code 400} if the field is not
     * an allow-listed filter on this dataset. Filters are a slightly wider set than facet dimensions
     * (they include indexed discriminators that make sense to pin but not to group by, e.g. {@code
     * trace_id} / {@code span_id} / {@code subject_span_id}). The returned identifier is trusted; the
     * value is bound.
     */
    public String filterColumn(String field) {
        String col = filterColumns.get(field);
        if (col == null) {
            throw new TessaryException(QueryError.UNKNOWN_FIELD, field, wireName);
        }
        return col;
    }

    /**
     * The wire field names this dataset accepts as a facet/group-by dimension, in declaration order.
     *
     * <p>The <em>keys</em>, deliberately, not the map: a caller outside the query layer has no business
     * naming a physical column, and the resolution {@code field -> column} stays the firewall's private
     * business ({@link #facetColumn}). What the keys are for is telling a caller which fields exist:
     * {@code describe_dataset} on the MCP surface publishes exactly this, so the advertised vocabulary is
     * read off the enum rather than transcribed beside it. Unmodifiable (the backing map is built by
     * {@link #dims}).
     */
    public Set<String> facetFields() {
        return facetColumns.keySet();
    }

    /**
     * The wire field names this dataset accepts as an equality filter, in declaration order. Wider than
     * {@link #facetFields()}; see {@link #filterColumn} for why. Keys only, and unmodifiable, for the same
     * reasons.
     */
    public Set<String> filterFields() {
        return filterColumns.keySet();
    }

    /**
     * The allow-listed text columns keyword {@code search()} may match against on this dataset. Returns a
     * fixed list of trusted column identifiers; the request supplies only the bound query <em>value</em>.
     */
    public List<String> searchColumns() {
        return List.copyOf(searchColumns);
    }

    /**
     * The trusted columns a {@code search()} row projects into its {@code fields} map, keyed by wire
     * field name ({@code wire field -> column identifier}, insertion-ordered). Wire name and column
     * usually coincide; {@code spans} is the exception, aliasing {@code span_id} onto the row's own
     * {@code id}.
     */
    public Map<String, String> displayColumns() {
        return displayColumns;
    }

    private static Map.Entry<String, String> entry(String field, String column) {
        return Map.entry(field, column);
    }

    @SafeVarargs
    private static Map<String, String> dims(Map.Entry<String, String>... entries) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        // Unmodifiable but insertion-ordered (Map.copyOf would scramble displayColumns' SELECT order).
        return java.util.Collections.unmodifiableMap(m);
    }
}
