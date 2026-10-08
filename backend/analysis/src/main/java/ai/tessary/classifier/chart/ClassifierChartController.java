// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartScopesView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartsView;
import ai.tessary.web.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Classifiers page charts: the selectors and menu, and the cards of one call site or one tool. Read only. */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/classifiers")
public class ClassifierChartController {

    private final ClassifierChartService service;
    private final TenantPathResolver resolver;

    public ClassifierChartController(ClassifierChartService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    /** Every call site and tool the selectors offer, and every classifier the Configure menu lists. 7, 28 or 90 days. */
    @GetMapping("/chart-scopes")
    public ApiResponse<ChartScopesView> scopes(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "days", defaultValue = "28") int days) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.scopes(r.project().id(), days));
    }

    /**
     * The cards and chips of one call site ({@code callSiteId}) or one tool ({@code tool}, a Tool Errors key such
     * as {@code tool:search_orders}); exactly one of the two. 7, 28 or 90 days, each a UTC date, the last one today.
     */
    @GetMapping("/charts")
    public ApiResponse<ChartsView> charts(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "callSiteId", required = false) @Nullable String callSiteId,
            @RequestParam(name = "tool", required = false) @Nullable String tool,
            @RequestParam(name = "days", defaultValue = "28") int days) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.charts(r.project().id(), callSiteId, tool, days));
    }
}
