// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaReportView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * The read side of the signal-RCA surface: rows out of {@link RcaReportRepository} rendered as the
 * wire {@link RcaReportView}. One seam for every reader — {@link RcaController}, {@code CaseService}
 * and {@link RcaTriggerService} — so a report reads identically whichever door it comes through, and
 * project scoping is applied in exactly one place.
 */
@Service
public class RcaReportService {

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

    /** A row the caller already holds, rendered as the same view {@link #get} returns. */
    public RcaReportView view(RcaReportRow row) {
        return RcaReportView.of(row, mapper);
    }

    /** The report for a job, if the job has one. */
    public RcaReportView getByJobId(String projectId, String jobId) {
        return reports.findByJobId(projectId, jobId)
                .map(row -> RcaReportView.of(row, mapper))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, jobId));
    }
}
