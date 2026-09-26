// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.ModelConfigError;
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
 * What {@link BehaviorTriageWorker} does with the two ways a ruling run can fail — decision 3 of the
 * triage-fixes plan: a run failure ({@code TRIAGE_RUN_INCOMPLETE}, thrown for a launcher-classified
 * run-level 502 or a non-401/403/404/5xx rejection) spends the finding's attempt and leaves the
 * launcher breaker untouched, while a launcher failure ({@code TRIAGE_LAUNCHER_UNAVAILABLE}) refunds
 * the attempt and feeds the breaker — see {@link E2bTriageSandboxTest} for the classification itself.
 * Driven through {@code triageForTest}, the same package-private seam {@link TriageSourceAbsenceTest}
 * uses, so no scheduler, executor or lease math is on the path.
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
        BehaviorTriageWorker worker = worker(jobs, engine, breaker, new BriefingSource());

        worker.triageForTest(job("job_1", "f1"));

        verify(jobs).markRetryable(eq("job_1"), anyString(), anyLong());
        verifyNoInteractions(breaker);
    }

    /**
     * A ruling is written through the source that briefed it, with the engine's citations, and only then is
     * the job done and the launcher counted reachable. Dropping any of the three leaves a verdict unwritten,
     * a job re-claimed for a finding already ruled, or the drain parked after the launcher came back.
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
     * A launcher that cannot run, and an org with no usable key, will fail identically until a person edits
     * something. The job goes back unspent and re-checks a configuration window later; spending attempts on
     * it dead-letters the finding for good, and feeding the breaker parks every other org's drain.
     */
    @ParameterizedTest
    @MethodSource("configurationGaps")
    void aConfigurationGapParksTheJobUnspentForTheRecheckWindow(ErrorCode code) {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        TriageLauncherBreaker breaker = mock(TriageLauncherBreaker.class);
        TessaryException gap = new TessaryException(code, "bedrock", "triage");
        when(engine.rule(eq(PROJECT), eq("f1"), any(), any())).thenThrow(gap);
        ClassifierProperties props = new ClassifierProperties();
        props.setTriageConfigRetrySeconds(1800);

        tickableWorker(jobs, engine, breaker, new BriefingSource(), props).triageForTest(job("job_1", "f1"));

        verify(jobs).releaseWithoutAttempt("job_1", gap.getMessage(), 1800L);
        verify(jobs, never()).markRetryable(anyString(), any(), anyLong());
        verifyNoInteractions(breaker);
    }

    static Stream<Arguments> configurationGaps() {
        return Stream.of(
                Arguments.of(ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED),
                Arguments.of(ModelConfigError.MISSING_CREDENTIALS),
                Arguments.of(ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED));
    }

    /** Any other failure spends the attempt and backs off: 2s after the first, RetryPolicy's own schedule. */
    @Test
    void anUnexpectedFailureSpendsTheAttemptWithTheFirstBackoff() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        when(engine.rule(eq(PROJECT), eq("f1"), any(), any())).thenThrow(new IllegalStateException("npe in the brief"));

        worker(jobs, engine, mock(TriageLauncherBreaker.class), new BriefingSource())
                .triageForTest(job("job_1", "f1"));

        verify(jobs).markRetryable("job_1", "npe in the brief", 2L);
    }

    /** While the launcher is down the tick touches no job: sweeping would dead-letter the whole queue. */
    @Test
    void anOpenBreakerParksTheWholeDrain() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);

        tickableWorker(
                        jobs,
                        mock(BehaviorTriageEngine.class),
                        trippedBreaker(),
                        new BriefingSource(),
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
        tickableWorker(sweepFails, engine, breaker(), new BriefingSource(), new ClassifierProperties())
                .tick();
        verify(sweepFails, never()).claimBatch(anyString(), anyInt(), anyLong(), anyInt());

        BehaviorTriageJobRepository claimFails = mock(BehaviorTriageJobRepository.class);
        when(claimFails.claimBatch(anyString(), anyInt(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        tickableWorker(claimFails, engine, breaker(), new BriefingSource(), new ClassifierProperties())
                .tick();

        verifyNoInteractions(engine);
    }

    /**
     * Claimed jobs run, and the tick keeps claiming until the queue is empty. A launcher failure that trips
     * the breaker mid-tick stops the remaining rounds, so an outage costs one batch, not the whole queue.
     */
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

        tickableWorker(jobs, engine, breaker, new BriefingSource(), new ClassifierProperties())
                .tick();

        verify(jobs).releaseWithoutAttempt(eq("job_1"), anyString());
        verify(jobs, times(1)).claimBatch(anyString(), anyInt(), anyLong(), anyInt());
        assertTrue(breaker.isOpen());
    }

    /**
     * A queue that never drains (every claim finds another due job) ends the tick after its round limit
     * instead of holding the scheduler thread for as long as jobs keep arriving.
     */
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

    // ---- fixtures ---------------------------------------------------------------------------------

    /** The worker with only the collaborators {@code triageForTest} reaches, plus a real breaker. */
    @SuppressWarnings("NullAway") // deliberate: the scheduler, lease properties and executor are not on this path
    private static BehaviorTriageWorker worker(
            BehaviorTriageJobRepository jobs,
            BehaviorTriageEngine engine,
            TriageLauncherBreaker breaker,
            TriageSource source) {
        return new BehaviorTriageWorker(jobs, List.of(source), engine, null, null, null, null, breaker);
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

    /** Claims every job, handing back a brief so the worker always calls {@code engine.rule}. */
    private static final class BriefingSource implements TriageSource {
        @Override
        public String kind() {
            return "behavior";
        }

        @Override
        public List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit) {
            return List.of();
        }

        @Override
        public Optional<BehaviorAnalysisView> analyze(String projectId, String findingId) {
            return Optional.empty();
        }

        @Override
        public List<BehaviorFindingView> list(
                String projectId,
                @Nullable String status,
                @Nullable String callSiteId,
                @Nullable String detector,
                boolean confirmedOnly) {
            return List.of();
        }

        @Override
        public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
            return Optional.empty();
        }

        @Override
        public Optional<BehaviorFindingView> resolve(
                String projectId, String findingId, String action, @Nullable String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<TriageBrief> brief(BehaviorTriageJobRow job) {
            return Optional.of(new TriageBrief(Map.of("finding.md", "the finding"), "rule on it"));
        }

        @Override
        public void recordVerdict(
                String projectId,
                String findingId,
                BehaviorTriageVerdict verdict,
                @Nullable String citationsJson,
                String now) {}
    }
}
