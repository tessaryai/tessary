// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseDetection;
import ai.tessary.cases.CaseKey;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
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
// Static @TestPropertySource, not @DynamicPropertySource: a dynamic registration keys the context
// cache on the declaring Method rather than on the value, which forks a context per class instead of
// letting this one and RcaWorkerTest share the one parked context they both want.
@SpringBootTest
@TestPropertySource(properties = {"tessary.rca.batch-size=0", "tessary.rca.heartbeat-ms=3600000"})
class RcaControllerTest {

    @Autowired
    RcaController controller;

    @Autowired
    CaseService cases;

    @Autowired
    CaseRepository caseRows;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FindingRepository findings;

    @Autowired
    RcaTriggerService trigger;

    @Autowired
    RcaJobRepository jobs;

    @Autowired
    RcaReportRepository reports;

    /** The finding shape these fixtures file: a classifier's armed window, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** One open classifier finding — the subject an RCA is about. */
    private String seedFinding(String projectId, String causeKey) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        projectId,
                        BuiltInDetector.Kind.SECRET_LEAK,
                        "clf-" + causeKey,
                        causeKey,
                        1,
                        "cs_extract",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }

    /** The case a person reads before pressing — opened on the finding, exactly as triage opens it. */
    private String seedCase(String projectId, String findingId) {
        return caseRows.open(
                        projectId,
                        new CaseDetection(
                                new CaseKey(
                                        CaseRow.Detector.CLASSIFIER,
                                        CaseRow.SubjectKind.CLASSIFIER,
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
        var job = jdbc.sql("SELECT payload->>'created_by' AS created_by, payload->>'finding_id' AS finding_id"
                        + " FROM job WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", first.jobId())
                .query()
                .singleRow();
        assertEquals(fix.user().id(), job.get("created_by"));
        assertEquals(findingId, job.get("finding_id"));

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

    /**
     * Re-running a report. Catches a re-run while the first analysis is still queued starting a duplicate, a
     * re-run of a finished (here failed) report coalescing back onto it instead of snapshotting the finding
     * afresh, and a report whose finding link is gone being re-run against nothing.
     */
    @Test
    void aRerunWaitsOnARunningReportAndSnapshotsAFinishedOneAfresh() {
        var fix = TenantFixture.bootstrap(tenants, "rca-rerun");
        var ctx = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        String projectId = fix.project().id();
        String findingId = seedFinding(projectId, "cause-rerun");
        RcaReportView first = cases.runRca(
                projectId, seedCase(projectId, findingId), fix.user().id());

        RcaReportView whilePending = Objects.requireNonNull(controller
                .rerun(ctx, fix.org().slug(), fix.project().slug(), first.id())
                .data());
        assertEquals(first.id(), whilePending.id(), "a queued analysis is handed back, not duplicated");

        jobs.markFailed(first.jobId(), "launcher down", 3);
        RcaReportView fresh = Objects.requireNonNull(controller
                .rerun(ctx, fix.org().slug(), fix.project().slug(), first.id())
                .data());
        assertNotEquals(first.id(), fresh.id());
        assertNotEquals(first.jobId(), fresh.jobId());
        assertEquals("pending", fresh.status());
        assertEquals(Optional.of(findingId), reports.findingIdOf(projectId, fresh.jobId()));

        jobs.markFailed(fresh.jobId(), "launcher down", 3);
        jdbc.sql("UPDATE rca_report SET finding_id = NULL WHERE project_id = :pid AND job_id = :jobId")
                .param("pid", projectId)
                .param("jobId", fresh.jobId())
                .update();
        TessaryException orphan = assertThrows(
                TessaryException.class,
                () -> controller.rerun(ctx, fix.org().slug(), fix.project().slug(), fresh.id()));
        assertEquals(RcaError.SUBJECT_NOT_FOUND, orphan.error());
    }

    /**
     * What the Triage queue captions a case with. Catches a running analysis being read as a conclusion, a
     * finished one whose verdict or leading hypothesis is not carried back to its case, and an empty page
     * reaching Postgres as {@code IN ()}, a syntax error that fails the whole queue read.
     */
    @Test
    void aCasesLeadIsItsFinishedAnalysis() {
        var fix = TenantFixture.bootstrap(tenants, "rca-leads");
        String projectId = fix.project().id();
        String caseId = seedCase(projectId, seedFinding(projectId, "cause-leads"));
        RcaReportView report = cases.runRca(projectId, caseId, fix.user().id());

        assertEquals(Map.of(), reports.leadsByCase(projectId, List.of(caseId)), "a queued analysis concluded nothing");

        reports.complete(
                report.jobId(),
                "done",
                RcaReportRow.Verdict.MODEL_CHANGE,
                "summary",
                "[]",
                "[{\"title\":\"A canary model\",\"confidence\":\"high\",\"rationale\":\"r\","
                        + "\"evidence_trace_ids\":[]}]",
                null,
                "## r",
                true);
        jobs.markDone(report.jobId());

        assertEquals(
                Map.of(caseId, new RcaReportRepository.CaseLead(RcaReportRow.Verdict.MODEL_CHANGE, "A canary model")),
                reports.leadsByCase(projectId, List.of(caseId)));
        assertEquals(Map.of(), reports.leadsByCase(projectId, List.of()));
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
