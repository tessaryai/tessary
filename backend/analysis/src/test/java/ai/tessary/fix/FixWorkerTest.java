// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.Outcome;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.ProviderStop;
import ai.tessary.agentrun.RunSteps;
import ai.tessary.config.FixProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.Props;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The fix queue's heartbeat and how each run ends, with the step and stores mocked. The bugs: a run whose launcher was
 * full marked failed instead of waiting its turn, a failure recorded without its code, a provider stop that reads as a
 * generic failure, a run whose worker died left running forever with its session still spending, and one bad claim or
 * sweep that stops the queue.
 */
@ExtendWith(MockitoExtension.class)
class FixWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private static final FixRunRow RUN = new FixRunRow(
            "run-1", "proj-1", null, "case-1", "rpt-1", "running", null, null, null, List.of(), List.of(), 0, "t0",
            "t0");

    @Mock
    private FixRunRepository fixRuns;

    @Mock
    private FixStep step;

    @Mock
    private AgentRunRepository runs;

    @Mock
    private AgentSessionClient sessions;

    private FixWorker worker() {
        return new FixWorker(
                fixRuns,
                step,
                runs,
                sessions,
                Props.of(FixProperties.class, "timeout-ms", 1_800_000),
                new TraceMdcBridge(Tracer.NOOP),
                new SyncTaskExecutor(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void opensRun() {
        when(runs.open("proj-1", AgentRunRepository.Lane.FIX, "run-1", "run-1", null))
                .thenReturn("ar-1");
    }

    private static AgentRunRepository.RunSummary orphan(String id, @Nullable String sessionId) {
        return new AgentRunRepository.RunSummary(
                id,
                AgentRunRepository.Lane.FIX,
                "run-0",
                1,
                "t",
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                sessionId);
    }

    @Test
    void aRunThatFinishesClosesItsAgentRunDone() {
        opensRun();

        worker().run(RUN);

        verify(step).run(RUN, new RunSteps(runs, "ar-1"));
        verify(runs).close("ar-1", Outcome.DONE, null);
        verify(fixRuns, never()).fail(anyString(), anyString(), any());
    }

    @Test
    void aFullLauncherSendsTheRunBackToTheQueue() {
        opensRun();
        doThrow(new TessaryException(AgentRunError.CAPACITY, "8 sessions"))
                .when(step)
                .run(eq(RUN), any());

        worker().run(RUN);

        verify(fixRuns).requeue("run-1", NOW);
        verify(runs).close("ar-1", Outcome.RELEASED, AgentRunError.CAPACITY.code());
        verify(fixRuns, never()).fail(anyString(), anyString(), any());
    }

    @Test
    void aFailureFailsTheRunWithItsErrorCode() {
        opensRun();
        doThrow(new TessaryException(GitError.BRANCH_MOVED, "b", "r"))
                .when(step)
                .run(eq(RUN), any());

        worker().run(RUN);

        verify(fixRuns).fail("run-1", GitError.BRANCH_MOVED.code(), NOW);
        verify(runs).close("ar-1", Outcome.FAILED, GitError.BRANCH_MOVED.code());
    }

    @Test
    void aProviderStopFailsTheRunWithItsKind() {
        opensRun();
        doThrow(new ProviderStop("provider_rejected", "bad key")).when(step).run(eq(RUN), any());

        worker().run(RUN);

        verify(fixRuns).fail("run-1", "provider_rejected", NOW);
    }

    @Test
    void anUnexpectedFailureFailsTheRunAsARunFailure() {
        opensRun();
        doThrow(new IllegalStateException("boom")).when(step).run(eq(RUN), any());

        worker().run(RUN);

        verify(fixRuns).fail("run-1", AgentRunError.RUN_FAILED.code(), NOW);
        verify(runs).close("ar-1", Outcome.FAILED, AgentRunError.RUN_FAILED.code());
    }

    @Test
    void aTickFailsStaleRunsStopsTheirSessionsThenDrainsTheQueueUntilEmpty() {
        FixRunRow stale = new FixRunRow(
                "run-0", "proj-1", null, "case-0", "rpt-0", "running", null, null, null, List.of(), List.of(), 0, "t",
                "t");
        when(fixRuns.runningSince(Instant.parse("2026-11-19T10:50:00Z"))).thenReturn(List.of(stale));
        when(runs.openRunsOfJob("run-0")).thenReturn(List.of(orphan("ar-0", "sess-0"), orphan("ar-00", null)));
        when(fixRuns.claimNext(NOW)).thenReturn(Optional.of(RUN), Optional.empty());
        opensRun();

        worker().tick();

        InOrder order = inOrder(sessions, runs, fixRuns, step);
        order.verify(sessions).stop("sess-0");
        order.verify(runs).close("ar-0", Outcome.ABANDONED, null);
        order.verify(runs).close("ar-00", Outcome.ABANDONED, null);
        order.verify(fixRuns).fail("run-0", FixRunRow.Reason.INTERRUPTED, NOW);
        order.verify(step).run(eq(RUN), any());
        verify(fixRuns, times(2)).claimNext(NOW);
    }

    @Test
    void aFailingSweepStillDrainsAndAFailingClaimEndsTheTickQuietly() {
        when(fixRuns.runningSince(any())).thenThrow(new IllegalStateException("db down"));
        when(fixRuns.claimNext(NOW)).thenThrow(new IllegalStateException("db down"));

        worker().tick();

        verify(fixRuns).claimNext(NOW);
        verify(step, never()).run(any(), any());
    }

    @Test
    void oneTickClaimsAtMostItsCap() {
        when(fixRuns.runningSince(any())).thenReturn(List.of());
        when(fixRuns.claimNext(NOW)).thenReturn(Optional.of(RUN));
        when(runs.open(anyString(), any(), anyString(), anyString(), any())).thenReturn("ar-1");

        worker().tick();

        verify(fixRuns, times(FixWorker.MAX_CLAIMS)).claimNext(NOW);
    }
}
