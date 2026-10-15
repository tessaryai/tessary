// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.plan.Capability;
import ai.tessary.plan.RequiresCapability;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.tenant.Ids;
import ai.tessary.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads RCA reports and re-runs finished ones; execution is {@link RcaWorker}. There is deliberately no trigger: a
 * first RCA is pressed on the case ({@code POST /cases/{id}/rca}), which keeps the finding id the only thing that
 * crosses into the lane.
 */
@RequiresCapability(Capability.RCA)
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/rca")
public class RcaController {

    private static final int DEFAULT_LIMIT = 50;

    private final RcaReportRepository reports;
    private final RcaReportService reportReads;
    private final RcaTriggerService trigger;
    private final TenantPathResolver resolver;

    public RcaController(
            RcaReportRepository reports,
            RcaReportService reportReads,
            RcaTriggerService trigger,
            TenantPathResolver resolver) {
        this.reports = reports;
        this.reportReads = reportReads;
        this.trigger = trigger;
        this.resolver = resolver;
    }

    /**
     * Snapshots the finding again into a fresh report rather than rewriting the immutable one. Opts out of the
     * per-finding coalesce, which would swallow a re-run; while the first analysis is still queued or running this
     * returns the running report.
     */
    @PostMapping("/{id}/rerun")
    public ApiResponse<RcaReportView> rerun(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        String projectId = r.project().id();
        RcaReportView existing = reportReads.get(projectId, id);
        if (JobRow.Status.PENDING.equals(existing.status()) || JobRow.Status.CLAIMED.equals(existing.status())) {
            return ApiResponse.ok(existing);
        }
        String findingId = reports.findingIdOf(projectId, existing.jobId())
                .orElseThrow(() -> new TessaryException(RcaError.SUBJECT_NOT_FOUND, existing.subjectId()));
        return ApiResponse.ok(trigger.trigger(projectId, findingId, ctx.userId(), Ids.ulid()));
    }

    @GetMapping
    public ApiResponse<List<RcaReportView>> list(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(reportReads.list(r.project().id(), limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<RcaReportView> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(reportReads.get(r.project().id(), id));
    }
}
