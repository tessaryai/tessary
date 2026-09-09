// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaReportView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The read side of the signal-RCA surface: rows out of {@link RcaReportRepository} rendered as the
 * wire {@link RcaReportView}. One seam for both readers — {@link RcaController} (the web UI) and
 * {@code McpToolRegistry} (connected agents) — so a report reads identically whichever door it comes
 * through, and project scoping is applied in exactly one place.
 */
@Service
public class RcaReportService {

    /** Bound on {@link #list} so a caller (or an agent) can never ask for an unbounded scan. */
    public static final int MAX_LIMIT = 200;

    private final RcaReportRepository reports;
    private final ObjectMapper mapper;

    public RcaReportService(RcaReportRepository reports, ObjectMapper mapper) {
        this.reports = reports;
        this.mapper = mapper;
    }

    /** One report by id, scoped to the project. Throws {@code COMMON.NOT_FOUND} when it isn't there. */
    public RcaReportView get(String projectId, String id) {
        return reports.findById(projectId, id)
                .map(row -> RcaReportView.of(row, mapper))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, id));
    }

    /** The report for a job, if the job has one. */
    public RcaReportView getByJobId(String projectId, String jobId) {
        return reports.findByJobId(projectId, jobId)
                .map(row -> RcaReportView.of(row, mapper))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, jobId));
    }

    /**
     * The project's reports, newest first. The optional subject filters narrow to one mover's history
     * — pass all three to get "every RCA ever run on this grader's pass rate", newest first.
     */
    public List<RcaReportView> list(
            String projectId,
            int limit,
            @Nullable String subjectKind,
            @Nullable String subjectId,
            @Nullable String metric,
            @Nullable String status) {
        return reports
                .listByProject(projectId, Math.clamp(limit, 1, MAX_LIMIT), subjectKind, subjectId, metric, status)
                .stream()
                .map(row -> RcaReportView.of(row, mapper))
                .toList();
    }
}
