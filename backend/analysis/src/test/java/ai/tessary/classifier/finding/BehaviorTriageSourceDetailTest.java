// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessDetailService;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView.TriageStatus;
import ai.tessary.classifier.finding.BehaviorTriageJobRepository.FailedTriage;
import ai.tessary.classifier.frustration.FrustrationDetailService;
import ai.tessary.classifier.malformed.MalformedOutputDetailService;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A finding's own page reads its triage status the way the findings list does.
 *
 * <p>The {@code finding} row cannot tell a dead-lettered triage from a running one (both have
 * {@code escalated_at} set and no verdict), so the page has to ask the job. It did not, and a triage that
 * gave up read as "Triaging" on the page while the list beside it said it had failed.
 */
class BehaviorTriageSourceDetailTest {

    private static final String PROJECT = "prj_1";

    private final FindingRepository findings = mock(FindingRepository.class);
    private final ClassifierService classifiers = mock(ClassifierService.class);
    private final BehaviorTriageJobRepository jobs = mock(BehaviorTriageJobRepository.class);

    @Test
    @DisplayName("a dead-lettered triage reads as failed on the finding's page, not in flight")
    void detail_reports_a_dead_lettered_triage_as_failed() {
        stubEscalatedFinding("f1");
        when(jobs.failedByFinding(PROJECT, List.of("f1")))
                .thenReturn(
                        Map.of("f1", new FailedTriage("f1", 5, "exhausted: 5 attempts (hung or crashed mid-triage)")));

        assertEquals(TriageStatus.FAILED, statusOf("f1"));
    }

    @Test
    @DisplayName("an escalated finding with no dead job is still in flight")
    void detail_reports_a_live_triage_as_in_flight() {
        stubEscalatedFinding("f1");
        when(jobs.failedByFinding(PROJECT, List.of("f1"))).thenReturn(Map.of());

        assertEquals(TriageStatus.IN_FLIGHT, statusOf("f1"));
    }

    /**
     * A finding an adapter owns carries the owner's detail as {@code classifierDetail}, and no cause-kind block. The
     * bug is an owner's numbers dropped between the adapter and the page.
     */
    @Test
    void theOwnersDetailIsOnTheFindingView() {
        stubOwnedFinding("f1");
        ClassifierDetail detail = detail();

        var view = source(FindingAdapters.of(List.of(
                        FakeFindingAdapter.owning("owned-x", null, null, null).reporting(detail))))
                .detail(PROJECT, "f1")
                .orElseThrow();

        assertEquals(detail, view.classifierDetail());
        assertNull(view.frustration());
    }

    private void stubOwnedFinding(String id) {
        FindingRow row = FindingRowBuilder.of("owned-x")
                .id(id)
                .projectId(PROJECT)
                .causeKey("cause:" + id)
                .subject(FindingRow.SubjectKind.CLASSIFIER, "clf_1")
                .payload("{\"cause_kind\":\"" + FindingRow.Cause.RATE_SHIFT + "\"}")
                .build();
        when(findings.findById(PROJECT, id)).thenReturn(Optional.of(row));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        when(jobs.failedByFinding(PROJECT, List.of(id))).thenReturn(Map.of());
    }

    private static ClassifierDetail detail() {
        return new ClassifierDetail(
                new RateDetail(
                        "cs-1",
                        0.02,
                        0.09,
                        7.0,
                        400,
                        120,
                        11,
                        List.of(),
                        false,
                        List.of(),
                        "2026-08-01T00:00:00Z",
                        null,
                        null,
                        "up",
                        6.2,
                        4.0,
                        0.3,
                        0.5),
                8L,
                "flagged",
                0.7,
                500L,
                4.0,
                null,
                new ClassifierDetail.Words("reply", "replies", "since onset", "learned rate", null, null));
    }

    private void stubEscalatedFinding(String id) {
        when(findings.findById(PROJECT, id)).thenReturn(Optional.of(escalatedRow(id)));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
    }

    private String statusOf(String id) {
        return source().detail(PROJECT, id).orElseThrow().finding().triageStatus();
    }

    private BehaviorTriageSource source() {
        return source(FindingAdapters.of(List.of()));
    }

    /** Only what {@code detail} reaches is real; the rest is null so a new dependency fails loudly. */
    @SuppressWarnings("NullAway") // deliberate: the null collaborators are unreachable on detail
    private BehaviorTriageSource source(FindingAdapters adapters) {
        return new BehaviorTriageSource(
                findings,
                null,
                null,
                classifiers,
                jobs,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mock(MalformedOutputDetailService.class),
                mock(SecretLeakDetailService.class),
                mock(FrustrationDetailService.class),
                null,
                mock(GroundednessDetailService.class),
                adapters);
    }

    private static FindingRow escalatedRow(String id) {
        return FindingRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT)
                .id(id)
                .projectId(PROJECT)
                .causeKey("cause:" + id)
                .subject(FindingRow.SubjectKind.CLASSIFIER, "clf_1")
                .dated("2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z")
                .sampleCount(3)
                .payload("{\"cause_kind\":\"behavior_drift\"}")
                .escalatedAt("2026-08-02T00:00:00Z")
                .build();
    }
}
