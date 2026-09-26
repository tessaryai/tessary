// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an ABSENT adapter does.
 *
 * <p>A classpath that holds no triage source, or none that owns an id, must degrade to what the surface
 * already shows for a classifier with no data, not throw. Pinned here without Spring, so a
 * wiring failure shows up here instead of at container start.
 *
 * <p>Every assertion runs against the real constructor: unreached collaborators are left null rather than
 * mocked, so a path that starts touching one fails loudly instead of passing on a default.
 */
class TriageSourceAbsenceTest {

    private static final String PROJECT = "prj_1";

    // ---- absent adapters degrade, on every port -----------------------------------------------

    @Test
    @DisplayName("no TriageSource owns the id: detail, analyze and resolve all 404 rather than 500")
    void unclaimed_ids_are_not_found() {
        FindingService service = service(List.of(new DisclaimingSource()));
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> service.finding(PROJECT, "f1"))
                        .error());
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> service.analyze(PROJECT, "f1", null))
                        .error());
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> service.resolve(
                                        PROJECT, "f1", BehaviorDtos.BehaviorResolutionRequest.EXPECTED, null))
                        .error());
    }

    @Test
    @DisplayName("an unrecognised resolution verb is rejected before any source is consulted")
    void the_verb_is_validated_at_the_entry_point() {
        assertEquals(
                ClassifierError.INVALID_RESOLUTION,
                assertThrows(TessaryException.class, () -> service(List.of()).resolve(PROJECT, "f1", "maybe", null))
                        .error());
    }

    // ---- routing -------------------------------------------------------------------------------

    // ---- a job no source claims ---------------------------------------------------------------

    @Test
    @DisplayName("a job no source briefs is done, not failed — the finding was resolved while it waited")
    void an_unclaimed_job_is_marked_done() {
        // Driven through the worker's package-private seam: the property is what the worker does with a
        // brief nobody returned. Both pre-seam branches already did this, so the seam adds no new state.
        BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);
        BehaviorTriageEngine engine = mock(BehaviorTriageEngine.class);
        BehaviorTriageWorker worker = worker(jobs, engine, List.of(new DisclaimingSource(), new ClaimingSource()));

        worker.triageForTest(job("job_1", "f1"));

        verify(jobs).markDone("job_1");
        // No brief means nothing to rule on, so the microVM must not be spawned at all.
        verifyNoInteractions(engine);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    @SuppressWarnings("NullAway") // deliberate: the injected repositories are unreachable on these paths
    private static FindingService service(List<TriageSource> sources) {
        return new FindingService(null, null, null, null, sources, null, null, null); // detail services unreached
    }

    /** The triage worker with only the collaborators {@code triageForTest} reaches. */
    @SuppressWarnings("NullAway") // deliberate: the scheduler, breaker and executor are not on this path
    private static BehaviorTriageWorker worker(
            BehaviorTriageJobRepository jobs, BehaviorTriageEngine engine, List<TriageSource> sources) {
        return new BehaviorTriageWorker(jobs, sources, engine, null, null, null, null, null);
    }

    private static BehaviorTriageJobRow job(String id, String findingId) {
        return new BehaviorTriageJobRow(
                id,
                PROJECT,
                findingId,
                "running",
                "owner",
                "2026-08-02T00:00:00Z",
                1,
                null,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }

    /** Owns nothing: every Optional-returning method answers empty. */
    private static class DisclaimingSource implements TriageSource {
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
            return Optional.empty();
        }

        @Override
        public void recordVerdict(
                String projectId,
                String findingId,
                BehaviorTriageVerdict verdict,
                @Nullable String citationsJson,
                String now) {}
    }

    /** Claims every id it is offered, on both the read and the write arm. */
    private static final class ClaimingSource extends DisclaimingSource {
        @Override
        public String kind() {
            return BuiltInDetector.Kind.TOOL_ERROR;
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
