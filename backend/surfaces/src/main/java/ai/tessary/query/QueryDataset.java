// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SQL-injection firewall for the query API: wire dataset and field names resolve HERE to fixed identifiers, the
 * only things ever interpolated into SQL, and anything else is a {@code 400}. Every dataset is project-scoped and
 * ranges on its own {@link #timeColumn()}. A span id is unique only inside its trace, so {@link #idExpr()} and
 * {@link #keyColumns()} carry the full identity.
 */
public enum QueryDataset {
    /**
     * Search reads the 200-char previews, not payloads, so this stays a single-table read. Untagged spans form a null
     * {@code call_site_id} facet bucket that takes one of the {@code top_n} slots; callers should drop it.
     */
    SPANS(
            "spans",
            "span",
            "started_at",
            null,
            true,
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
                    // `model` is what the SDK said; `model_id` is what we priced it as.
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
                    // Both halves ride in the projection so a search row feeds get_span directly.
                    entry("trace_id", "trace_id"),
                    entry("span_id", "id"),
                    entry("started_at", "started_at"),
                    entry("cost_source", "cost_source")),
            true),

    /** Keeps its own surrogate {@code id}; it points at its span by {@code (trace_id, span_id)}. */
    TOOL_CALLS(
            "tool_calls",
            "tool_call",
            "started_at",
            null,
            true,
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
                    entry("span_id", "span_id"),
                    entry("started_at", "started_at")),
            true),

    /**
     * Reads {@code DetectionTableRegistry}'s union over the per-classifier detection tables; {@link #table()} is only a
     * marker resolved by {@link QueryRepository#relation}. Detections from before the classifier-pipeline cutover were
     * deleted, not migrated. {@code subject_span_id} is meaningful only with {@code subject_trace_id}.
     */
    CLASSIFIER_EVENTS(
            "classifier_events",
            "classifier_events_union",
            "subject_started_at",
            null,
            true,
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
                    entry("subject_span_id", "subject_span_id"),
                    entry("subject_started_at", "subject_started_at")),
            true),

    /**
     * Already aggregated: the measure is {@code SUM(value)} over {@code bucket_start}. Always filter
     * {@code granularity}, or a timeseries mixes hour and day grains and double-counts. No search: a rollup row has no
     * text.
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
     * When true, bound time params are cast {@code ::timestamptz}; when false the column is ISO-8601 TEXT compared
     * lexicographically.
     */
    public boolean timeIsTimestamptz() {
        return timeIsTimestamptz;
    }

    public String table() {
        return table;
    }

    /** {@code trace_id || ':' || id} for {@code spans}, because a span id alone does not identify a span. */
    public String idExpr() {
        return idExpr;
    }

    /** The keyset cursor's tiebreak tuple, so identity is stated once. */
    public List<String> keyColumns() {
        return keyColumns;
    }

    /** {@code created_at} rides on every row for display but is filtered only where it is also the time column. */
    public String timeColumn() {
        return timeColumn;
    }

    /** Null means {@code COUNT(*)}; only {@code metric_rollups} sums {@code value}. */
    public @org.jspecify.annotations.Nullable String measureColumn() {
        return measureColumn;
    }

    /** Rejected with a {@code 400} before the {@code created_at} keyset cursor can run. */
    public boolean supportsSearch() {
        return supportsSearch;
    }

    public String wireName() {
        return wireName;
    }

    /** Exact: a dataset has one wire spelling. */
    public static QueryDataset fromWire(String wire) {
        for (QueryDataset d : values()) {
            if (d.wireName.equals(wire)) {
                return d;
            }
        }
        throw new TessaryException(QueryError.UNKNOWN_DATASET, wire);
    }

    /** The returned name is the only group-by token that reaches SQL. */
    public String facetColumn(String field) {
        String col = facetColumns.get(field);
        if (col == null) {
            throw new TessaryException(QueryError.UNKNOWN_FIELD, field, wireName);
        }
        return col;
    }

    /**
     * Wider than facets: includes indexed discriminators worth pinning but not grouping. The identifier is trusted; the
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
     * Keys only: the {@code field -> column} resolution stays the firewall's private business. {@code describe_dataset}
     * publishes exactly this.
     */
    public Set<String> facetFields() {
        return facetColumns.keySet();
    }

    public Set<String> filterFields() {
        return filterColumns.keySet();
    }

    /** The request supplies only the bound value. */
    public List<String> searchColumns() {
        return List.copyOf(searchColumns);
    }

    /** {@code spans} aliases {@code span_id} onto the row's own {@code id}. */
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
        // Map.copyOf would scramble displayColumns' SELECT order.
        return java.util.Collections.unmodifiableMap(m);
    }
}
