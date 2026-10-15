// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FakeFindingAdapter;
import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link RcaTriggerService}: the report kind follows the classifier that filed the finding, and a report that
 * names causes reads its header as the finding's rate against the rate its call site learned.
 */
class RcaTriggerServiceTest {

    private final FindingRepository findings = mock(FindingRepository.class);
    private final RcaJobRepository jobs = mock(RcaJobRepository.class);
    private final RcaReportRepository reports = mock(RcaReportRepository.class);
    private final RcaReportService reportReads = mock(RcaReportService.class);
    private final RcaTriggerService trigger =
            new RcaTriggerService(findings, jobs, reports, reportReads, FindingAdapters.of(List.of()));

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
        jobIs("job_1");

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

    /**
     * A finding an adapter owns gets the owned-causes report, whatever its classifier key would give. The bug is an
     * owned finding's report filed as a metric movement, whose header reads a severity nobody asserted.
     */
    @Test
    void aFindingAnAdapterOwnsGetsTheOwnedCausesKind() {
        ownedFindingTriggered();

        verify(reports)
                .insertPendingIfAbsent(
                        eq("proj"),
                        eq("job_1"),
                        eq("fnd_o"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("owned-x"),
                        eq(RcaReportRow.ReportKind.OWNED_CAUSES),
                        any(Instant.class),
                        any(Instant.class),
                        any(Instant.class),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyString());
    }

    /**
     * An owned-causes report's header reads the finding's current and baseline rates off its payload, as a
     * frustration report's does. The bug is a header of the asserted severity over zero.
     */
    @Test
    void theOwnedCausesHeaderReadsTheRatesOffThePayload() {
        ownedFindingTriggered();

        verify(reports)
                .insertPendingIfAbsent(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Instant.class),
                        any(Instant.class),
                        any(Instant.class),
                        eq(0.09),
                        eq(0.02),
                        eq(0.09 - 0.02),
                        anyString());
    }

    private void jobIs(String jobId) {
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
                .thenReturn(jobId);
    }

    /** Triggers RCA on an {@code owned-x} finding that asserted a 0.9 severity, with an adapter that owns the key. */
    private void ownedFindingTriggered() {
        when(findings.findClaim("proj", "fnd_o"))
                .thenReturn(Optional.of(new FindingClaim(
                        "fnd_o",
                        "proj",
                        "owned-x",
                        "clf_o:support.answer",
                        "classifier",
                        "clf_o",
                        "Owned",
                        "support.answer",
                        "2026-05-04T00:00:00Z",
                        "2026-05-05T00:00:00Z",
                        null,
                        null,
                        0.9,
                        40,
                        "{\"cause_kind\":\"owned_rate\",\"baseline_rate\":0.02,\"current_rate\":0.09}",
                        null,
                        "2026-05-04T01:00:00Z")));
        jobIs("job_1");

        new RcaTriggerService(
                        findings,
                        jobs,
                        reports,
                        reportReads,
                        FindingAdapters.of(List.of(FakeFindingAdapter.owning("owned-x", null, null, null))))
                .trigger("proj", "fnd_o", "user_1", null);
    }

    /**
     * Catches a finding row with an unreadable timestamp refusing the analysis: the window is a rendering
     * detail, so each end falls back to a sibling column and then to now. Also pins the metric-movement header
     * for a finding that asserted no severity and has no subject label: 0 over 0, labelled by the subject id.
     */
    @Test
    void anUnreadableWindowFallsBackToASiblingColumnThenToNow() {
        when(findings.findClaim("proj", "fnd_2"))
                .thenReturn(Optional.of(toolErrorClaim("fnd_2", "2026-05-04T00:00:00Z", "not-a-time", "not-a-time")));
        when(findings.findClaim("proj", "fnd_3")).thenReturn(Optional.of(toolErrorClaim("fnd_3", "x", "y", "z")));
        jobIs("job_2");

        Instant before = Instant.now();
        trigger.trigger("proj", "fnd_2", "user_1", null);
        trigger.trigger("proj", "fnd_3", "user_1", null);
        Instant after = Instant.now();

        Instant onset = Instant.parse("2026-05-04T00:00:00Z");
        verify(reports)
                .insertPendingIfAbsent(
                        eq("proj"),
                        eq("job_2"),
                        eq("fnd_2"),
                        eq("classifier"),
                        eq("clf_t"),
                        eq("clf_t"),
                        isNull(),
                        eq(BuiltInDetector.Kind.TOOL_ERROR),
                        eq(RcaReportRow.ReportKind.METRIC_MOVEMENT),
                        eq(onset),
                        eq(onset),
                        eq(onset),
                        eq(0.0),
                        eq(0.0),
                        eq(0.0),
                        eq(RcaReportRow.Engine.AGENTIC));
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(reports)
                .insertPendingIfAbsent(
                        eq("proj"),
                        eq("job_2"),
                        eq("fnd_3"),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        anyString(),
                        anyString(),
                        from.capture(),
                        any(Instant.class),
                        to.capture(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyString());
        for (Instant end : List.of(from.getValue(), to.getValue())) {
            assertTrue(!end.isBefore(before) && !end.isAfter(after), end + " is the trigger's now");
        }
    }

    /** A tool-error finding with no severity and no subject label, and the three window columns given. */
    private static FindingClaim toolErrorClaim(String id, String onsetAt, String lastSeenAt, String createdAt) {
        return new FindingClaim(
                id,
                "proj",
                BuiltInDetector.Kind.TOOL_ERROR,
                "search",
                "classifier",
                "clf_t",
                null,
                null,
                onsetAt,
                lastSeenAt,
                null,
                null,
                null,
                3,
                null,
                null,
                createdAt);
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
