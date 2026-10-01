// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.AwaitsConfiguration;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * What {@link BehaviorTriageWorker} does with the two ways a ruling run fails (decision 3): a run failure ({@code
 * TRIAGE_RUN_INCOMPLETE}) spends the attempt and leaves the breaker alone; a launcher failure ({@code
 * TRIAGE_LAUNCHER_UNAVAILABLE}) refunds it and feeds the breaker. Classification is {@link E2bTriageSandboxTest}'s.
 * Driven through {@code triageForTest}, with no scheduler or lease math.
 */
class BehaviorTriageWorkerTest {

    private static final String PROJECT = "prj_1";

    @Test
    @DisplayName("a run failure spends the attempt (markRetryable) and never touches the breaker")
    void aRunFailureCallsMarkRetryableAndLeavesTheBreakerAlone() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        TriageLauncherBreaker breaker = mock(TriageLauncherBreaker.class);
        when(engine.rule(eq(PROJECT), eq("f1"), any(), any()))
                .thenThrow(new TessaryException(
                        ClassifierError.TRIAGE_RUN_INCOMPLETE, "f1", "kind=script_exit detail=agent rejected"));
        BehaviorTriageWorker worker = worker(jobs, engine, breaker, FakeTriageSource.briefing());

        worker.triageForTest(job("job_1", "f1"));

        verify(jobs).markRetryable(eq("job_1"), anyString(), anyLong());
        verifyNoInteractions(breaker);
    }

    /**
     * A ruling is written through its source with the citations, then the job is done and the launcher counted
     * reachable. Missing any leaves a verdict unwritten, a ruled finding re-claimed, or the drain parked.
     */
    @Test
    void aRulingIsRecordedByTheBriefingSourceAndClosesTheJobAndTheBreaker() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        TriageSource source = mock(TriageSource.class);
        TriageLauncherBreaker breaker = trippedBreaker();
        BehaviorTriageJobRow job = job("job_1", "f1");
        TriageBrief brief = new TriageBrief(Map.of("finding.md", "the finding"), "rule on it");
        BehaviorTriageVerdict verdict = new BehaviorTriageVerdict(
                FindingRow.TriageVerdict.POSITIVE,
                "the rise is real",
                List.of(new BehaviorTriageVerdict.Citation("window.n_cur", "the count", null)));
        when(source.brief(job)).thenReturn(Optional.of(brief));
        when(engine.rule(PROJECT, "f1", brief.dossier(), brief.prompt())).thenReturn(verdict);
        when(engine.citationsJson(verdict)).thenReturn("[{\"path\":\"window.n_cur\"}]");

        worker(jobs, engine, breaker, source).triageForTest(job);

        verify(source)
                .recordVerdict(eq(PROJECT), eq("f1"), eq(verdict), eq("[{\"path\":\"window.n_cur\"}]"), anyString());
        verify(jobs).markDone("job_1");
        assertFalse(breaker.isOpen(), "a run that reached the launcher closes a trip");
    }

    /**
     * A launcher that cannot run, or an org with no usable key, fails until a person edits something. The job goes
     * back unspent; spending attempts dead-letters it, and feeding the breaker parks every org's drain.
     */
    @ParameterizedTest
    @MethodSource("refusals")
    void aRefusalParksTheJobUnspentUntilItCanClear(TessaryException refusal, long delaySeconds) {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        TriageLauncherBreaker breaker = mock(TriageLauncherBreaker.class);
        when(engine.rule(eq(PROJECT), eq("f1"), any(), any())).thenThrow(refusal);
        ClassifierProperties props = new ClassifierProperties();
        props.setTriageConfigRetrySeconds(1800);

        tickableWorker(jobs, engine, breaker, FakeTriageSource.briefing(), props)
                .triageForTest(job("job_1", "f1"));

        verify(jobs).releaseWithoutAttempt("job_1", refusal.getMessage(), delaySeconds);
        verify(jobs, never()).markRetryable(anyString(), any(), anyLong());
        verifyNoInteractions(breaker);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(gap(ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED), 1800L),
                Arguments.of(gap(ModelConfigError.MISSING_CREDENTIALS), 1800L),
                Arguments.of(gap(ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED), 1800L),
                Arguments.of(
                        named(
                                "a refusal waiting on a person (credit to top up) holds for the re-check window, since"
                                        + " topping up later publishes no event that would bring it back",
                                new OutOfCredit()),
                        1800L),
                Arguments.of(
                        named("a refusal that clears on its own (a busy slot) waits the delay it names", new Busy()),
                        60L));
    }

    private static TessaryException gap(ErrorCode code) {
        return new TessaryException(code, "bedrock", "triage");
    }

    /** A refusal only a person can clear, such as a provider balance at zero. */
    private static final class OutOfCredit extends TessaryException implements AwaitsConfiguration {
        OutOfCredit() {
            super(ClassifierError.TRIAGE_RUN_INCOMPLETE, "f1", "the provider's credit is exhausted");
        }
    }

    /** A refusal that clears on its own, such as every concurrent run slot being taken. */
    private static final class Busy extends TessaryException implements Retryable {
        Busy() {
            super(ClassifierError.TRIAGE_RUN_INCOMPLETE, "f1", "every run slot is taken");
        }

        @Override
        public int retryAfterSeconds() {
            return 60;
        }
    }

    @Test
    @DisplayName("a job no source briefs is done, not failed — the finding was resolved while it waited")
    void an_unclaimed_job_is_marked_done() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        TriageLauncherBreaker breaker = mock(TriageLauncherBreaker.class);
        BehaviorTriageWorker worker =
                worker(jobs, engine, breaker, new FakeTriageSource("behavior"), new ClaimingSource());

        worker.triageForTest(job("job_1", "f1"));

        verify(jobs).markDone("job_1");
        // No brief: the microVM is never spawned.
        verifyNoInteractions(engine);
        verifyNoInteractions(breaker);
    }

    /** Any other failure spends the attempt and backs off 2s, RetryPolicy's schedule. */
    @Test
    void anUnexpectedFailureSpendsTheAttemptWithTheFirstBackoff() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        when(engine.rule(eq(PROJECT), eq("f1"), any(), any())).thenThrow(new IllegalStateException("npe in the brief"));

        worker(jobs, engine, mock(TriageLauncherBreaker.class), FakeTriageSource.briefing())
                .triageForTest(job("job_1", "f1"));

        verify(jobs).markRetryable("job_1", "npe in the brief", 2L);
    }

    /** While the launcher is down no job is touched, or the whole queue dead-letters. */
    @Test
    void anOpenBreakerParksTheWholeDrain() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);

        tickableWorker(
                        jobs,
                        mock(BehaviorTriageEngine.class),
                        trippedBreaker(),
                        FakeTriageSource.briefing(),
                        new ClassifierProperties())
                .tick();

        verifyNoInteractions(jobs);
    }

    /** A dead-letter sweep or a claim that fails ends the tick without dispatching anything. */
    @Test
    void aFailedSweepOrClaimDispatchesNothing() {
        BehaviorTriageJobRepository sweepFails = mock(BehaviorTriageJobRepository.class);
        when(sweepFails.failExhausted(anyInt())).thenThrow(new IllegalStateException("db down"));
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        tickableWorker(sweepFails, engine, breaker(), FakeTriageSource.briefing(), new ClassifierProperties())
                .tick();
        verify(sweepFails, never()).claimBatch(anyString(), anyInt(), anyLong(), anyInt());

        BehaviorTriageJobRepository claimFails = mock(BehaviorTriageJobRepository.class);
        when(claimFails.claimBatch(anyString(), anyInt(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        tickableWorker(claimFails, engine, breaker(), FakeTriageSource.briefing(), new ClassifierProperties())
                .tick();

        verifyNoInteractions(engine);
    }

    /** The tick claims until the queue is empty; a breaker trip mid-tick stops it, so an outage costs one batch. */
    @Test
    void aTickRunsWhatItClaimsAndStopsClaimingOnceTheBreakerTrips() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        when(jobs.failExhausted(anyInt())).thenReturn(2);
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(job("job_1", "f1")))
                .thenReturn(List.of(job("job_2", "f2")));
        when(engine.rule(eq(PROJECT), anyString(), any(), any()))
                .thenThrow(new TessaryException(ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE, "status 401"));
        TriageLauncherBreaker breaker = breaker();

        tickableWorker(jobs, engine, breaker, FakeTriageSource.briefing(), new ClassifierProperties())
                .tick();

        verify(jobs).releaseWithoutAttempt(eq("job_1"), anyString());
        verify(jobs, times(1)).claimBatch(anyString(), anyInt(), anyLong(), anyInt());
        assertTrue(breaker.isOpen());
    }

    /** A queue that never drains ends the tick at its round limit. */
    @Test
    void aTickStopsClaimingAfterItsRoundLimit() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt())).thenReturn(List.of(job("job_1", "f1")));
        TriageSource claimsNothing = mock(TriageSource.class);
        when(claimsNothing.brief(any())).thenReturn(Optional.empty());

        tickableWorker(jobs, mock(BehaviorTriageEngine.class), breaker(), claimsNothing, new ClassifierProperties())
                .tick();

        verify(jobs, times(100)).claimBatch(anyString(), anyInt(), anyLong(), anyInt());
    }

    /** A breaker that trips on the first launcher failure and parks for an hour. */
    private static TriageLauncherBreaker breaker() {
        ClassifierProperties props = new ClassifierProperties();
        props.setTriageBreakerFailures(1);
        props.setTriageBreakerCooldownSeconds(3600);
        return new TriageLauncherBreaker(props);
    }

    private static TriageLauncherBreaker trippedBreaker() {
        TriageLauncherBreaker breaker = breaker();
        breaker.recordLauncherFailure("401");
        return breaker;
    }

    private static BehaviorTriageWorker tickableWorker(
            BehaviorTriageJobRepository jobs,
            BehaviorTriageEngine engine,
            TriageLauncherBreaker breaker,
            TriageSource source,
            ClassifierProperties props) {
        return new BehaviorTriageWorker(
                jobs,
                List.of(source),
                engine,
                props,
                new ObserverProperties(),
                new TraceMdcBridge(mock(Tracer.class)),
                new SyncTaskExecutor(),
                breaker);
    }

    /** Only the collaborators {@code triageForTest} reaches, plus a real breaker. */
    @SuppressWarnings("NullAway") // the scheduler, lease properties, and executor are not on this path
    private static BehaviorTriageWorker worker(
            BehaviorTriageJobRepository jobs,
            BehaviorTriageEngine engine,
            TriageLauncherBreaker breaker,
            TriageSource... sources) {
        return new BehaviorTriageWorker(jobs, List.of(sources), engine, null, null, null, null, breaker);
    }

    private static BehaviorTriageJobRow job(String id, String findingId) {
        return new BehaviorTriageJobRow(
                id,
                PROJECT,
                findingId,
                "running",
                "owner",
                "2026-09-16T00:00:00Z",
                1,
                null,
                "2026-09-15T00:00:00Z",
                "2026-09-16T00:00:00Z");
    }

    /** Claims every id it is offered, on both the read and the write arm. */
    private static final class ClaimingSource extends FakeTriageSource {
        ClaimingSource() {
            super(BuiltInDetector.Kind.TOOL_ERROR);
        }

        @Override
        public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
            return Optional.of(new BehaviorFindingDetailView(claimed(), null, null, null, null, null, null, null));
        }

        @Override
        public Optional<BehaviorFindingView> resolve(
                String projectId, String findingId, String action, @Nullable String userId) {
            return Optional.of(claimed());
        }

        private static BehaviorFindingView claimed() {
            return new BehaviorFindingView(
                    "claimed",
                    null,
                    FindingRow.Cause.RATE_SHIFT,
                    "cause",
                    "claimed",
                    BuiltInDetector.Kind.TOOL_ERROR,
                    FindingRow.GLOBAL_WORKFLOW,
                    "2026-08-01T00:00:00Z",
                    "2026-08-02T00:00:00Z",
                    1,
                    FindingRow.Status.OPEN,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    BehaviorFindingView.TriageStatus.PENDING,
                    null,
                    null);
        }
    }
}
