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
 * The single validation seam for the query API: wire strings resolve against {@link QueryDataset} /
 * {@link QueryInterval} allow-lists, so a bad name is a 400 before any SQL. {@code projectId} always comes from the
 * token, never the body.
 */
@Service
public class QueryService {

    private final QueryRepository repository;

    public QueryService(QueryRepository repository) {
        this.repository = repository;
    }

    public long count(String projectId, CountRequest req) {
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        var scope = scope(dataset, projectId, req.range(), req.filters());
        return repository.count(scope);
    }

    /** Requires both range bounds: an unbounded timeseries is a full scan. */
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

    public List<QueryRepository.Facet> facets(String projectId, FacetsRequest req) {
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        String column = dataset.facetColumn(req.field());
        var scope = scope(dataset, projectId, req.range(), req.filters());
        Integer requestedTopN = req.topN();
        int topN = requestedTopN == null ? QueryRepository.DEFAULT_FACET_TOP_N : requestedTopN;
        return repository.facets(scope, column, topN);
    }

    /**
     * Only {@code keyword} is supported; {@code semantic} is a 400 rather than silently coerced. On {@code spans} it
     * matches {@code name} and the stored previews only; full-text search belongs to global search.
     */
    public QueryRepository.SearchPage search(String projectId, SearchRequest req) {
        String reqMode = req.mode();
        String mode = reqMode == null || reqMode.isBlank() ? "keyword" : reqMode;
        QueryDataset dataset = QueryDataset.fromWire(req.dataset());
        // Pre-aggregated datasets have no text or row-level page; reject before the created_at keyset cursor runs
        // against another time column.
        if (!dataset.supportsSearch()) {
            throw new TessaryException(QueryError.SEARCH_UNSUPPORTED_FOR_DATASET, dataset.wireName());
        }
        var scope = scope(dataset, projectId, req.range(), req.filters());
        // Clamp to [1, MAX_SEARCH_LIMIT]: a negative limit would reach Postgres as LIMIT -1 (a 500) and a huge one
        // forces an unbounded scan.
        Integer reqLimit = req.limit();
        int requested = reqLimit == null ? QueryRepository.DEFAULT_SEARCH_LIMIT : reqLimit;
        int limit = Math.max(1, Math.min(requested, QueryRepository.MAX_SEARCH_LIMIT));
        if (!"keyword".equals(mode)) {
            throw new TessaryException(QueryError.UNKNOWN_SEARCH_MODE, mode);
        }
        return repository.search(
                scope, req.q(), dataset.searchColumns(), dataset.displayColumns(), limit, req.cursor());
    }

    private static QueryRepository.Scope scope(
            QueryDataset dataset, String projectId, @Nullable TimeRange range, @Nullable Map<String, String> filters) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (filters != null) {
            for (Map.Entry<String, String> f : filters.entrySet()) {
                resolved.put(dataset.filterColumn(f.getKey()), f.getValue());
            }
        }
        String from = range == null ? null : range.from();
        String to = range == null ? null : range.to();
        return new QueryRepository.Scope(dataset, projectId, from, to, Map.copyOf(resolved));
    }
}
