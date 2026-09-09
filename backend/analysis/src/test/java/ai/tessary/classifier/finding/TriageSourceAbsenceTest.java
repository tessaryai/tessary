// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.debug.ClassifierDebugContributor;
import ai.tessary.classifier.debug.ClassifierDebugDtos.BehaviorProfileDebugView;
import ai.tessary.classifier.debug.ClassifierDebugDtos.ClassifierDebugView;
import ai.tessary.classifier.debug.ClassifierDebugService;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.storage.AnnotationRepository;
import ai.tessary.storage.AnnotationRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What an ABSENT adapter does.
 *
 * <p>An edition whose classpath holds no drift adapter and no conformance adapter must degrade to what
 * the surface already shows for a classifier with no data, not throw. Pinned here without Spring, so a
 * wiring failure shows up here instead of at container start.
 *
 * <p>Every assertion runs against the real constructor: unreached collaborators are left null rather than
 * mocked, so a path that starts touching one fails loudly instead of passing on a default.
 */
class TriageSourceAbsenceTest {

    private static final String PROJECT = "prj_1";

    // ---- absent adapters degrade, on every port -----------------------------------------------

    @Test
    @DisplayName("no TriageSource at all: the page is empty and the withheld count is zero, not a failure")
    void absent_triage_sources_render_an_empty_page() {
        var page = service(List.of()).findings(PROJECT, null, null, null, true);
        assertTrue(page.findings().isEmpty());
        assertEquals(0, page.withheld());
        assertEquals(TriageLane.EVIDENCE_ONLY.wire(), page.lane(), "the lane is a constant, not a source's opinion");
    }

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

    @Test
    @DisplayName("no CauseResolver: the human's judgement is still recorded, only the fitted-state write is skipped")
    void absent_cause_resolver_still_resolves() {
        FindingRepository findings = mock(FindingRepository.class);
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        ClassifierService classifiers = mock(ClassifierService.class);
        AnnotationRepository annotations = mock(AnnotationRepository.class);
        BehaviorBaselineEventRepository events = mock(BehaviorBaselineEventRepository.class);
        BehaviorSubstrateRepository substrate = mock(BehaviorSubstrateRepository.class);

        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(profileRow("f1", FindingRow.Cause.NOVELTY)));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        when(evidence.exemplarTraceId(PROJECT, "f1")).thenReturn(Optional.of("tr_1"));
        when(substrate.traceSessionId(PROJECT, "tr_1")).thenReturn(Optional.of("ses_1"));

        // Shared source with an empty resolver list, as the container wires it without the drift adapter.
        BehaviorTriageSource source =
                driftSource(findings, evidence, classifiers, annotations, events, substrate, List.of());

        Optional<BehaviorFindingView> resolved =
                source.resolve(PROJECT, "f1", BehaviorResolutionRequest.EXPECTED, "usr_1");

        assertTrue(resolved.isPresent(), "an absent resolver degrades the write, it does not fail the call");
        verify(findings).setStatus(eq(PROJECT), eq("f1"), eq(FindingRow.Status.ALLOWLISTED), anyString());
        verify(events).insert(any(BehaviorBaselineEventRow.class));
        ArgumentCaptor<AnnotationRow> annotation = ArgumentCaptor.forClass(AnnotationRow.class);
        verify(annotations).upsert(annotation.capture());
        assertEquals(
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                annotation.getValue().key(),
                "with no resolver to name the classifier row whose fitted state moved, the correction is "
                        + "attributed to the detector kind — the fallback the pre-seam read already used");
        Boolean agrees = annotation.getValue().agrees();
        assertNotNull(agrees, "the correction is a boolean annotation, not an empty one");
        assertFalse(agrees, "'Expected' says the DETECTION was wrong, not that the behaviour was");
    }

    @Test
    @DisplayName("no ClassifierDebugContributor: the profile block is null, as it is for every other family")
    void absent_debug_contributor_renders_null() {
        ClassifierDebugView view = debug(List.of());

        assertEquals(
                ClassifierDebugView.Family.BEHAVIOR_DRIFT,
                view.family(),
                "the classifier is still routed to the drift family; only the block-filler is missing");
        assertNull(
                view.behaviorProfiles(),
                "null is 'this family has no profile block', which is what a deterministic classifier "
                        + "already renders; an empty list would claim the epoch store was read and was empty");
    }

    @Test
    @DisplayName("a registered contributor IS matched — on the Family string the service computed, not on a "
            + "detector kind that merely reads the same today")
    void a_registered_debug_contributor_fills_the_block() {
        // Control for the test above: Family and Kind are separate constants that only share a value
        // today; a null-only assertion wouldn't catch them diverging.
        ClassifierDebugView view = debug(List.of(new StubDebugContributor()));

        assertEquals(
                List.of(StubDebugContributor.EPOCH),
                view.behaviorProfiles(),
                "a present contributor's epochs are what the block holds; if this goes null the service and "
                        + "the adapter have stopped agreeing on the family string");
    }

    // ---- two deliberate routing asymmetries ---------------------------------------------------

    @Test
    @DisplayName("detail disclaims an id the source does not project; the next source is asked")
    void detail_falls_through_to_the_next_source() {
        ClaimingSource conformance = new ClaimingSource();
        var view = service(List.of(new DisclaimingSource(), conformance)).finding(PROJECT, "f1");
        assertEquals("claimed", view.finding().id(), "the shared source disclaims an SOP-keyed row by design");
    }

    @Test
    @DisplayName("resolve claims where detail disclaims — the asymmetry is deliberate and is why conformance's "
            + "resolve arm is unreachable")
    void resolve_and_detail_disagree_on_purpose() {
        // detail() routes on classifier_key and defers SOP-keyed rows to conformance; resolve() routes on
        // presence in the shared table, so it claims those same rows and 404s them instead. Both predate
        // this seam and are deliberate; one row is enough since the point is that it gets two answers.
        FindingRepository findings = mock(FindingRepository.class);
        ClassifierService classifiers = mock(ClassifierService.class);
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(sopRow("f1")));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        BehaviorTriageSource shared = driftSource(
                findings,
                mock(FindingEvidenceRepository.class),
                classifiers,
                mock(AnnotationRepository.class),
                mock(BehaviorBaselineEventRepository.class),
                mock(BehaviorSubstrateRepository.class),
                List.of());

        assertTrue(
                shared.detail(PROJECT, "f1").isEmpty(),
                "detail routes on classifier_key, so the SOP-keyed row falls through to conformance's own "
                        + "projection — the only one that carries the kind and baseline block that page renders");
        TessaryException claimed = assertThrows(
                TessaryException.class, () -> shared.resolve(PROJECT, "f1", BehaviorResolutionRequest.EXPECTED, null));
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                claimed.error(),
                "resolve routes on PRESENCE, so it claims the same row and 404s it on its own profile guard "
                        + "instead of returning empty and letting conformance answer — which is exactly why "
                        + "conformance's resolve arm stays unreachable here");
    }

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
        return new FindingService(null, null, null, null, sources);
    }

    /**
     * The shared source as an edition without the drift adapter would wire it: unreached collaborators
     * are null rather than mocked, so touching one fails loudly instead of quietly returning a default.
     */
    @SuppressWarnings("NullAway") // deliberate: the null collaborators are unreachable on detail/resolve
    private static BehaviorTriageSource driftSource(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierService classifiers,
            AnnotationRepository annotations,
            BehaviorBaselineEventRepository events,
            BehaviorSubstrateRepository substrate,
            List<CauseResolver> causeResolvers) {
        return new BehaviorTriageSource(
                findings,
                evidence,
                null, // ClassifierRepository: read by the escalation arm only
                classifiers,
                null, // BehaviorTriageJobRepository: read by list()/escalate() only
                null, // BehaviorTriageEngine
                null, // MetricBaselineRepository: the distribution_shift branch only
                null, // ToolErrorReferenceRepository: the rate_shift branch only
                null, // ToolErrorStateRepository
                null, // ToolErrorService
                events,
                annotations,
                substrate,
                TestObjectProvider.of(causeResolvers),
                null); // ObjectMapper: the two metric/tool-error branches only
    }

    /** The triage worker with only the collaborators {@code triageForTest} reaches. */
    @SuppressWarnings("NullAway") // deliberate: the scheduler, breaker and executor are not on this path
    private static BehaviorTriageWorker worker(
            BehaviorTriageJobRepository jobs, BehaviorTriageEngine engine, List<TriageSource> sources) {
        return new BehaviorTriageWorker(jobs, sources, engine, null, null, null, null, null);
    }

    /** The debug bundle for one behaviour-drift classifier, assembled by the real service. */
    private static ClassifierDebugView debug(List<ClassifierDebugContributor> contributors) {
        ClassifierService classifiers = mock(ClassifierService.class);
        ClassifierJobRepository jobs = mock(ClassifierJobRepository.class);
        when(classifiers.get(PROJECT, "clf_1")).thenReturn(driftClassifier());
        when(jobs.listByProject(PROJECT)).thenReturn(List.of());
        return new ClassifierDebugService(
                        classifiers,
                        jobs,
                        mock(MetricBaselineRepository.class),
                        TestObjectProvider.of(contributors),
                        new ObjectMapper())
                .debug(PROJECT, "clf_1");
    }

    private static ClassifierRow driftClassifier() {
        return new ClassifierRow(
                "clf_1",
                PROJECT,
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                "behaviour drift",
                null,
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }

    /** A drift finding that hangs off a fitted profile — the shape that reaches the {@link CauseResolver} call. */
    private static FindingRow profileRow(String id, String cause) {
        return row(
                id,
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                FindingRow.SubjectKind.BEHAVIOR_PROFILE,
                "prof_1",
                "{\"cause_kind\":\"" + cause + "\",\"exemplar_verdict_id\":\"vd_1\"}");
    }

    /** An SOP-keyed row in the SHARED table — the one conformance's own source projects. */
    private static FindingRow sopRow(String id) {
        return row(id, BuiltInDetector.Kind.SOP_CONFORMANCE, FindingRow.SubjectKind.CONFORMANCE_RULE, "rule_1", null);
    }

    private static FindingRow row(
            String id, String classifierKey, String subjectKind, String subjectId, @Nullable String payloadJson) {
        return new FindingRow(
                id,
                PROJECT,
                classifierKey,
                "cause:" + id,
                subjectKind,
                subjectId,
                null,
                null,
                FindingRow.Status.OPEN,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                null,
                null,
                null,
                3,
                payloadJson,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
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
                "2026-08-02T00:00:00Z",
                1,
                null,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                BehaviorTriageSource.KIND,
                null);
    }

    /** A contributor that IS registered, declaring the family string the service computes. */
    private static final class StubDebugContributor implements ClassifierDebugContributor {

        static final BehaviorProfileDebugView EPOCH = new BehaviorProfileDebugView(
                "cs_1",
                "armed",
                "2026-08-01T00:00:00Z",
                "2026-08-01T06:00:00Z",
                40,
                12,
                3,
                null,
                null,
                null,
                null,
                null,
                null);

        @Override
        public String family() {
            return ClassifierDebugView.Family.BEHAVIOR_DRIFT;
        }

        @Override
        public List<BehaviorProfileDebugView> profiles(String projectId, String classifierId) {
            return List.of(EPOCH);
        }
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
            return BuiltInDetector.Kind.SOP_CONFORMANCE;
        }

        @Override
        public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
            return Optional.of(new BehaviorFindingDetailView(claimed(), null, null, null));
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
                    FindingRow.Cause.NOVELTY,
                    "cause",
                    "claimed",
                    BuiltInDetector.Kind.SOP_CONFORMANCE,
                    FindingRow.GLOBAL_WORKFLOW,
                    "2026-08-01T00:00:00Z",
                    "2026-08-02T00:00:00Z",
                    1,
                    List.of(),
                    FindingRow.Status.OPEN,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    BehaviorFindingView.TriageStatus.PENDING,
                    null,
                    0,
                    null);
        }
    }
}
