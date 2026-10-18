// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalog;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryResult;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate reads over a project's traces, LLM calls, tool calls and cases, for the Metrics page, the Triage strip and
 * query-scoped API keys alike. Read-only: nothing here writes or escalates.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/metrics")
public class MetricsController {

    private final MetricsQueryService service;
    private final TenantPathResolver resolver;

    public MetricsController(MetricsQueryService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping("/catalog")
    public ApiResponse<MetricsCatalog> catalog(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        MetricsAccess.read(resolver, ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.catalog());
    }

    @PostMapping("/query")
    public ApiResponse<MetricsQueryResult> query(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody MetricsQueryRequest req) {
        Resolved r = MetricsAccess.read(resolver, ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.query(r.project().id(), req));
    }
}
