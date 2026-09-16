// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("a launcher failure releases the job unspent and feeds the breaker")
    void aLauncherFailureReleasesTheJobAndFeedsTheBreaker() {
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        TriageLauncherBreaker breaker = mock(TriageLauncherBreaker.class);
        when(engine.rule(eq(PROJECT), eq("f1"), any(), any()))
                .thenThrow(new TessaryException(
                        ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE,
                        "status 502 from http://launcher — docker pull failed"));
        BehaviorTriageWorker worker = worker(jobs, engine, breaker, new BriefingSource());

        worker.triageForTest(job("job_1", "f1"));

        verify(jobs).releaseWithoutAttempt(eq("job_1"), anyString());
        verify(breaker).recordLauncherFailure(anyString());
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
                null,
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                "running",
                "owner",
                "2026-09-16T00:00:00Z",
                1,
                null,
                "2026-09-15T00:00:00Z",
                "2026-09-16T00:00:00Z",
                BehaviorTriageSource.KIND,
                null);
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
        public long countWithheld(String projectId, @Nullable String callSiteId, boolean confirmedOnly) {
            return 0;
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
