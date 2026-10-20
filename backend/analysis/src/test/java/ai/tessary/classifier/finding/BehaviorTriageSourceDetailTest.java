// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.Outcome;
import ai.tessary.agentrun.AgentRunRepository.RunSummary;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessDetailService;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView.TriageProgress;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView.TriageRunView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView.TriageStatus;
import ai.tessary.classifier.finding.BehaviorTriageJobRepository.TriageJobState;
import ai.tessary.classifier.frustration.FrustrationDetailService;
import ai.tessary.classifier.malformed.MalformedOutputDetailService;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    private final AgentRunRepository runs = mock(AgentRunRepository.class);

    private static final long COOLDOWN = 1800;

    @Test
    @DisplayName("a dead-lettered triage reads as failed on the finding's page, not in flight")
    void detail_reports_a_dead_lettered_triage_as_failed() {
        stubEscalatedFinding("f1");
        stubJob(new TriageJobState(
                "f1",
                "dead",
                5,
                5,
                null,
                null,
                "exhausted: 5 attempts (hung or crashed mid-triage)",
                "2026-08-02T01:00Z",
                null,
                COOLDOWN));

        assertEquals(TriageStatus.FAILED, statusOf("f1"));
    }

    @Test
    @DisplayName("an escalated finding with no job row is still in flight")
    void detail_reports_a_live_triage_as_in_flight() {
        stubEscalatedFinding("f1");
        when(jobs.statesByFinding(PROJECT, List.of("f1"))).thenReturn(Map.of());

        assertEquals(TriageStatus.IN_FLIGHT, statusOf("f1"));
    }

    /**
     * A finding closed while its triage waited ends with the job done and no ruling, so no job state is read; it read
     * as in flight for ever, and the page spun and polled with nothing left to run.
     */
    @Test
    void aFindingClosedWhileItsTriageWaitedStopsReadingAsRunning() {
        FindingRow closed = FindingRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT)
                .id("f1")
                .projectId(PROJECT)
                .causeKey("cause:f1")
                .subject(FindingRow.SubjectKind.CLASSIFIER, "clf_1")
                .status(FindingRow.Status.CLOSED)
                .payload("{\"cause_kind\":\"behavior_drift\"}")
                .escalatedAt("2026-08-02T00:00:00Z")
                .build();
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(closed));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        when(jobs.statesByFinding(PROJECT, List.of("f1"))).thenReturn(Map.of());

        var finding = source().detail(PROJECT, "f1").orElseThrow().finding();

        assertEquals(TriageStatus.PENDING, finding.triageStatus());
        assertNull(finding.triageProgress());
    }

    /**
     * Each state the job can be in reads as its own status, with how far the run got. The bug: every one of them read
     * as "Triaging", so a run held on a rejected key, or waiting out a retry, looked like a run going on for ever.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("jobStates")
    void detail_reports_where_the_run_stands(
            String why, TriageJobState job, String expectedStatus, TriageProgress expectedProgress) {
        stubEscalatedFinding("f1");
        stubJob(job);

        var finding = source().detail(PROJECT, "f1").orElseThrow().finding();

        assertEquals(expectedStatus, finding.triageStatus());
        assertEquals(expectedProgress, finding.triageProgress());
    }

    static Stream<Arguments> jobStates() {
        String at = "2026-08-02T01:00:00Z";
        String due = "2026-08-02T01:00:30Z";
        return Stream.of(
                Arguments.of(
                        "a queued finding says when it was queued",
                        new TriageJobState("f1", "pending", 0, 5, null, null, null, at, null, COOLDOWN),
                        TriageStatus.QUEUED,
                        new TriageProgress(0, 5, null, null, at, null, null, COOLDOWN)),
                Arguments.of(
                        "a worker holds the lease",
                        new TriageJobState("f1", "claimed", 2, 5, "worker-a", null, "kind=timeout", at, due, COOLDOWN),
                        TriageStatus.IN_FLIGHT,
                        new TriageProgress(2, 5, at, null, null, null, null, COOLDOWN)),
                Arguments.of(
                        "a retrying finding says when it retries",
                        new TriageJobState("f1", "claimed", 1, 5, null, null, "kind=timeout", at, due, COOLDOWN),
                        TriageStatus.RETRYING,
                        new TriageProgress(1, 5, null, null, null, due, null, COOLDOWN)),
                Arguments.of(
                        "a run that spent the last attempt reads failed before the sweep dead-letters it, not attempt"
                                + " 6 of 5",
                        new TriageJobState("f1", "claimed", 5, 5, null, null, "kind=timeout", at, due, COOLDOWN),
                        TriageStatus.FAILED,
                        new TriageProgress(5, 5, null, null, null, null, "2026-08-02T01:30:00Z", COOLDOWN)),
                Arguments.of(
                        "held until the key is fixed",
                        new TriageJobState(
                                "f1",
                                "claimed",
                                0,
                                5,
                                null,
                                "CLASSIFIER.TRIAGE_PROVIDER_REJECTED",
                                "invalid key",
                                at,
                                due,
                                COOLDOWN),
                        TriageStatus.BLOCKED,
                        new TriageProgress(
                                0, 5, null, "CLASSIFIER.TRIAGE_PROVIDER_REJECTED", null, null, null, COOLDOWN)),
                Arguments.of(
                        "a dead finding says when it can run again, and a stale hold does not hide that it gave up",
                        new TriageJobState(
                                "f1",
                                "dead",
                                5,
                                5,
                                null,
                                "CLASSIFIER.TRIAGE_PROVIDER_REJECTED",
                                "x",
                                at,
                                null,
                                COOLDOWN),
                        TriageStatus.FAILED,
                        new TriageProgress(5, 5, null, null, null, null, "2026-08-02T01:30:00Z", COOLDOWN)),
                Arguments.of(
                        "a dead job whose time cannot be read gives no revival time rather than failing the page",
                        new TriageJobState("f1", "dead", 5, 5, null, null, "x", "not a time", null, COOLDOWN),
                        TriageStatus.FAILED,
                        new TriageProgress(5, 5, null, null, null, null, null, COOLDOWN)));
    }

    /**
     * The finding carries its newest run's step count and the words of the step running now, and a running triage
     * says it started when its run did, not when the job was claimed (a live line with no step, or an elapsed time that
     * counts the queue).
     */
    @Test
    void theFindingCarriesItsNewestRunsStepAndCurrentWords() {
        stubEscalatedFinding("f1");
        stubJob(new TriageJobState(
                "f1", "claimed", 2, 5, "worker-a", null, null, "2026-08-02T01:00:00Z", null, COOLDOWN));
        stubRun(run("2026-08-02T01:00:02Z", null, null, null));

        var finding = source().detail(PROJECT, "f1").orElseThrow().finding();

        assertEquals(
                new TriageRunView(
                        "run-2", 2, "2026-08-02T01:00:02Z", null, 7, "Reading flagged answers 51 to 58", null, null),
                finding.triageRun());
        assertEquals("2026-08-02T01:00:02Z", requireProgress(finding).startedAt());
    }

    /**
     * In the moment between the claim and the run's opening, the newest run is the last attempt's, already closed: the
     * claim's time is when this attempt started (an elapsed time counted from an older attempt).
     */
    @Test
    void aRunningTriageWhoseRunHasNotOpenedCountsFromTheClaim() {
        stubEscalatedFinding("f1");
        stubJob(new TriageJobState(
                "f1", "claimed", 2, 5, "worker-a", null, null, "2026-08-02T01:00:00Z", null, COOLDOWN));
        stubRun(run(
                "2026-08-02T00:50:00Z", "2026-08-02T00:55:00Z", Outcome.RETRYING, "CLASSIFIER.TRIAGE_RUN_INCOMPLETE"));

        var finding = source().detail(PROJECT, "f1").orElseThrow().finding();

        assertEquals("2026-08-02T01:00:00Z", requireProgress(finding).startedAt());
        assertEquals(
                "retrying",
                java.util.Objects.requireNonNull(finding.triageRun()).outcome());
        assertEquals(
                "CLASSIFIER.TRIAGE_RUN_INCOMPLETE",
                java.util.Objects.requireNonNull(finding.triageRun()).stopCode());
    }

    /** The findings list reads every row's newest run in the same page read (a page with no live line). */
    @Test
    void theListCarriesEachFindingsNewestRun() {
        FindingRow row = escalatedRow("f1");
        when(findings.listByProject(PROJECT, null, null, null, false, 200)).thenReturn(List.of(row));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        when(jobs.statesByFinding(PROJECT, List.of("f1"))).thenReturn(Map.of());
        stubRun(run("2026-08-02T01:00:02Z", null, null, null));

        var listed = source().list(PROJECT, null, null, null, false);

        assertEquals(
                "run-2",
                java.util.Objects.requireNonNull(listed.getFirst().triageRun()).id());
    }

    /** The RCA view of a finding carries no triage run: its step words say what triage read (triage leaking into RCA). */
    @Test
    void theRcaViewOfAFindingCarriesNoTriageRun() {
        stubEscalatedFinding("f1");
        when(jobs.statesByFinding(PROJECT, List.of("f1"))).thenReturn(Map.of());
        stubRun(run("2026-08-02T01:00:02Z", null, null, null));

        var finding = source().detail(PROJECT, "f1").orElseThrow().finding();

        assertEquals(
                "run-2", java.util.Objects.requireNonNull(finding.triageRun()).id());
        assertNull(finding.withoutTriage().triageRun());
    }

    private static TriageProgress requireProgress(BehaviorDtos.BehaviorFindingView finding) {
        return java.util.Objects.requireNonNull(finding.triageProgress());
    }

    private static RunSummary run(
            String startedAt, @Nullable String endedAt, @Nullable Outcome outcome, @Nullable String stopCode) {
        return new RunSummary(
                "run-2",
                AgentRunRepository.Lane.TRIAGE,
                "f1",
                2,
                startedAt,
                endedAt,
                outcome,
                stopCode,
                7,
                endedAt == null ? "Reading flagged answers 51 to 58" : null,
                "s-1");
    }

    private void stubRun(RunSummary run) {
        when(runs.latestBySubjects(PROJECT, AgentRunRepository.Lane.TRIAGE, List.of(run.subjectId())))
                .thenReturn(Map.of(run.subjectId(), run));
    }

    private void stubJob(TriageJobState job) {
        when(jobs.statesByFinding(PROJECT, List.of(job.findingId()))).thenReturn(Map.of(job.findingId(), job));
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
        when(jobs.statesByFinding(PROJECT, List.of(id))).thenReturn(Map.of());
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
                adapters,
                runs);
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
