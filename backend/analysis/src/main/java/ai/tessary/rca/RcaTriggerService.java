// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.Pair;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.rca.RcaDtos.RcaReportView;
import java.time.Duration;
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

        // Trigger-time truth for rendering, not a query window. Ordered from <= split <= to: created_at is the sweep's
        // wall clock, which on a backfill is after the onset, so it is never the start.
        Instant split = parse(finding.onsetAt(), finding.createdAt());
        Instant to = max(parse(finding.lastSeenAt(), finding.onsetAt()), split);
        Instant referenceFrom = parseOrNull(finding.payloadText("reference_from"));
        Instant from = referenceFrom != null
                ? referenceFrom
                : split.minus(max(Duration.ofHours(24), Duration.between(split, to)));
        if (from.isAfter(split)) from = split;
        String reportKind = adapters.ownerOf(finding).isPresent()
                ? RcaReportRow.ReportKind.OWNED_CAUSES
                : RcaReportRow.ReportKind.forClassifier(finding.classifierKey());
        double current;
        double prior;
        Pair mean = null;
        if (FindingRow.Cause.DISTRIBUTION_SHIFT.equals(finding.causeKind())) {
            ShiftDetail detail = MetricFindingEvidence.detail(finding.payloadJson());
            mean = detail == null ? null : detail.mean();
        }
        Double meanNow = mean == null ? null : mean.now();
        Double meanThen = mean == null ? null : mean.then();
        if (RcaReportRow.ReportKind.namesCauses(reportKind)) {
            // A frustration, groundedness or adapter-owned finding measured a rate against a reference, so the
            // header reads as that rate: the flagged share since onset over the reference rate.
            current = finding.payloadNumber("current_rate");
            prior = finding.payloadNumber("baseline_rate");
        } else if (meanNow != null && meanThen != null) {
            // A drift finding reads as its mean then and now, in the measure's own units. One written before the
            // payload carried both falls through to its severity.
            current = meanNow;
            prior = meanThen;
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

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static @Nullable Instant parseOrNull(@Nullable String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
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
