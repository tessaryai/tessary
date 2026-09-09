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
 * The project-scoped usage read surface — self-serve "how much has my project consumed over
 * time," consumed by per-project dashboards and programmatic API clients. Like the query API
 * ({@code QueryController}) and the OTLP front door, it lives on a token-scoped {@code /v1/usage/*} path
 * outside {@code /api/**} so {@code AuthFilter} doesn't hard-401 it; the controller enforces the
 * project-scoped token itself and every read is scoped to that token's project — the request never
 * carries a project id.
 *
 * <p>Org-level / cross-project billing reads are deliberately NOT here: they require {@code
 * Permission.BILLING_MANAGE} and live behind {@code BillingController}'s org-scoped path, so a project
 * token can never see another project's usage.
 */
@RestController
@RequestMapping("/v1/usage")
public class MeteringController {

    private final MeteringService service;

    public MeteringController(MeteringService service) {
        this.service = service;
    }

    /**
     * The per-bucket usage timeseries for one unit over {@code [from, to)} (both ISO-8601, both required), at
     * the requested {@code granularity} grain ({@code hour} default, or {@code day}; a {@code
     * 422} on any other grain). {@code unit} is one of {@code ingested_spans|l1_evals|l2_evals|
     * llm_tokens|storage} (a {@code 422} otherwise; {@code storage} returns empty unless the deployment has
     * opted into producing it via {@code tessary.metering.storage-enabled}). The two eval units were spelled
     * {@code signal_evals} and {@code grader_runs} before the 2026-08 cleanup release; a client pinned to
     * those names gets the {@code 422}, not an empty series.
     *
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

    /** The project-scoped token requirement, identical to the OTLP/query ingest front doors. */
    private static String projectId(TenantContext ctx) {
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            throw new TessaryException(MeteringError.TOKEN_REQUIRED);
        }
        return projectId;
    }
}
