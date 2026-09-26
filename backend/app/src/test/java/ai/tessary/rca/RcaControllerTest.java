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
 * The RCA surface against a real tenant. The press is on the case ({@code POST /cases/{id}/rca}): it resolves the
 * finding behind the case and nothing else crosses into the lane, re-presses coalesce onto one report, a case whose
 * finding is gone is a 404, and reports are project-scoped.
 */
// batch-size=0 parks the drain, as in RcaWorkerTest: the worker would otherwise claim the job before the `pending`
// read, and @Scheduled(fixedDelay) fires at startup. Static @TestPropertySource so both classes share one cached
// context.
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

    /** One open classifier finding, the RCA's subject. */
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

    /** The case a person reads before pressing, opened as triage opens it. */
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

        // The press records who pressed and the finding id, the whole of what crosses into the lane.
        var job = jdbc.sql("SELECT payload->>'created_by' AS created_by, payload->>'finding_id' AS finding_id"
                        + " FROM job WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", first.jobId())
                .query()
                .singleRow();
        assertEquals(fix.user().id(), job.get("created_by"));
        assertEquals(findingId, job.get("finding_id"));

        // A second press resolves to the same report.
        RcaReportView second = cases.runRca(projectId, caseId, fix.user().id());
        assertEquals(first.id(), second.id());
        assertEquals(first.jobId(), second.jobId());

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

    /** An unknown case is a case 404, and a finding id resolving to nothing an RCA 404; never an enqueued job. */
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
     * A re-run while queued does not duplicate, a re-run of a finished report snapshots afresh, and a report whose
     * finding is gone is not re-run.
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
     * Triage queue captions: a running analysis is not a conclusion, a finished verdict reaches its case, and an
     * empty page never reaches Postgres as {@code IN ()}.
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

        assertThrows(
                TessaryException.class,
                () -> controller.get(ctxB, b.org().slug(), b.project().slug(), report.id()));
    }
}
