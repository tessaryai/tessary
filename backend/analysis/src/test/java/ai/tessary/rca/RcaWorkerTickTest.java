// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.Props;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The RCA worker's heartbeat and failure isolation with the queue and pipeline mocked; the integration suite parks
 * the drain, leaving the claim loop and failing bookkeeping writes to this class.
 */
class RcaWorkerTickTest {

    private static final RcaJobRow JOB =
            new RcaJobRow("job-1", "proj-1", "fnd-1", "finding", "fnd-1", "behavior_drift", "user-1");

    private final RcaJobRepository jobs = mock(RcaJobRepository.class);
    private final RcaReportRepository reports = mock(RcaReportRepository.class);
    private final RcaAnalysisService analysis = mock(RcaAnalysisService.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final CaseEventRepository caseEvents = mock(CaseEventRepository.class);
    private final RcaProperties props = Props.of(RcaProperties.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentSessionClient sessions = mock(AgentSessionClient.class);
    private final RcaWorker worker = new RcaWorker(
            jobs,
            reports,
            analysis,
            props,
            new TraceMdcBridge(Tracer.NOOP),
            findings,
            caseEvents,
            new SyncTaskExecutor(),
            runs,
            sessions);

    /**
     * The exhaustion sweep's outcome, success or failure, never gates the claim: due jobs are claimed round after
     * round until empty.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theQueueDrainsWhateverTheExhaustionSweepDid(boolean sweepThrows) {
        if (sweepThrows) {
            when(jobs.failExhausted(anyInt())).thenThrow(new IllegalStateException("statement timeout"));
        } else {
            when(jobs.failExhausted(anyInt())).thenReturn(2);
        }
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(JOB))
                .thenReturn(List.of());
        when(findings.findById("proj-1", "fnd-1")).thenReturn(Optional.empty());

        worker.tick();

        verify(analysis).analyze(JOB);
        verify(jobs).markDone("job-1");
        verify(jobs, times(2)).claimBatch(anyString(), anyInt(), anyLong(), anyInt());
    }

    /** A failing claim ends the tick rather than retrying in a tight loop. */
    @Test
    void aClaimThatFailsEndsTheTick() {
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("connection reset"));

        worker.tick();

        verify(jobs, times(1)).claimBatch(anyString(), anyInt(), anyLong(), anyInt());
        verifyNoInteractions(analysis);
    }

    /** A failed report stamp must not reach the executor; the job's failure stands. */
    @Test
    void aReportStampThatFailsDoesNotEscapeTheWorker() {
        doThrow(new IllegalStateException("launcher down")).when(analysis).analyze(JOB);
        doThrow(new IllegalStateException("db down"))
                .when(reports)
                .complete(
                        eq("job-1"),
                        eq(RcaJobRow.FAILED),
                        isNull(),
                        eq("launcher down"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());

        worker.run(JOB);

        verify(jobs).markFailed("job-1", "launcher down");
        verify(jobs, never()).markDone(anyString());
    }

    /** A finished run reaches its case, and a failed case-trail write does not turn it into a failed analysis. */
    @Test
    void aFinishedRunTellsItsCaseAndAFailedTrailLineDoesNotUndoIt() {
        when(findings.findById("proj-1", "fnd-1")).thenReturn(Optional.of(findingOnCase("case-1")));
        doThrow(new IllegalStateException("db down"))
                .when(caseEvents)
                .append(
                        eq("proj-1"),
                        eq("case-1"),
                        eq(CaseEventRow.Kind.RCA_COMPLETED),
                        isNull(),
                        eq("Finished — see the report."),
                        isNull(),
                        any(Instant.class));

        worker.run(JOB);

        verify(jobs).markDone("job-1");
        verify(caseEvents)
                .append(
                        eq("proj-1"),
                        eq("case-1"),
                        eq(CaseEventRow.Kind.RCA_COMPLETED),
                        isNull(),
                        anyString(),
                        isNull(),
                        any(Instant.class));
        verify(jobs, never()).markFailed(anyString(), any());
    }

    /**
     * An orphan reply that keeps spending: a reclaimed job stops the session its crashed attempt left open and marks
     * that run abandoned before it opens its own.
     */
    @Test
    void aReclaimedJobStopsTheSessionItsCrashedAttemptLeftOpen() {
        AgentRunRepository.RunSummary orphan = new AgentRunRepository.RunSummary(
                "run-old",
                AgentRunRepository.Lane.RCA,
                "report-1",
                1,
                "2026-10-20T10:00:00Z",
                null,
                null,
                null,
                4,
                "Reading commit a41c0de",
                null,
                null,
                null,
                null,
                "s-old");
        when(runs.openRunsOfJob("job-1")).thenReturn(List.of(orphan));
        when(findings.findById("proj-1", "fnd-1")).thenReturn(Optional.empty());

        worker.run(JOB);

        var order = inOrder(sessions, runs, analysis);
        order.verify(sessions).stop("s-old");
        order.verify(runs).close("run-old", AgentRunRepository.Outcome.ABANDONED, null);
        order.verify(analysis).analyze(JOB);
    }

    /**
     * A launcher that cannot take the stop of a crashed attempt's session has lost that session already: the orphan is
     * still closed and the analysis runs (a report stopped on the stop, and an open orphan read as the report's run).
     */
    @Test
    void aStopTheLauncherCannotTakeStillClosesTheOrphanAndRunsTheAnalysis() {
        AgentRunRepository.RunSummary orphan = new AgentRunRepository.RunSummary(
                "run-old",
                AgentRunRepository.Lane.RCA,
                "report-1",
                1,
                "2026-10-20T10:00:00Z",
                null,
                null,
                null,
                4,
                "Reading commit a41c0de",
                null,
                null,
                null,
                null,
                "s-old");
        when(runs.openRunsOfJob("job-1")).thenReturn(List.of(orphan));
        doThrow(new TessaryException(AgentRunError.LAUNCHER_UNAVAILABLE, "unreachable"))
                .when(sessions)
                .stop("s-old");
        when(findings.findById("proj-1", "fnd-1")).thenReturn(Optional.empty());

        worker.run(JOB);

        verify(runs).close("run-old", AgentRunRepository.Outcome.ABANDONED, null);
        verify(analysis).analyze(JOB);
        verify(jobs).markDone("job-1");
        verify(jobs, never()).markFailed(anyString(), any());
    }

    /**
     * The job's failure is the write that stops the case page polling, so it lands last: a poll between it and the
     * report's stamp left the page on a disabled Analyzing button beside a stopped bar.
     */
    @Test
    void aFailureStampsTheReportAndClosesTheRunBeforeItFailsTheJob() {
        when(reports.findByJobId("proj-1", "job-1")).thenReturn(Optional.of(mock(RcaReportRow.class)));
        when(runs.open(any(), any(), any(), anyString(), isNull())).thenReturn("run-1");
        doThrow(new IllegalStateException("agent returned junk")).when(analysis).analyze(JOB);

        worker.run(JOB);

        var order = inOrder(reports, runs, jobs);
        order.verify(reports)
                .complete(
                        eq("job-1"),
                        eq(RcaJobRow.FAILED),
                        isNull(),
                        eq("agent returned junk"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        order.verify(runs).close("run-1", AgentRunRepository.Outcome.FAILED, "RCA.UPSTREAM_FAILED");
        order.verify(jobs).markFailed("job-1", "agent returned junk");
    }

    /** A run that cannot be closed only leaves the page a step behind; the job still ends as it did. */
    @Test
    void aRunThatCannotBeClosedDoesNotChangeHowTheJobEnded() {
        when(reports.findByJobId("proj-1", "job-1")).thenReturn(Optional.of(mock(RcaReportRow.class)));
        when(runs.open(any(), any(), any(), anyString(), isNull())).thenReturn("run-1");
        doThrow(new IllegalStateException("db down")).when(runs).close(anyString(), any(), any());
        when(findings.findById("proj-1", "fnd-1")).thenReturn(Optional.empty());

        worker.run(JOB);

        verify(jobs).markDone("job-1");
        verify(jobs, never()).markFailed(anyString(), any());
    }

    private static FindingRow findingOnCase(String caseId) {
        return new FindingRow(
                "fnd-1",
                "proj-1",
                "tool_error",
                "cause:fnd-1",
                FindingRow.SubjectKind.TOOL,
                "sub_1",
                null,
                null,
                FindingRow.Status.OPEN,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                null,
                null,
                null,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                caseId,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }
}
