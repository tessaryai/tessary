// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.CountView;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.FacetsView;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.SearchView;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import ai.tessary.query.QueryDtos.TimeseriesView;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token-scoped aggregation query API ({@code count}, {@code timeseries}, {@code facets}, {@code search}). On
 * {@code /v1/query/*}, outside {@code /api/**}, so like {@code OtlpTraceController} it enforces the project-scoped
 * token itself; the request never carries a project id.
 */
@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final QueryService service;

    public QueryController(QueryService service) {
        this.service = service;
    }

    @PostMapping("/count")
    public ApiResponse<CountView> count(TenantContext ctx, @Valid @RequestBody CountRequest req) {
        return ApiResponse.ok(CountView.of(service.count(projectId(ctx), req)));
    }

    @PostMapping("/timeseries")
    public ApiResponse<TimeseriesView> timeseries(TenantContext ctx, @Valid @RequestBody TimeseriesRequest req) {
        return ApiResponse.ok(TimeseriesView.of(service.timeseries(projectId(ctx), req)));
    }

    @PostMapping("/facets")
    public ApiResponse<FacetsView> facets(TenantContext ctx, @Valid @RequestBody FacetsRequest req) {
        return ApiResponse.ok(FacetsView.of(req.field(), service.facets(projectId(ctx), req)));
    }

    @PostMapping("/search")
    public ApiResponse<SearchView> search(TenantContext ctx, @Valid @RequestBody SearchRequest req) {
        return ApiResponse.ok(SearchView.of(service.search(projectId(ctx), req)));
    }

    /** Same requirement as the OTLP ingest front door. */
    private static String projectId(TenantContext ctx) {
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            throw new TessaryException(QueryError.TOKEN_REQUIRED);
        }
        // A query or mcp key may read; a write-only key may not.
        if (!ctx.keyPermits(ai.tessary.tenant.KeyScope.QUERY)) {
            throw new TessaryException(QueryError.WRONG_KEY_SCOPE);
        }
        return projectId;
    }
}
