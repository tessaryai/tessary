// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaReportView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Renders {@link RcaReportRepository} rows as {@link RcaReportView} for every reader, so a report reads the same
 * through any door and project scoping lives in one place.
 */
@Service
public class RcaReportService {

    private final RcaReportRepository reports;
    private final ObjectMapper mapper;

    public RcaReportService(RcaReportRepository reports, ObjectMapper mapper) {
        this.reports = reports;
        this.mapper = mapper;
    }

    /** Throws {@code COMMON.NOT_FOUND} when absent. */
    public RcaReportView get(String projectId, String id) {
        return reports.findById(projectId, id)
                .map(row -> RcaReportView.of(row, mapper))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, id));
    }

    public RcaReportView view(RcaReportRow row) {
        return RcaReportView.of(row, mapper);
    }

    public RcaReportView getByJobId(String projectId, String jobId) {
        return reports.findByJobId(projectId, jobId)
                .map(row -> RcaReportView.of(row, mapper))
                .orElseThrow(() -> new TessaryException(CommonError.NOT_FOUND, jobId));
    }
}
