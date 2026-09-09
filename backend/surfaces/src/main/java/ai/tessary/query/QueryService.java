// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.TimeRange;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the aggregation-first query API: it resolves the opaque snake_case wire strings of
 * a request (dataset, facet field, interval, filter keys) against the {@link QueryDataset} /
 * {@link QueryInterval} allow-lists into trusted column identifiers, assembles a validated
 * {@link QueryRepository.Scope}, and delegates the SQL to {@link QueryRepository}. The controller never
 * touches the repository or the allow-lists directly — this service is the single validation seam, so a
 * bad dataset/field/interval is a {@code 400} before any SQL is built.
 *
 * <p>The {@code projectId} is always supplied by the controller from the project-scoped token (never
 * from the request body), so every read is tenant-scoped.
 */
@Service
public class QueryService {

    private final QueryRepository repository;

    public QueryService(QueryRepository repository) {
        this.repository = repository;
    }

    /** Total matching rows. */
    public long count(String projectId, CountRequest req) {
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        var scope = scope(dataset, projectId, req.range(), req.filters());
        return repository.count(scope);
    }

    /** Bucketed counts over time. Requires both range bounds — an unbounded timeseries is a full scan. */
    public List<QueryRepository.Bucket> timeseries(String projectId, TimeseriesRequest req) {
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        QueryInterval interval = QueryInterval.fromWire(req.interval());
        TimeRange range = req.range();
        if (range == null || range.from() == null || range.to() == null) {
            throw new TessaryException(QueryError.INVALID_RANGE);
        }
        var scope = scope(dataset, projectId, range, req.filters());
        return repository.timeseries(scope, interval);
    }

    /** Top-N breakdown of an allow-listed dimension. */
    public List<QueryRepository.Facet> facets(String projectId, FacetsRequest req) {
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        String column = dataset.facetColumn(req.field());
        var scope = scope(dataset, projectId, req.range(), req.filters());
        Integer requestedTopN = req.topN();
        int topN = requestedTopN == null ? QueryRepository.DEFAULT_FACET_TOP_N : requestedTopN;
        return repository.facets(scope, column, topN);
    }

    /**
     * Search returning a bounded page of rows. {@code keyword} is the only supported mode — a
     * keyset-paginated ILIKE scan. Any other {@code mode} (including the {@code semantic} value this API
     * used to accept before the vector substrate was removed, #1116) is a {@code 400 UNKNOWN_SEARCH_MODE}:
     * there is deliberately no semantic→keyword coercion, so a caller still asking for semantic search
     * gets a clear rejection rather than a silently different result set.
     *
     * <p>On {@code spans}, keyword mode matches {@code name} and the stored previews — the payload lives
     * off-row in {@code span_payload} and this surface stays a single-table read. A keyword search that
     * needs the full conversation text is the global-search surface's job (it holds the payload GIN
     * index); {@code get_span} is how the text itself is read.
     */
    public QueryRepository.SearchPage search(String projectId, SearchRequest req) {
        String reqMode = req.mode();
        String mode = reqMode == null || reqMode.isBlank() ? "keyword" : reqMode;
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        // Pre-aggregated datasets (metric_rollups) have no text columns and no row-level page; reject
        // search before the repository's created_at-keyed keyset cursor could run against a different time
        // column.
        if (!dataset.supportsSearch()) {
            throw new TessaryException(QueryError.SEARCH_UNSUPPORTED_FOR_DATASET, dataset.wireName());
        }
        var scope = scope(dataset, projectId, req.range(), req.filters());
        // Clamp to the server-advertised [1, MAX_SEARCH_LIMIT] page bound; repository.search re-clamps to
        // a harmless no-op, but the request's own limit is validated here regardless of mode. A negative
        // limit can't reach Postgres as LIMIT -1 (a raw 500) and a huge limit can't force an unbounded scan;
        // this matches the contract the query API advertises.
        Integer reqLimit = req.limit();
        int requested = reqLimit == null ? QueryRepository.DEFAULT_SEARCH_LIMIT : reqLimit;
        int limit = Math.max(1, Math.min(requested, QueryRepository.MAX_SEARCH_LIMIT));
        if (!"keyword".equals(mode)) {
            throw new TessaryException(QueryError.UNKNOWN_SEARCH_MODE, mode);
        }
        return repository.search(
                scope, req.q(), dataset.searchColumns(), dataset.displayColumns(), limit, req.cursor());
    }

    /** Resolve a request's range + filter keys into a validated, column-resolved scope. */
    private static QueryRepository.Scope scope(
            QueryDataset dataset, String projectId, @Nullable TimeRange range, @Nullable Map<String, String> filters) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (filters != null) {
            for (Map.Entry<String, String> f : filters.entrySet()) {
                // Resolve each filter field to a trusted column identifier (or 400) before it reaches SQL.
                resolved.put(dataset.filterColumn(f.getKey()), f.getValue());
            }
        }
        String from = range == null ? null : range.from();
        String to = range == null ? null : range.to();
        return new QueryRepository.Scope(dataset, projectId, from, to, Map.copyOf(resolved));
    }
}
