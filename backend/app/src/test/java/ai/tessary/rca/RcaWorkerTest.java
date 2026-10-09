// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RcaParkedSpringBootTest;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The RCA worker and pipeline against real Postgres with only {@link AgenticRcaEngine} mocked: the evidence the run
 * may cite and how much was flagged come from the finding's evidence rows by role, never by time; the dossier is the
 * claim, the numbers, the classifier's method and the tools; the verdict, causes and ruled-out sentences persist; an
 * engine failure stamps {@code failed}.
 */
@RcaParkedSpringBootTest
class RcaWorkerTest {

    @Autowired
    RcaWorker worker;

    @Autowired
    RcaJobRepository jobs;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    RcaReportRepository reports;

    @Autowired
    PipelineService pipelines;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AgenticRcaEngine engine;

    private static final Instant TO = Instant.parse("2026-07-15T12:00:00Z");
    private static final Instant SPLIT = TO.minus(Duration.ofHours(24));
    private static final Instant FROM = SPLIT.minus(Duration.ofHours(24));

    /**
     * When a classifier writes both halves of a fraction ({@code tool_error}: member for all calls, witness for
     * failures), the witnesses are the flagged side; the old "not baseline" bucketing called ordinary traffic the
     * failure.
     */
    @Test
    void witnessesAreTheFlaggedSideWhenAClassifierWroteBoth() {
        var fix = TenantFixture.bootstrap(tenants, "rca-witness-side");
        String pid = fix.project().id();

        String baselineTrace = seedTrace(pid, seedSession(pid), FROM.plus(Duration.ofHours(2)));

        // All three are member (the denominator); only the first is witness.
        String failingTrace = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(2)));
        String healthyA = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(3)));
        String healthyB = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(4)));

        String findingId = seedFinding(pid, List.of(baselineTrace), List.of(failingTrace));
        String now = Instant.now().toString();
        for (String traceId : List.of(failingTrace, healthyA, healthyB)) {
            evidence.record(
                    pid,
                    findingId,
                    FindingEvidenceRow.Role.MEMBER,
                    List.of(FindingEvidenceRepository.Ref.trace(traceId)),
                    now);
        }
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                List.of(FindingEvidenceRepository.Ref.trace(failingTrace)),
                now);

        stubEngine();
        RcaJobRow job = enqueue(pid, findingId);
        worker.run(job);

        AgenticRcaEngine.Evidence ev = capturedEvidence();
        assertEquals(1, ev.flaggedCount(), "the flagged side counted the whole population instead of the failures");
        assertEquals("traces", ev.grain());
        assertTrue(ev.baselinePresent());
        assertEquals(Set.of(baselineTrace, failingTrace, healthyA, healthyB), ev.citableTraceIds());
    }

    @Test
    void theAgentsVerdictCausesAndRuledOutSentencesPersist() {
        var fix = TenantFixture.bootstrap(tenants, "rca-behavior");
        String pid = fix.project().id();
        String sessionId = seedSession(pid);
        // The evidence role decides the side, not the timestamp.
        String failingTrace = seedTrace(pid, sessionId, SPLIT.plus(Duration.ofHours(2)));
        String passingTrace = seedTrace(pid, sessionId, FROM.plus(Duration.ofHours(2)));

        RcaDtos.Cause cause = new RcaDtos.Cause(
                "The prompt was made stricter",
                "high",
                "change",
                "prompt",
                "The prompt now refuses partial answers.",
                "Every flagged trace is a refusal.",
                "Restore the earlier wording.",
                new RcaDtos.Attribution(null, "agent/system.md", "abc123", "Refuse when unsure."),
                List.of(failingTrace),
                List.of(),
                1);
        RuledOutCheck ruledOut = RuledOutCheck.ruledOut(1, "The serving model did not change.");
        when(engine.run(any(), any(), anyString(), anyMap(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "The prompt was rewritten.",
                        List.of(cause),
                        List.of(ruledOut),
                        "## Investigation",
                        true));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(passingTrace), List.of(failingTrace)));
        worker.run(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("done", report.status());
        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, report.verdict());
        assertEquals("The prompt was rewritten.", report.summary());
        assertEquals("## Investigation", report.detailedReport());
        assertNull(report.hypotheses(), "a metric run stores its causes where every other kind does");
        assertEquals(Map.of("ruled_out_1", ruledOut), storedChecks(report));

        RcaDtos.RcaReportView view = RcaDtos.RcaReportView.of(report, new ObjectMapper());
        assertEquals(List.of(cause), view.causes(), "change and type survive the round trip");

        // The dossier is the claim, the numbers, the method and the tools only: the agent pages evidence over MCP,
        // so nothing pre-chooses a sample.
        Map<String, String> dossier = capturedDossier();
        assertEquals(Set.of("finding.md", "evidence.md", "method.md", "tools.md"), dossier.keySet());
        assertEquals(AgenticRcaEngine.method(BuiltInDetector.Kind.SECRET_LEAK), dossier.get("method.md"));
        assertEquals(AgenticRcaEngine.TOOLS, dossier.get("tools.md"));
        String findingDoc = dossier.get("finding.md");
        assertTrue(findingDoc.contains("the id every `get_finding_evidence` call takes"), findingDoc);
        assertTrue(findingDoc.contains("`baseline`: 1 ref(s)"), findingDoc);
        assertTrue(findingDoc.contains("`exemplar`: 1 ref(s)"), findingDoc);
    }

    /**
     * The firewall: none of Layer 2's verdict, summary or citations may reach the dossier. Greps the whole dossier
     * because the likely leak is a future edit widening the projection; {@code FindingRepository.findClaim} is the
     * structural half.
     */
    @Test
    void theDossierCarriesNoTriageRuling() {
        var fix = TenantFixture.bootstrap(tenants, "rca-firewall");
        String pid = fix.project().id();
        String sessionId = seedSession(pid);
        String failingTrace = seedTrace(pid, sessionId, SPLIT.plus(Duration.ofHours(2)));
        String passingTrace = seedTrace(pid, sessionId, FROM.plus(Duration.ofHours(2)));
        String findingId = seedFinding(pid, List.of(passingTrace), List.of(failingTrace));

        // A full triage ruling as the lane writes it, citations with a check script and its stdout included.
        findings.recordTriage(
                pid,
                findingId,
                FindingRow.TriageVerdict.POSITIVE,
                "The omission is real: the deadline step is absent from every flagged trace.",
                "[{\"path\":\"checks/omission_rate.py\",\"reason\":\"recomputed the omission rate\","
                        + "\"stdout\":\"omission_rate=0.94\"}]",
                Instant.now().toString());

        stubEngine();
        worker.run(enqueue(pid, findingId));

        String dossier = String.join("\n", capturedDossier().values()).toLowerCase(Locale.ROOT);
        for (String word : List.of(
                "triage", "positive", "negative", "unclear", "opened_case", "omission is real", "omission_rate")) {
            assertFalse(
                    dossier.contains(word),
                    "the dossier leaks '" + word + "' — Layer 2's conclusions must never become Layer 3's"
                            + " premises, or RCA stops being a check on the gate: " + dossier);
        }
    }

    @Test
    void engineFailureFailsJobAndStampsReport() {
        var fix = TenantFixture.bootstrap(tenants, "rca-engine-down");
        String pid = fix.project().id();
        String sessionId = seedSession(pid);
        String failingTrace = seedTrace(pid, sessionId, SPLIT.plus(Duration.ofHours(2)));

        // No evidence door is a deployment fault: fail closed with {@code failed}, since the agent reads everything
        // through MCP.
        when(engine.run(any(), any(), anyString(), anyMap(), any()))
                .thenThrow(new TessaryException(RcaError.NO_EVIDENCE_DOOR, "mcp base url unset"));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of(failingTrace)));
        worker.run(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("failed", report.status());
        assertNotNull(report.summary());
    }

    /**
     * A frustration finding: the frustrated sessions are what was flagged, counted in sessions, and causes persist on a
     * frustration_causes report.
     */
    @Test
    void aFrustrationReportCountsSessionsAndPersistsItsCauses() {
        var fix = TenantFixture.bootstrap(tenants, "rca-frustration");
        String pid = fix.project().id();
        String sessionA = seedSession(pid);
        String sessionB = seedSession(pid);
        String turnA = seedTrace(pid, sessionA, SPLIT.plus(Duration.ofHours(2)));
        String turnB = seedTrace(pid, sessionB, SPLIT.plus(Duration.ofHours(3)));
        String calm = seedSession(pid);
        String findingId = seedFinding(pid, List.of(), List.of());
        String now = Instant.now().toString();
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(
                        FindingEvidenceRepository.Ref.session(sessionA),
                        FindingEvidenceRepository.Ref.session(calm),
                        FindingEvidenceRepository.Ref.session(sessionB)),
                now);
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                List.of(
                        FindingEvidenceRepository.Ref.session(sessionA),
                        FindingEvidenceRepository.Ref.session(sessionB)),
                now);
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                List.of(FindingEvidenceRepository.Ref.trace(turnA), FindingEvidenceRepository.Ref.trace(turnB)),
                now);

        RcaDtos.Cause cause = new RcaDtos.Cause(
                "Ignores the attached file",
                "medium",
                "standing",
                "prompt",
                "Answers from memory when the user attaches a file.",
                "The user has to paste the file's contents again.",
                "Tell the agent to read attachments first.",
                new RcaDtos.Attribution(null, "agent/system.md", "abc123", "Answer briefly."),
                List.of(turnA),
                List.of(sessionA, sessionB),
                2);
        when(engine.run(any(), any(), anyString(), anyMap(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "The agent ignores attachments.",
                        List.of(cause),
                        List.of(RuledOutCheck.ruledOut(1, "Frustration is not concentrated on one model.")),
                        "## Investigation",
                        true));

        RcaJobRow job = enqueue(pid, findingId, RcaReportRow.ReportKind.FRUSTRATION_CAUSES);
        worker.run(job);

        AgenticRcaEngine.Evidence ev = capturedEvidence();
        assertEquals(2, ev.flaggedCount(), "the frustrated sessions, not the calm one");
        assertEquals("sessions", ev.grain());
        assertFalse(ev.baselinePresent());
        assertTrue(ev.citableSessionIds().containsAll(Set.of(sessionA, sessionB, calm)), ev.toString());
        assertTrue(ev.citableTraceIds().containsAll(Set.of(turnA, turnB)), ev.toString());

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("done", report.status());
        assertEquals(RcaReportRow.ReportKind.FRUSTRATION_CAUSES, report.reportKind());
        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, report.verdict());
        assertEquals(Set.of("ruled_out_1"), storedChecks(report).keySet());

        RcaDtos.RcaReportView view = RcaDtos.RcaReportView.of(report, new ObjectMapper());
        assertEquals(List.of(cause), view.causes());
    }

    /**
     * A groundedness finding: the traces with a flagged answer are what was flagged, the flagged sentences stay behind
     * MCP rather than in the dossier, and causes persist on a groundedness_causes report.
     */
    @Test
    void aGroundednessReportCountsFlaggedTracesAndPersistsItsCauses() {
        var fix = TenantFixture.bootstrap(tenants, "rca-groundedness");
        String pid = fix.project().id();
        String flaggedA = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(2)));
        String flaggedB = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(3)));
        String clean = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(4)));
        String findingId = seedFinding(pid, List.of(), List.of());
        String now = Instant.now().toString();
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(
                        FindingEvidenceRepository.Ref.trace(flaggedA),
                        FindingEvidenceRepository.Ref.trace(clean),
                        FindingEvidenceRepository.Ref.trace(flaggedB)),
                now);
        evidence.record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                List.of(
                        FindingEvidenceRepository.Ref.trace(flaggedA),
                        FindingEvidenceRepository.Ref.trace(flaggedB),
                        FindingEvidenceRepository.Ref.span(flaggedA, "answer-a"),
                        FindingEvidenceRepository.Ref.span(flaggedB, "answer-b")),
                now);

        RcaDtos.Cause cause = new RcaDtos.Cause(
                "Retrieval returns one document",
                "medium",
                "change",
                "code",
                "Answers past what the single retrieved document says.",
                null,
                "Retrieve more documents.",
                new RcaDtos.Attribution(null, "rag/retrieve.py", "abc123", "top_k=1"),
                List.of(flaggedA, flaggedB),
                List.of(),
                2);
        when(engine.run(any(), any(), anyString(), anyMap(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "Retrieval returns one document.",
                        List.of(cause),
                        List.of(RuledOutCheck.ruledOut(1, "Flagged answers are not concentrated on one model.")),
                        "## Investigation",
                        true));

        RcaJobRow job = enqueue(pid, findingId, RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES);
        worker.run(job);

        AgenticRcaEngine.Evidence ev = capturedEvidence();
        assertEquals(2, ev.flaggedCount(), "the flagged traces, not the clean member");
        assertEquals("traces", ev.grain());
        assertFalse(ev.baselinePresent());
        assertEquals(Set.of(flaggedA, clean, flaggedB), ev.citableTraceIds());
        assertFalse(capturedDossier().containsKey("detections.md"), "flagged sentences are read over MCP");

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("done", report.status());
        assertEquals(RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES, report.reportKind());
        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, report.verdict());
        assertEquals(Set.of("ruled_out_1"), storedChecks(report).keySet());

        RcaDtos.RcaReportView view = RcaDtos.RcaReportView.of(report, new ObjectMapper());
        assertEquals(List.of(cause), view.causes());
    }

    /** Catches a finding with no evidence reaching the agent, which would still stamp a verdict. */
    @Test
    void aFindingWithNoEvidenceFailsWithoutReachingTheAgent() {
        String pid =
                TenantFixture.bootstrap(tenants, "rca-no-evidence").project().id();

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of()));
        worker.run(job);

        assertEquals("failed", reports.findByJobId(pid, job.id()).orElseThrow().status());
        verify(engine, never()).run(any(), any(), anyString(), anyMap(), any());
    }

    /** Catches the finding's title and basis missing from the dossier. */
    @Test
    void theFindingDocCarriesItsTitleAndBasis() {
        String pid =
                TenantFixture.bootstrap(tenants, "rca-title-basis").project().id();
        String failingTrace = seedTrace(pid, seedSession(pid), SPLIT.plus(Duration.ofHours(2)));
        String findingId = seedFinding(pid, List.of(), List.of(failingTrace));
        jdbc.sql("UPDATE finding SET title = 'Extraction skips the deadline', basis = 'seen on 9 of 10 traces'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", findingId)
                .update();
        stubEngine();

        worker.run(enqueue(pid, findingId));

        String findingDoc = capturedDossier().get("finding.md");
        assertTrue(findingDoc.contains("- title: Extraction skips the deadline\n"), findingDoc);
        assertTrue(findingDoc.contains("- basis: seen on 9 of 10 traces\n"), findingDoc);
    }

    /**
     * Full-column round trip of a claimed job: a misread column would analyse the wrong finding or key the wrong
     * principal.
     */
    @Test
    void aClaimedJobReadsBackEveryColumn() {
        String pid = TenantFixture.bootstrap(tenants, "rca-claim").project().id();
        String findingId = seedFinding(pid, List.of(), List.of());
        RcaJobRow enqueued = enqueue(pid, findingId);

        // The drain is parked, so a wide batch reaches this job.
        List<RcaJobRow> claimed = jobs.claimBatch("rca-claim-test", 10_000, 60, 5);

        assertTrue(claimed.contains(enqueued), claimed.toString());
    }

    private void stubEngine() {
        when(engine.run(any(), any(), anyString(), anyMap(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.NO_CAUSE_FOUND, "summary", List.of(), List.of(), "## report", true));
    }

    private static Map<String, RuledOutCheck> storedChecks(RcaReportRow report) {
        try {
            List<RuledOutCheck> parsed =
                    new ObjectMapper().readValue(report.ruledOut(), new TypeReference<List<RuledOutCheck>>() {});
            Map<String, RuledOutCheck> byCheck = new LinkedHashMap<>();
            for (RuledOutCheck c : parsed) byCheck.put(c.check(), c);
            return byCheck;
        } catch (Exception e) {
            throw new AssertionError("ruled_out was not an array of entries: " + report.ruledOut(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedDossier() {
        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        verify(engine).run(any(), any(), anyString(), files.capture(), any());
        return files.getValue();
    }

    private AgenticRcaEngine.Evidence capturedEvidence() {
        ArgumentCaptor<AgenticRcaEngine.Evidence> ev = ArgumentCaptor.forClass(AgenticRcaEngine.Evidence.class);
        verify(engine).run(any(), any(), anyString(), anyMap(), ev.capture());
        return ev.getValue();
    }

    /** An armed-window finding, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** One open finding citing {@code baseline} and {@code flagged} traces as its two sides. */
    private String seedFinding(String pid, List<String> baseline, List<String> flagged) {
        String now = Instant.now().toString();
        String cause = "cause-" + Ids.ulid();
        String findingId = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        pid,
                        BuiltInDetector.Kind.SECRET_LEAK,
                        "clf-" + cause,
                        cause,
                        1,
                        "cs_extract",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
        for (String traceId : baseline) {
            evidence.record(
                    pid,
                    findingId,
                    FindingEvidenceRow.Role.BASELINE,
                    List.of(FindingEvidenceRepository.Ref.trace(traceId)),
                    now);
        }
        for (String traceId : flagged) {
            evidence.record(
                    pid,
                    findingId,
                    FindingEvidenceRow.Role.EXEMPLAR,
                    List.of(FindingEvidenceRepository.Ref.trace(traceId)),
                    now);
        }
        return findingId;
    }

    private RcaJobRow enqueue(String pid, String findingId) {
        return enqueue(pid, findingId, RcaReportRow.ReportKind.METRIC_MOVEMENT);
    }

    private RcaJobRow enqueue(String pid, String findingId, String reportKind) {
        String jobId = jobs.createOrGet(
                pid, findingId, "behavior_profile", "profile-1", "behavior_drift", FROM, SPLIT, TO, "user-1", null);
        reports.insertPendingIfAbsent(
                pid,
                jobId,
                findingId,
                "behavior_profile",
                "profile-1",
                "deadline grader",
                null,
                "behavior_drift",
                reportKind,
                FROM,
                SPLIT,
                TO,
                0.62,
                0.0,
                0.62,
                RcaReportRow.Engine.AGENTIC);
        return new RcaJobRow(jobId, pid, findingId, "behavior_profile", "profile-1", "behavior_drift", "user-1");
    }

    private String seedSession(String pid) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx().session(pid, sessionId, FROM);
        return sessionId;
    }

    /** {@code at} is the trace's started_at, the basis the RCA window buckets by. */
    private String seedTrace(String pid, String sessionId, Instant at) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx().trace(pid, traceId, sessionId, at);
        return traceId;
    }

    private SubstrateV2Fixtures fx() {
        return new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }
}
