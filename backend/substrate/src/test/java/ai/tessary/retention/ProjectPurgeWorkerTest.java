// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.tenant.ProjectDeleteJobRepository;
import ai.tessary.tenant.ProjectDeleteJobRow;
import ai.tessary.tenant.ProjectRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Where a purge job goes after one tick of work. The job state transitions are the repository calls, so
 * those calls are the outcome asserted here.
 */
@ExtendWith(MockitoExtension.class)
class ProjectPurgeWorkerTest {

    private static final ProjectDeleteJobRow JOB = new ProjectDeleteJobRow("job-1", "proj-1", 2);

    @Mock
    ProjectDeleteJobRepository jobs;

    @Mock
    ProjectPurgeRepository purge;

    @Mock
    ProjectRepository projects;

    private ProjectPurgeWorker worker() {
        when(jobs.enqueueMissing(anyString())).thenReturn(0);
        when(jobs.failExhausted(anyInt())).thenReturn(List.of());
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt())).thenReturn(List.of(JOB));
        return new ProjectPurgeWorker(jobs, purge, projects);
    }

    /** A project bigger than one tick's batch budget goes back to the queue to continue, not marked done. */
    @Test
    void aPurgeThatOutrunsItsTickIsReleasedToContinue() {
        ProjectPurgeWorker worker = worker();
        when(purge.deleteBatch(anyString(), eq("proj-1"), anyInt())).thenReturn(1);

        worker.tick();

        verify(jobs).releaseForContinuation("job-1");
        verify(jobs, never()).markDone(anyString());
    }

    /** A purge that throws is handed back for retry with its error and its attempt count. */
    @Test
    void aPurgeThatFailsIsMarkedRetryable() {
        ProjectPurgeWorker worker = worker();
        IllegalStateException failure = new IllegalStateException("lock timeout");
        when(purge.deleteBatch(anyString(), eq("proj-1"), anyInt())).thenThrow(failure);

        worker.tick();

        verify(jobs).markRetryable("job-1", failure.toString(), 2, 5);
    }

    /** A database that is down for the recovery step costs the tick, not the heartbeat. */
    @Test
    void aTickWhoseRecoveryFailsIsSurvived() {
        when(jobs.enqueueMissing(anyString())).thenThrow(new IllegalStateException("connection refused"));

        assertDoesNotThrow(new ProjectPurgeWorker(jobs, purge, projects)::tick);
    }
}
