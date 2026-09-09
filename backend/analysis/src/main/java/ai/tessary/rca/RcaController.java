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
 * The RCA reading surface: one finding's report, and the re-run of a finished one. Execution is
 * {@link RcaWorker}.
 *
 * <p><b>There is no trigger here.</b> A first RCA is pressed on the CASE the finding opened
 * ({@code POST /cases/{id}/rca} — {@link ai.tessary.cases.CaseController}), because that is the
 * surface a person reads before deciding one is worth spending, and because routing the press through
 * the case is what keeps the finding id the only thing that crosses into the lane. This controller kept
 * a second door onto the same job for a while and it was the door that let a caller hand the lane
 * anything else it liked.
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
     * Re-run the analysis behind an existing report. The report is immutable, so this snapshots the
     * finding AGAIN — its current evidence, a fresh report to navigate to — rather than rewriting
     * history. Deliberately opts out of the per-finding coalesce, which is exactly what would otherwise
     * swallow a re-run; a re-run while the first analysis is still queued or running is a no-op that
     * hands back the running report.
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
        return ApiResponse.ok(reportReads.list(r.project().id(), limit, null, null, null, null));
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
