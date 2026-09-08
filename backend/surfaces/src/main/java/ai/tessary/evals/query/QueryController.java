// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.query;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.QueryError;
import ai.tessary.evals.query.QueryDtos.CountRequest;
import ai.tessary.evals.query.QueryDtos.CountView;
import ai.tessary.evals.query.QueryDtos.FacetsRequest;
import ai.tessary.evals.query.QueryDtos.FacetsView;
import ai.tessary.evals.query.QueryDtos.SearchRequest;
import ai.tessary.evals.query.QueryDtos.SearchView;
import ai.tessary.evals.query.QueryDtos.TimeseriesRequest;
import ai.tessary.evals.query.QueryDtos.TimeseriesView;
import ai.tessary.evals.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The aggregation-first query API — a stable, token-scoped read surface over the trace + signal
 * store: {@code count} / {@code timeseries} / {@code facets} / keyword {@code search}.
 *
 * <p><b>Auth + project resolution.</b> Like the OTLP front door ({@code OtlpTraceController}), this lives
 * on a token-scoped {@code /v1/query/*} path — outside {@code /api/**}, so {@code AuthFilter} doesn't
 * hard-401 it; the controller enforces the project-scoped token itself. The {@code tsy_} bearer
 * token resolves {@link TenantContext#projectId()} directly, so token-scoped callers need no org/project path
 * segments. Every read is scoped to that token's project — the request body never carries a project id.
 *
 * <p>Four POST endpoints, one validated request record each. Reuses the {@code ApiResponse<T>} envelope
 * and snake_case JSON, per the {@code ClassifierController} convention. All reads funnel through
 * {@link QueryService} → {@link QueryRepository}; no caller above the repository touches a table.
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

    /** The project-scoped token requirement, identical to the OTLP ingest front door. */
    private static String projectId(TenantContext ctx) {
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            throw new EvalsException(QueryError.TOKEN_REQUIRED);
        }
        // Least-privilege key family: a query or mcp key may read; a write-only key may not.
        if (!ctx.keyPermits(ai.tessary.evals.tenant.KeyScope.QUERY)) {
            throw new EvalsException(QueryError.WRONG_KEY_SCOPE);
        }
        return projectId;
    }
}
