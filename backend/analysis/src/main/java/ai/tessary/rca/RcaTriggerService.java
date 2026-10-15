// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.rca.RcaDtos.RcaReportView;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enqueue one RCA. The finding id is the whole input; everything else is read off the finding row, never from the
 * caller (see {@link RcaAnalysisService} on the firewall).
 */
@Service
public class RcaTriggerService {

    private static final Logger log = LoggerFactory.getLogger(RcaTriggerService.class);

    private final FindingRepository findings;
    private final RcaJobRepository jobs;
    private final RcaReportRepository reports;
    private final RcaReportService reportReads;
    private final FindingAdapters adapters;

    public RcaTriggerService(
            FindingRepository findings,
            RcaJobRepository jobs,
            RcaReportRepository reports,
            RcaReportService reportReads,
            FindingAdapters adapters) {
        this.findings = findings;
        this.jobs = jobs;
        this.reports = reports;
        this.reportReads = reportReads;
        this.adapters = adapters;
    }

    /** {@code runNonce} is null for a first trigger (re-presses coalesce) and fresh for an explicit re-run. */
    public RcaReportView trigger(String projectId, String findingId, String userId, @Nullable String runNonce) {
        FindingClaim finding = findings.findClaim(projectId, findingId).orElseThrow(() -> {
            // GlobalExceptionHandler logs 4xx at DEBUG, invisible in prod. Codes only: bounded and PII-free.
            log.info(Markers.OPS, "rca rejected project={} code=SUBJECT_NOT_FOUND", projectId);
            return new TessaryException(RcaError.SUBJECT_NOT_FOUND, findingId);
        });

        // Trigger-time truth for rendering, not a query window.
        Instant from = parse(finding.createdAt(), finding.onsetAt());
        Instant split = parse(finding.onsetAt(), finding.createdAt());
        Instant to = parse(finding.lastSeenAt(), finding.onsetAt());
        String reportKind = adapters.ownerOf(finding).isPresent()
                ? RcaReportRow.ReportKind.OWNED_CAUSES
                : RcaReportRow.ReportKind.forClassifier(finding.classifierKey());
        double current;
        double prior;
        if (RcaReportRow.ReportKind.namesCauses(reportKind)) {
            // A frustration, groundedness or adapter-owned finding measured a rate against a reference, so the
            // header reads as that rate: the flagged share since onset over the reference rate.
            current = finding.payloadNumber("current_rate");
            prior = finding.payloadNumber("baseline_rate");
        } else {
            // No "before" number exists; inventing one would show a movement nobody measured.
            Double asserted = finding.severity();
            current = asserted == null ? 0.0 : asserted;
            prior = 0.0;
        }
        String label = finding.subjectLabel() == null ? finding.subjectId() : finding.subjectLabel();

        String jobId = jobs.createOrGet(
                projectId,
                finding.id(),
                finding.subjectKind(),
                finding.subjectId(),
                finding.classifierKey(),
                from,
                split,
                to,
                userId,
                runNonce);
        reports.insertPendingIfAbsent(
                projectId,
                jobId,
                finding.id(),
                finding.subjectKind(),
                finding.subjectId(),
                label,
                finding.callSiteId(),
                finding.classifierKey(),
                reportKind,
                from,
                split,
                to,
                current,
                prior,
                current - prior,
                RcaReportRow.Engine.AGENTIC);
        return reportReads.getByJobId(projectId, jobId);
    }

    /** Falls back to a sibling column, then now: a malformed window must not refuse the analysis. */
    private static Instant parse(String value, String fallback) {
        for (String candidate : new String[] {value, fallback}) {
            try {
                return Instant.parse(candidate);
            } catch (RuntimeException ignored) {
            }
        }
        return Instant.now();
    }
}
