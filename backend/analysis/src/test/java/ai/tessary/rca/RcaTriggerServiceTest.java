// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link RcaTriggerService}: the report kind follows the classifier that filed the finding, and a report that
 * names causes reads its header as the finding's rate against the rate its call site learned.
 */
class RcaTriggerServiceTest {

    private final FindingRepository findings = mock(FindingRepository.class);
    private final RcaJobRepository jobs = mock(RcaJobRepository.class);
    private final RcaReportRepository reports = mock(RcaReportRepository.class);
    private final RcaReportService reportReads = mock(RcaReportService.class);
    private final RcaTriggerService trigger = new RcaTriggerService(findings, jobs, reports, reportReads);

    @Test
    void eachClassifierGetsItsReportKind() {
        assertEquals(
                RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES,
                RcaReportRow.ReportKind.forClassifier(BuiltInDetector.Kind.GROUNDEDNESS));
        assertEquals(
                RcaReportRow.ReportKind.FRUSTRATION_CAUSES,
                RcaReportRow.ReportKind.forClassifier(BuiltInDetector.Kind.FRUSTRATION));
        assertEquals(
                RcaReportRow.ReportKind.METRIC_MOVEMENT,
                RcaReportRow.ReportKind.forClassifier(BuiltInDetector.Kind.TOOL_ERROR));
    }

    @Test
    void aGroundednessReportReadsTheFlaggedRateAgainstTheLearnedOne() {
        when(findings.findClaim("proj", "fnd_1")).thenReturn(Optional.of(claim()));
        when(jobs.createOrGet(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Instant.class),
                        any(Instant.class),
                        any(Instant.class),
                        any(),
                        isNull()))
                .thenReturn("job_1");

        trigger.trigger("proj", "fnd_1", "user_1", null);

        verify(reports)
                .insertPendingIfAbsent(
                        eq("proj"),
                        eq("job_1"),
                        eq("fnd_1"),
                        eq("classifier"),
                        eq("clf_g"),
                        eq("Groundedness"),
                        eq("support.answer"),
                        eq(BuiltInDetector.Kind.GROUNDEDNESS),
                        eq(RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES),
                        any(Instant.class),
                        any(Instant.class),
                        any(Instant.class),
                        eq(0.064),
                        eq(0.021),
                        anyDouble(),
                        eq(RcaReportRow.Engine.AGENTIC));
    }

    private static FindingClaim claim() {
        return new FindingClaim(
                "fnd_1",
                "proj",
                BuiltInDetector.Kind.GROUNDEDNESS,
                "support.answer",
                "classifier",
                "clf_g",
                "Groundedness",
                "support.answer",
                "2026-05-04T00:00:00Z",
                "2026-05-05T00:00:00Z",
                null,
                null,
                0.9,
                72,
                "{\"cause_kind\":\"groundedness_rate\",\"baseline_rate\":0.021,\"current_rate\":0.064}",
                null,
                "2026-05-04T01:00:00Z");
    }
}
