// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseDetection;
import ai.tessary.cases.CaseKey;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Web-layer acceptance for the RCA surface, driven against a real bootstrapped tenant
 * ({@code requireProject} exercised for real).
 *
 * <p><b>The press is on the CASE</b> — {@code POST /cases/{id}/rca} — and this test presses it there,
 * because the trigger this class used to call no longer exists. What it pins: the press resolves the
 * finding behind the case and NOTHING else crosses into the lane; re-presses coalesce onto one report
 * (the dedupe grain); a case whose finding has gone is a 404 rather than a fabricated report; and
 * reports are project-scoped on the way back out.
 */
// Parks the scheduled drain, exactly as RcaWorkerTest does and for the same reason: scheduling is
// live in @SpringBootTest, so the worker can claim the job this test just enqueued before the
// `pending` assertion reads it back, turning the status into `claimed`. batch-size=0 is what parks
// it (claimBatch's LIMIT 0 returns nothing); the long heartbeat cannot park it alone, because
// @Scheduled(fixedDelay) has no initial delay and the first tick fires at context startup.
//
// Static @TestPropertySource, not the @DynamicPropertySource below: dynamic properties are NOT part
// of the context cache key, so a parking value registered there would silently not apply whenever
// another test class built the shared context first.
@SpringBootTest
@TestPropertySource(properties = {"tessary.rca.batch-size=0", "tessary.rca.heartbeat-ms=3600000"})
class RcaControllerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    RcaController controller;

    @Autowired
    CaseService cases;

    @Autowired
    CaseRepository caseRows;

    @Autowired
    TenantService tenants;

    @Autowired
    RcaJobRepository jobs;

    @Autowired
    FindingRepository findings;

    @Autowired
    RcaTriggerService trigger;

    /** One open behaviour-drift finding — the subject an RCA is about. */
    private String seedFinding(String projectId, String causeKey) {
        return findings.recordFiring(
                        Ids.ulid(),
                        projectId,
                        "profile-1",
                        FindingRow.Cause.NOVELTY,
                        causeKey,
                        FindingRow.GLOBAL_WORKFLOW,
                        1,
                        null,
                        null,
                        "cs_extract",
                        Instant.now().toString())
                .findingId();
    }

    /** The case a person reads before pressing — opened on the finding, exactly as triage opens it. */
    private String seedCase(String projectId, String findingId) {
        return caseRows.open(
                        projectId,
                        new CaseDetection(
                                new CaseKey(
                                        CaseRow.Detector.BEHAVIOR_DRIFT,
                                        CaseRow.SubjectKind.BEHAVIOR_PROFILE,
                                        "profile-1",
                                        "novelty"),
                                "extraction",
                                "cs_extract",
                                findingId,
                                "A step the agent always performs was skipped",
                                "seen on 1 trace",
                                0.4,
                                Instant.parse("2026-07-01T10:00:00Z"),
                                null,
                                null,
                                null),
                        Instant.now())
                .orElseThrow()
                .id();
    }

    @Test
    void pressingRcaOnACaseSnapshotsItsFindingAndRepressesCoalesce() {
        var fix = TenantFixture.bootstrap(tenants, "rca-api");
        var ctx = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        String projectId = fix.project().id();
        String findingId = seedFinding(projectId, "cause-a");
        String caseId = seedCase(projectId, findingId);

        RcaReportView first = cases.runRca(projectId, caseId, fix.user().id());
        assertEquals("pending", first.status());
        assertEquals("cs_extract", first.callSiteId());

        // The press records who pressed — the principal the lane's ephemeral MCP key is issued to —
        // and the finding id, which is the whole of what crosses into it.
        var job = jobs.findById(projectId, first.jobId()).orElseThrow();
        assertEquals(fix.user().id(), job.createdBy());
        assertEquals(findingId, job.findingId());

        // Same case, second press — must resolve to the SAME report, not a duplicate analysis.
        RcaReportView second = cases.runRca(projectId, caseId, fix.user().id());
        assertEquals(first.id(), second.id());
        assertEquals(first.jobId(), second.jobId());

        // The report reads back by id, and the list surfaces it.
        assertEquals(
                first.id(),
                controller
                        .get(ctx, fix.org().slug(), fix.project().slug(), first.id())
                        .data()
                        .id());
        assertEquals(
                1,
                controller
                        .list(ctx, fix.org().slug(), fix.project().slug(), 50)
                        .data()
                        .size());
    }

    /** Neither half of the press may invent a subject: an unknown case is a case 404, and a finding id
     *  that resolves to nothing is an RCA 404 — never an enqueued job with nothing to analyse. */
    @Test
    void anUnknownCaseOrFindingIsRejected() {
        var fix = TenantFixture.bootstrap(tenants, "rca-api-nofinding");
        String projectId = fix.project().id();

        TessaryException unknownCase = assertThrows(
                TessaryException.class,
                () -> cases.runRca(projectId, "cs_does_not_exist", fix.user().id()));
        assertEquals("CASE.NOT_FOUND", unknownCase.error().code());

        TessaryException unknownFinding = assertThrows(
                TessaryException.class,
                () -> trigger.trigger(
                        projectId, "fnd_does_not_exist", fix.user().id(), null));
        assertEquals(RcaError.SUBJECT_NOT_FOUND, unknownFinding.error());
    }

    @Test
    void reportsAreProjectScoped() {
        var a = TenantFixture.bootstrap(tenants, "rca-api-iso-a");
        var b = TenantFixture.bootstrap(tenants, "rca-api-iso-b");
        var ctxB = new TenantContext(b.user().id(), b.user().email(), null, null, null, null);
        String findingId = seedFinding(a.project().id(), "cause-c");
        String caseId = seedCase(a.project().id(), findingId);

        RcaReportView report = cases.runRca(a.project().id(), caseId, a.user().id());

        // Project B cannot read project A's report through its own tenant path.
        assertThrows(
                TessaryException.class,
                () -> controller.get(ctxB, b.org().slug(), b.project().slug(), report.id()));
    }
}
