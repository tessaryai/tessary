// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaJobRepository;
import ai.tessary.rca.RcaReportRepository;
import ai.tessary.rca.RcaReportRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Case lifecycle as a human drives it: resolve, mute, unmute, how a case is looked up, and what
 *  pressing RCA on one does (1c). */
@SpringBootTest
// batch-size=0 parks RcaWorker's own drain (claimBatch's LIMIT 0 returns nothing), the same reason
// RcaControllerTest does it: this class presses runRca and must read back locked_at / the trail line
// itself, not race the real worker picking the job up first.
@TestPropertySource(properties = {"test.context-group=case-service", "tessary.rca.batch-size=0"})
class CaseServiceTest {

    @Autowired
    FindingRepository findings;

    @Autowired
    CaseService service;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseLedger ledger;

    @Autowired
    CaseEventRepository events;

    @Autowired
    RcaJobRepository rcaJobs;

    @Autowired
    RcaReportRepository rcaReports;

    @Autowired
    TenantService tenants;

    @Test
    void resolvingRequiresAReasonAndKeepsIt() {
        Project p = project("svc-resolve");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);

        assertThrows(TessaryException.class, () -> service.resolve(p.id(), row.id(), "  ", "priya@example.com", null));

        service.resolve(p.id(), row.id(), "traffic mix shifted", "priya@example.com", null);
        CaseRow closed = cases.findById(p.id(), row.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, closed.state());
        assertEquals("traffic mix shifted", closed.resolutionReason());
        assertEquals("priya@example.com", closed.resolvedBy());
    }

    @Test
    void resolvingAnAlreadyClosedCaseIsRefused() {
        Project p = project("svc-double-resolve");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);
        service.resolve(p.id(), row.id(), "done", "priya@example.com", null);

        assertThrows(
                TessaryException.class, () -> service.resolve(p.id(), row.id(), "again", "priya@example.com", null));
    }

    @Test
    void muteIsIdempotentAndDoesNotNarrateItselfTwice() {
        Project p = project("svc-mute");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);

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
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);
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
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);

        assertEquals(row.id(), service.detail(p.id(), row.id()).caseView().id());
        assertEquals(
                row.id(), service.detail(p.id(), row.reference()).caseView().id());
    }

    @Test
    void anotherProjectsCaseIsNotFound() {
        Project mine = project("svc-tenant-a");
        Project theirs = project("svc-tenant-b");
        CaseRow row = open(mine, CaseRow.Detector.CLASSIFIER);

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
                service.detail(p.id(), open(p, CaseRow.Detector.CLASSIFIER).id());
        CaseDetailView toolError =
                service.detail(p.id(), open(p, CaseRow.Detector.TOOL_ERROR).id());

        assertTrue(drift.rcaAvailable());
        assertNotNull(drift.latestFindingId());
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
        CaseRow drift = open(p, CaseRow.Detector.CLASSIFIER);
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
        open(p, CaseRow.Detector.CLASSIFIER);

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
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);
        String findingId = Objects.requireNonNull(row.latestFindingId());
        String jobId = rcaJobs.createOrGet(
                p.id(),
                findingId,
                CaseRow.SubjectKind.CLASSIFIER,
                "profile-1",
                "pass_rate",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-08T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:00Z"),
                "priya@example.com",
                null);
        rcaReports.insertPendingIfAbsent(
                p.id(),
                jobId,
                findingId,
                CaseRow.SubjectKind.CLASSIFIER,
                "profile-1",
                "Checkout summariser",
                "cs-a",
                "pass_rate",
                RcaReportRow.ReportKind.METRIC_MOVEMENT,
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

    // ---- 1c: RCA locks a case --------------------------------------------------------------

    @Test
    void runRcaLocksTheCaseAndWritesRcaRequested() {
        Project p = project("svc-rca-locks");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);

        service.runRca(p.id(), row.id(), "priya@example.com");

        CaseRow locked = cases.findById(p.id(), row.id()).orElseThrow();
        assertNotNull(locked.lockedAt());
        assertTrue(kinds(p, row).contains(CaseEventRow.Kind.RCA_REQUESTED));
    }

    /** Re-pressing a locked case must not re-stamp the lock or narrate the press twice. */
    @Test
    void rePressingALockedCaseCoalescesOntoTheSameReportAndDoesNotReLockOrReNarrate() {
        Project p = project("svc-rca-re-press");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);

        RcaReportView first = service.runRca(p.id(), row.id(), "priya@example.com");
        String lockedAt = cases.findById(p.id(), row.id()).orElseThrow().lockedAt();
        RcaReportView second = service.runRca(p.id(), row.id(), "priya@example.com");

        assertEquals(first.id(), second.id(), "the same finding coalesces onto one report");
        assertEquals(lockedAt, cases.findById(p.id(), row.id()).orElseThrow().lockedAt(), "the lock does not move");
        assertEquals(
                1,
                kinds(p, row).stream()
                        .filter(CaseEventRow.Kind.RCA_REQUESTED::equals)
                        .count(),
                "only the locking press narrates the request");
    }

    /** A locked case's key opens a NEW case rather than joining the locked one. */
    @Test
    void aPositiveForALockedCasesKeyOpensAFreshCase() {
        Project p = project("svc-rca-locked-key");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);
        service.runRca(p.id(), row.id(), "priya@example.com");

        CaseDetection detection = new CaseDetection(
                new CaseKey(row.detector(), row.subjectKind(), row.subjectId(), row.metric()),
                "subject label",
                null,
                findingBehind(p, row.detector()),
                "something happened again",
                "because the detector said so",
                0.4,
                Instant.parse("2026-07-05T10:00:00Z"),
                0.55,
                0.95,
                -0.4);
        CaseRow secondCase = ledger.openOrJoin(p.id(), detection, null, Instant.now());

        assertNotEquals(row.id(), secondCase.id(), "the locked case is never joined");
        assertEquals(
                CaseRow.State.OPEN,
                cases.findById(p.id(), row.id()).orElseThrow().state(),
                "the locked case itself is untouched");
    }

    // ---- resolving/absorbing closes every finding the case holds ---------------------------

    @Test
    void resolvingClosesEveryOpenFindingTheCaseHolds() {
        Project p = project("svc-resolve-closes-findings");
        CaseRow row = open(p, CaseRow.Detector.CLASSIFIER);
        String findingId = Objects.requireNonNull(row.latestFindingId());

        service.resolve(p.id(), row.id(), "shipped a fix", "priya@example.com", null);

        assertEquals(
                FindingRow.Status.CLOSED,
                findings.findById(p.id(), findingId).orElseThrow().status());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    private CaseRow open(Project p, String detector) {
        String subjectKind =
                switch (detector) {
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

    /** The finding shape these fixtures file: a classifier's armed window, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** Every case opened after cutover points at a finding — the forward CHECK requires it. */
    private String findingBehind(Project p, String detector) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        p.id(),
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + detector,
                        "cause-" + detector,
                        1,
                        "cs-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }

    private List<String> kinds(Project p, CaseRow row) {
        return events.listByCase(p.id(), row.id()).stream()
                .map(CaseEventRow::kind)
                .toList();
    }
}
