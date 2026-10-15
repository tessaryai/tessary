// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.auth.TenantContext;
import ai.tessary.metering.MeteringDtos.UsageTimeseriesView;
import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.web.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-scoped usage reads on {@code /v1/usage/*}, outside {@code /api/**}, so this controller enforces the project
 * token itself; the request never carries a project id. Org-level reads need {@code Permission.BILLING_MANAGE} and live
 * in {@code BillingController}.
 */
@RestController
@RequestMapping("/v1/usage")
public class MeteringController {

    private final MeteringService service;

    public MeteringController(MeteringService service) {
        this.service = service;
    }

    /**
     * Both bounds required. {@code granularity} is {@code hour} (default) or {@code day}; {@code unit} is
     * {@code ingested_spans|ingested_traces|l1_evals}; anything else is 422.
     */
    @GetMapping("/timeseries")
    public ApiResponse<UsageTimeseriesView> timeseries(
            TenantContext ctx,
            @RequestParam String unit,
            @RequestParam(name = "granularity", required = false) @Nullable String granularity,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        String projectId = projectId(ctx);
        return ApiResponse.ok(
                UsageTimeseriesView.of(unit, service.projectTimeseries(projectId, unit, granularity, from, to)));
    }

    private static String projectId(TenantContext ctx) {
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            throw new TessaryException(MeteringError.TOKEN_REQUIRED);
        }
        return projectId;
    }
}
