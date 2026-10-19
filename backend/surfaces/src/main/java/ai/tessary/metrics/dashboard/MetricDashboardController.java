// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.metrics.MetricsAccess;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardCreateRequest;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardList;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardUpdateRequest;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Metrics dashboards. Reads take a session or a query key; writes take a signed-in session only. */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/metrics/dashboards")
public class MetricDashboardController {

    private final MetricDashboardService service;
    private final TenantPathResolver resolver;

    public MetricDashboardController(MetricDashboardService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<MetricDashboardList> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = MetricsAccess.read(resolver, ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.list(r.project().id(), MetricsAccess.canManage(r, ctx)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MetricDashboard> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        Resolved r = MetricsAccess.read(resolver, ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.get(r.project().id(), id, MetricsAccess.canManage(r, ctx)));
    }

    @PostMapping
    public ApiResponse<MetricDashboard> create(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody MetricDashboardCreateRequest req) {
        Resolved r = MetricsAccess.write(resolver, ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.create(r.project().id(), ctx.userId(), req, MetricsAccess.canManage(r, ctx)));
    }

    @PutMapping("/{id}")
    public ApiResponse<MetricDashboard> update(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody MetricDashboardUpdateRequest req) {
        Resolved r = MetricsAccess.write(resolver, ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.update(r.project().id(), id, req, MetricsAccess.canManage(r, ctx)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        Resolved r = MetricsAccess.write(resolver, ctx, orgSlug, projectSlug);
        service.delete(r.project().id(), id);
        return ApiResponse.ok(null);
    }
}
