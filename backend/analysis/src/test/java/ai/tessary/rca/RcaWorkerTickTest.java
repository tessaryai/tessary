// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.TraceMdcBridge;
import io.micrometer.tracing.Tracer;
import java.net.UnknownHostException;
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
    private final RcaProperties props = new RcaProperties();
    private final RcaWorker worker = new RcaWorker(
            jobs,
            reports,
            analysis,
            props,
            new TraceMdcBridge(Tracer.NOOP),
            findings,
            caseEvents,
            new SyncTaskExecutor());

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

    /** A failed report stamp after the job was marked failed must not reach the executor; the job's failure stands. */
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

        verify(jobs).markFailed("job-1", "launcher down", props.getMaxAttempts());
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
        verify(jobs, never()).markFailed(anyString(), any(), anyInt());
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

    /**
     * On a host whose name does not resolve, building the lease owner threw and the worker never constructed. It
     * falls back to a fixed name.
     */
    @Test
    void aHostWhoseNameDoesNotResolveStillNamesItsLeaseOwner() {
        assertEquals("box-1", RcaWorker.shortHost(() -> "box-1"));
        assertEquals("host", RcaWorker.shortHost(() -> {
            throw new UnknownHostException("box-1");
        }));
    }
}
