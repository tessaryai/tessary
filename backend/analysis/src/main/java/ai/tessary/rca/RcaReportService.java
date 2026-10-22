// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.agentrun.AgentRunDtos.RunStepsView;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaDtos.RcaRunView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Renders {@link RcaReportRepository} rows as {@link RcaReportView} for every reader, so a report reads the same
 * through any door and project scoping lives in one place. Every view carries where its analysis stands.
 */
@Service
public class RcaReportService {

    private final RcaReportRepository reports;
    private final AgentRunRepository runs;
    private final ObjectMapper mapper;

    public RcaReportService(RcaReportRepository reports, AgentRunRepository runs, ObjectMapper mapper) {
        this.reports = reports;
        this.runs = runs;
        this.mapper = mapper;
    }

    /** Throws {@code COMMON.NOT_FOUND} when absent. */
    public RcaReportView get(String projectId, String id) {
        return reports.findById(projectId, id)
                .map(row -> view(projectId, row))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, id));
    }

    public RcaReportView view(String projectId, RcaReportRow row) {
        return RcaReportView.of(row, mapper, run(projectId, row.id()));
    }

    public RcaReportView getByJobId(String projectId, String jobId) {
        return reports.findByJobId(projectId, jobId)
                .map(row -> view(projectId, row))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, jobId));
    }

    /**
     * The steps of the report's newest run after step {@code afterSeq}; a report outside the project, or one no run has
     * started for, is not found.
     */
    public RunStepsView steps(String projectId, String reportId, int afterSeq) {
        if (afterSeq < 0) {
            throw new TessaryException(CommonError.VALIDATION_FAILED, "after must be zero or more");
        }
        if (reports.findById(projectId, reportId).isEmpty()) {
            throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, reportId);
        }
        AgentRunRepository.RunSummary run = runs.latestBySubjects(
                        projectId, AgentRunRepository.Lane.RCA, List.of(reportId))
                .get(reportId);
        if (run == null) throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, reportId);
        return RunStepsView.of(runs.steps(projectId, run.id(), afterSeq));
    }

    private @Nullable RcaRunView run(String projectId, String reportId) {
        return reports.runState(projectId, reportId).map(RcaRunView::of).orElse(null);
    }
}
