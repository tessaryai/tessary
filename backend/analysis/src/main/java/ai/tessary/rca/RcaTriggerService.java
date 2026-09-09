// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

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
 * Enqueue one RCA. <b>The finding id is the whole input</b> — everything the job and its pending report
 * carry is read off the finding row here, never accepted from the caller.
 *
 * <p>Its own service because the press lives on the CASE surface now ({@code POST
 * /cases/{id}/rca}): a person reads the case, including everything triage wrote on it, and decides. The
 * decision is a human one and the id is all of it that crosses — see {@link RcaAnalysisService} on the
 * firewall that makes that the load-bearing sentence.
 */
@Service
public class RcaTriggerService {

    private static final Logger log = LoggerFactory.getLogger(RcaTriggerService.class);

    private final FindingRepository findings;
    private final RcaJobRepository jobs;
    private final RcaReportRepository reports;
    private final RcaReportService reportReads;

    public RcaTriggerService(
            FindingRepository findings,
            RcaJobRepository jobs,
            RcaReportRepository reports,
            RcaReportService reportReads) {
        this.findings = findings;
        this.jobs = jobs;
        this.reports = reports;
        this.reportReads = reportReads;
    }

    /**
     * Snapshot the finding into a job + pending report and return that report. {@code runNonce} is null
     * for a first trigger (re-presses coalesce onto the one report for the finding) and a fresh id for an
     * explicit re-run (which must get its own analysis).
     */
    public RcaReportView trigger(
            String projectId, String findingId, @Nullable String userId, @Nullable String runNonce) {
        FindingClaim finding = findings.findClaim(projectId, findingId).orElseThrow(() -> {
            // A 4xx ends the press with a log line GlobalExceptionHandler writes at DEBUG — invisible in
            // prod. Without this, "Run RCA does nothing" leaves no server-side trace at all. Codes only:
            // bounded, PII-free, countable (backend/AGENTS.md § Logging).
            log.info(Markers.OPS, "rca rejected project={} code=SUBJECT_NOT_FOUND", projectId);
            return new TessaryException(RcaError.SUBJECT_NOT_FOUND, findingId);
        });

        // The window columns describe the finding's own span: created → onset → last seen. They are
        // trigger-time truth for the report to render, not a query window — the analysis reads the
        // finding's evidence rows, which are not time-sliced.
        Instant from = parse(finding.createdAt(), finding.onsetAt());
        Instant split = parse(finding.onsetAt(), finding.createdAt());
        Instant to = parse(finding.lastSeenAt(), finding.onsetAt());
        // The severity is what the classifier asserted about the cause; there is no "before" number to
        // compare it against, and inventing one would put a movement on the page that nobody measured.
        Double asserted = finding.severity();
        double severity = asserted == null ? 0.0 : asserted;
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
                from,
                split,
                to,
                severity,
                0.0,
                severity,
                RcaReportRow.Engine.AGENTIC);
        return reportReads.getByJobId(projectId, jobId);
    }

    /** ISO-8601 text off a finding row, falling back to a sibling column and finally to now — a report
     *  window is a rendering detail, and a malformed one must not refuse the analysis. */
    private static Instant parse(String value, String fallback) {
        for (String candidate : new String[] {value, fallback}) {
            try {
                return Instant.parse(candidate);
            } catch (RuntimeException ignored) {
                // try the next one
            }
        }
        return Instant.now();
    }
}
