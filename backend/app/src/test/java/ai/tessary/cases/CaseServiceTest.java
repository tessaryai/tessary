// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.plan.Capability;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaJobRepository;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Case lifecycle as a human drives it: resolve, mute, unmute, and how a case is looked up. */
@SpringBootTest
// Own context on purpose: CaseWorker's sweep is parked here, and the lifecycle assertions depend on no other writer
// touching the case rows.
@TestPropertySource(properties = "test.context-group=case-service")
class CaseServiceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.cases.heartbeat-ms", () -> "3600000");
    }

    @Autowired
    FindingRepository findings;

    @Autowired
    CaseService service;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseEventRepository events;

    @Autowired
    RcaJobRepository rcaJobs;

    @Autowired
    RcaReportRepository rcaReports;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Test
    void resolvingRequiresAReasonAndKeepsIt() {
        Project p = project("svc-resolve");
        CaseRow row = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);

        assertThrows(TessaryException.class, () -> service.resolve(p.id(), row.id(), "  ", "priya@example.com"));

        service.resolve(p.id(), row.id(), "traffic mix shifted", "priya@example.com");
        CaseRow closed = cases.findById(p.id(), row.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, closed.state());
        assertEquals("traffic mix shifted", closed.resolutionReason());
        assertEquals("priya@example.com", closed.resolvedBy());
    }

    @Test
    void resolvingAnAlreadyClosedCaseIsRefused() {
        Project p = project("svc-double-resolve");
        CaseRow row = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);
        service.resolve(p.id(), row.id(), "done", "priya@example.com");

        assertThrows(TessaryException.class, () -> service.resolve(p.id(), row.id(), "again", "priya@example.com"));
    }

    @Test
    void muteIsIdempotentAndDoesNotNarrateItselfTwice() {
        Project p = project("svc-mute");
        CaseRow row = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);

        service.mute(p.id(), row.id(), "priya@example.com");
        service.mute(p.id(), row.id(), "sam@example.com");

        assertEquals(
                CaseRow.State.MUTED,
                cases.findById(p.id(), row.id()).orElseThrow().state());
        assertEquals(
                1,
                kinds(p, row).stream().filter(CaseEventRow.Kind.MUTED::equals).count(),
                "two people reaching for mute is ordinary; a second trail line is not");
    }

    @Test
    void unmuteReturnsTheCaseToOpen() {
        Project p = project("svc-unmute");
        CaseRow row = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);
        service.mute(p.id(), row.id(), "priya@example.com");

        service.unmute(p.id(), row.id(), "priya@example.com");

        assertEquals(
                CaseRow.State.OPEN,
                cases.findById(p.id(), row.id()).orElseThrow().state());
        assertTrue(kinds(p, row).contains(CaseEventRow.Kind.UNMUTED));
    }

    @Test
    void aCaseResolvesByItsStoredIdOrTheNumberAHumanQuotes() {
        Project p = project("svc-lookup");
        CaseRow row = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);

        assertEquals(row.id(), service.detail(p.id(), row.id()).caseView().id());
        assertEquals(
                row.id(), service.detail(p.id(), row.reference()).caseView().id());
    }

    @Test
    void anotherProjectsCaseIsNotFound() {
        Project mine = project("svc-tenant-a");
        Project theirs = project("svc-tenant-b");
        CaseRow row = open(mine, CaseRow.Detector.BEHAVIOR_DRIFT);

        assertThrows(TessaryException.class, () -> service.detail(theirs.id(), row.id()));
    }

    /**
     * The RCA affordance is a server-side fact, not a detector string the client enumerates. RCA is a
     * finding-analysis lane now, so what decides it is whether there IS a finding — which is true of
     * every detector that still opens cases, and false only for the archived rows of the two retired
     * ones. A client comparing {@code detector} against a hardcoded list would have to be edited every
     * time a detector is added.
     */
    @Test
    void rcaIsOfferedWhereThereIsAFindingToAnalyse() {
        Project p = project("svc-rca-available");

        CaseDetailView drift =
                service.detail(p.id(), open(p, CaseRow.Detector.BEHAVIOR_DRIFT).id());
        CaseDetailView toolError =
                service.detail(p.id(), open(p, CaseRow.Detector.TOOL_ERROR).id());

        assertTrue(drift.rcaAvailable());
        assertNotNull(drift.findingId());
        assertTrue(toolError.rcaAvailable());
    }

    /**
     * The paged read walks the whole set with the cursor it hands back, and stops by handing back none.
     *
     * <p>Asserted as a set rather than a sequence on purpose: what a page must guarantee is that every case
     * appears exactly once across the walk. The worst-first ORDER is a repository claim, pinned in
     * {@link CaseRepositoryIntegrationTest} against timestamps chosen to make ties bite; re-asserting it here
     * off two cases opened microseconds apart would be a test of the clock.
     */
    @Test
    void pagingWalksEveryOpenCaseExactlyOnceAndThenStops() {
        Project p = project("svc-page-walk");
        CaseRow drift = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);
        CaseRow toolError = open(p, CaseRow.Detector.TOOL_ERROR);

        CasesPage first = service.page(p.id(), CaseRow.State.OPEN, null, null, 1, null);
        assertEquals(1, first.cases().size());
        assertNotNull(first.nextCursor(), "two cases and a page of one: there is a next page");

        CasesPage second = service.page(p.id(), CaseRow.State.OPEN, null, null, 1, first.nextCursor());
        assertEquals(1, second.cases().size());
        assertNull(second.nextCursor(), "the walk ends without a probing empty page");

        assertEquals(
                Set.of(drift.id(), toolError.id()),
                Set.of(first.cases().get(0).id(), second.cases().get(0).id()),
                "every case exactly once across the walk");
    }

    /** A detector filter narrows the page; nothing else in the project comes along. */
    @Test
    void pagingNarrowsToOneDetector() {
        Project p = project("svc-page-filter");
        CaseRow toolError = open(p, CaseRow.Detector.TOOL_ERROR);
        open(p, CaseRow.Detector.BEHAVIOR_DRIFT);

        CasesPage page = service.page(p.id(), CaseRow.State.OPEN, CaseRow.Detector.TOOL_ERROR, null, 50, null);

        assertEquals(
                List.of(toolError.id()), page.cases().stream().map(CaseView::id).toList());
    }

    /**
     * A finished RCA report is the case page's answer to "why is this open," so it arrives inline.
     *
     * <p>The pending half of this test is the load-bearing half. A report's shell is inserted at
     * trigger time and carries nothing (no verdict, no hypotheses, no write-up), so inlining it
     * would render an object whose every interesting field is null, indistinguishable from an
     * analysis that concluded nothing. While it runs, the id is the whole answer: it is what a poll
     * is for.
     */
    @Test
    void aFinishedRcaReportIsInlinedAndAPendingOneIsOnlyNamed() {
        Project p = project("svc-rca-inline");
        CaseRow row = open(p, CaseRow.Detector.BEHAVIOR_DRIFT);
        String findingId = Objects.requireNonNull(row.findingId());
        String jobId = rcaJobs.createOrGet(
                p.id(),
                findingId,
                CaseRow.SubjectKind.BEHAVIOR_PROFILE,
                "profile-1",
                "pass_rate",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-08T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:00Z"),
                "priya@example.com");
        rcaReports.insertPendingIfAbsent(
                p.id(),
                jobId,
                findingId,
                CaseRow.SubjectKind.BEHAVIOR_PROFILE,
                "profile-1",
                "Checkout summariser",
                "cs-a",
                "pass_rate",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-08T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:00Z"),
                0.55,
                0.95,
                -0.4,
                RcaReportRow.Engine.AGENTIC);

        CaseDetailView running = service.detail(p.id(), row.id());
        assertNotNull(running.rcaReportId(), "the id is there to poll");
        assertNull(running.rca(), "a shell must not render as a report");
        assertTrue(running.rcaAvailable());

        rcaReports.complete(
                jobId,
                JobRow.Status.DONE,
                RcaReportRow.Verdict.MODEL_CHANGE,
                "The judge model changed mid-window.",
                null,
                null,
                "## Why\nThe provider rotated the default.",
                true);
        // The wire status is the JOB's, so the queue is what has to say "finished" — the report's own column
        // alone would leave an exhaustion-swept job reading as claimed forever.
        rcaJobs.markDone(jobId);

        CaseDetailView finished = service.detail(p.id(), row.id());
        RcaReportView report = finished.rca();
        assertNotNull(report, "a completed report belongs on the case page, whole");
        assertEquals(finished.rcaReportId(), report.id(), "the inlined report is the one the id names");
        assertEquals(RcaReportRow.Verdict.MODEL_CHANGE, report.verdict());
        assertEquals("## Why\nThe provider rotated the default.", report.detailedReport());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Project project(String name) {
        return bootstrapGranted(name).project();
    }

    private CaseRow open(Project p, String detector) {
        String subjectKind =
                switch (detector) {
                    case CaseRow.Detector.BEHAVIOR_DRIFT -> CaseRow.SubjectKind.BEHAVIOR_PROFILE;
                    case CaseRow.Detector.TOOL_ERROR -> CaseRow.SubjectKind.TOOL;
                    default -> CaseRow.SubjectKind.CLASSIFIER;
                };
        CaseDetection detection = new CaseDetection(
                new CaseKey(detector, subjectKind, "subject-" + detector, "pass_rate"),
                "subject label",
                null,
                // Every case points at a finding; the forward CHECK on eval_case enforces it. The two
                // detectors that open one without a finding are exempt by name, so seeding a real
                // finding for the rest is the invariant, not test scaffolding.
                findingBehind(p, detector),
                "something happened",
                "because the detector said so",
                0.4,
                Instant.parse("2026-07-01T10:00:00Z"),
                0.55,
                0.95,
                -0.4);
        return cases.open(p.id(), detection, Instant.now()).orElseThrow();
    }

    /** Every case opened after cutover points at a finding — the forward CHECK requires it. */
    private String findingBehind(Project p, String detector) {
        String now = Instant.now().toString();
        return findings.recordFiring(
                        Ids.ulid(),
                        p.id(),
                        "profile-" + detector,
                        FindingRow.Cause.NOVELTY,
                        "cause-" + detector,
                        FindingRow.GLOBAL_WORKFLOW,
                        1,
                        null,
                        null,
                        "cs-a",
                        now)
                .findingId();
    }

    private List<String> kinds(Project p, CaseRow row) {
        return events.listByCase(p.id(), row.id()).stream()
                .map(CaseEventRow::kind)
                .toList();
    }

    /**
     * Bootstrap a tenant whose org has behavior drift switched on before its project is created.
     *
     * <p>Behavior drift defaults off, so without a grant these cases would assert the capability
     * default rather than the behaviour they name. The grant must precede the project because
     * project creation is what seeds the built-in classifiers: grant afterwards and the classifier
     * row is never inserted, leaving the test hunting findings from a classifier the project does
     * not have.
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.BEHAVIOR_DRIFT);
        });
    }
}
