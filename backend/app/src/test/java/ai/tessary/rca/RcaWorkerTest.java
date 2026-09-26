// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
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
import ai.tessary.rca.RcaDtos.Hypothesis;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.rca.RcaDtos.RuledOutCheck.Assessment;
import ai.tessary.rca.RcaSynthesisOutput.ChecklistAssessment;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The RCA worker and pipeline against real Postgres with only {@link AgenticRcaEngine} mocked. Gate-free: a grader-
 * version flip and an empty failing cohort both still reach the agent; the verdict and checklist persist merged with
 * their measurements; an engine failure stamps {@code failed}. A finding's two sides are its evidence rows by role,
 * never by time.
 */
// batch-size=0 parks the scheduled drain (claimBatch's LIMIT 0), so a direct `run` is the only execution; a tick
// would run the job twice. A long heartbeat cannot do this, since fixedDelay fires at startup. Static
// @TestPropertySource keeps the context cache key shared, where @DynamicPropertySource would fork one per declaring
// class.
@SpringBootTest
@TestPropertySource(properties = {"tessary.rca.batch-size=0", "tessary.rca.heartbeat-ms=3600000"})
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
    RcaChecklist checklist;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
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

        stubEngine(RcaReportRow.Verdict.BEHAVIOR_CHANGE, List.of());
        RcaJobRow job = enqueue(pid, findingId);
        worker.run(job);

        String checklist = capturedDossier().get("checklist.md");
        assertNotNull(checklist);
        // One failing trace, not three. Asserted on the "N failing trace(s)" count, which the no-readable-
        // observations branch these span-less traces hit also states.
        assertTrue(
                checklist.contains("1 failing trace(s)") && !checklist.contains("3 failing trace(s)"),
                "the flagged side measured the whole population instead of the failures:\n" + checklist);
    }

    @Test
    void agentVerdictAndChecklistPersistMergedWithTheMeasurements() {
        var fix = TenantFixture.bootstrap(tenants, "rca-behavior");
        String pid = fix.project().id();
        String sessionId = seedSession(pid);
        // The evidence role decides the side, not the timestamp.
        String failingTrace = seedTrace(pid, sessionId, SPLIT.plus(Duration.ofHours(2)));
        String passingTrace = seedTrace(pid, sessionId, FROM.plus(Duration.ofHours(2)));

        // behaviour_change is the agent's to assign.
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.BEHAVIOR_CHANGE,
                        "The prompt was rewritten.",
                        List.of(new Hypothesis("stricter prompt", "high", "because", List.of(failingTrace))),
                        List.of(),
                        List.of(new ChecklistAssessment(
                                "serving_model", "explains", "commit abc123 moved the call site to a new model")),
                        "## Investigation",
                        true));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(passingTrace), List.of(failingTrace)));
        worker.run(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("done", report.status());
        assertEquals(RcaReportRow.Verdict.BEHAVIOR_CHANGE, report.verdict());
        assertEquals("The prompt was rewritten.", report.summary());
        assertEquals("## Investigation", report.detailedReport());
        assertTrue(report.hypotheses().contains("stricter prompt"));

        // Each stored item carries the agent's call and the numbers it judged; a skipped check survives as unknown.
        Map<String, RuledOutCheck> checks = storedChecks(report);
        RuledOutCheck model = checks.get("serving_model");
        assertEquals(Assessment.EXPLAINS, model.assessment());
        assertEquals("commit abc123 moved the call site to a new model", model.detail());
        assertTrue(model.measurement().contains("serving model"), model.measurement());
        assertFalse(model.passed());

        RuledOutCheck skipped = checks.get("failing_cohort_shape");
        assertEquals(Assessment.UNKNOWN, skipped.assessment());
        assertFalse(skipped.passed());
        assertNotNull(skipped.measurement());

        // The dossier is the claim, the numbers and the checklist only: the agent pages evidence over MCP, so nothing
        // pre-chooses a sample.
        Map<String, String> dossier = capturedDossier();
        assertEquals(Set.of("finding.md", "method.md", "evidence.json", "checklist.md"), dossier.keySet());
        assertTrue(
                dossier.get("method.md").contains("**Absent roles**"),
                "the method card has to say what a MISSING role means, or an absent baseline reads as a lost"
                        + " write on three of the five detectors");
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

        stubEngine(RcaReportRow.Verdict.BEHAVIOR_CHANGE, List.of());
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
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenThrow(new TessaryException(RcaError.NO_EVIDENCE_DOOR, "mcp base url unset"));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of(failingTrace)));
        worker.run(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("failed", report.status());
        assertNotNull(report.summary());
    }

    /**
     * A frustration finding: only the frustrated sessions become citable receipts, only the cohort shape is measured
     * (no baseline for serving_model), and causes persist on a frustration_causes report.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFrustrationReportPersistsItsCausesAndSkipsTheTwoSidedCheck() {
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
                "Answers from memory when the user attaches a file.",
                2,
                1,
                List.of(sessionA, sessionB),
                List.of(turnA),
                new RcaDtos.Attribution("prompt", "agent/system.md", "abc123", "Answer briefly."),
                "Tell the agent to read attachments first.",
                "medium");
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "The agent ignores attachments.",
                        List.of(),
                        List.of(cause),
                        List.of(new ChecklistAssessment("failing_cohort_shape", "ruled_out", "no concentration")),
                        "## Investigation",
                        true));

        RcaJobRow job = enqueue(pid, findingId, RcaReportRow.ReportKind.FRUSTRATION_CAUSES);
        worker.run(job);

        ArgumentCaptor<Set<String>> citableSessions = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Set<String>> flagged = ArgumentCaptor.forClass(Set.class);
        verify(engine)
                .run(
                        any(),
                        any(),
                        anyString(),
                        anyMap(),
                        anySet(),
                        flagged.capture(),
                        citableSessions.capture(),
                        anySet());
        assertEquals(
                Set.of(sessionA, sessionB), citableSessions.getValue(), "the frustrated sessions, not the calm one");
        assertEquals(Set.of(turnA, turnB), flagged.getValue());

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("done", report.status());
        assertEquals(RcaReportRow.ReportKind.FRUSTRATION_CAUSES, report.reportKind());
        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, report.verdict());
        assertEquals(Set.of("failing_cohort_shape"), storedChecks(report).keySet());

        RcaDtos.RcaReportView view = RcaDtos.RcaReportView.of(report, new ObjectMapper());
        assertEquals(List.of(cause), view.causes());
        assertTrue(view.hypotheses().isEmpty());
    }

    /**
     * A groundedness finding: traces with a flagged answer are the only receipts, only the cohort shape is measured,
     * flagged answers ship as {@code detections.md}, and causes persist on a groundedness_causes report.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aGroundednessReportPersistsItsCausesAndHandsOverTheFlaggedAnswers() {
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
                "Answers past what the single retrieved document says.",
                0,
                2,
                List.of(),
                List.of(flaggedA, flaggedB),
                new RcaDtos.Attribution("code", "rag/retrieve.py", "abc123", "top_k=1"),
                "Retrieve more documents.",
                "medium");
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "Retrieval returns one document.",
                        List.of(),
                        List.of(cause),
                        List.of(new ChecklistAssessment("failing_cohort_shape", "ruled_out", "no concentration")),
                        "## Investigation",
                        true));

        RcaJobRow job = enqueue(pid, findingId, RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES);
        worker.run(job);

        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Set<String>> flagged = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Set<String>> citableSessions = ArgumentCaptor.forClass(Set.class);
        verify(engine)
                .run(
                        any(),
                        any(),
                        anyString(),
                        files.capture(),
                        anySet(),
                        flagged.capture(),
                        citableSessions.capture(),
                        anySet());
        assertEquals(Set.of(flaggedA, flaggedB), flagged.getValue(), "the flagged traces, not the clean member");
        assertTrue(citableSessions.getValue().isEmpty(), "a groundedness finding cites no sessions");
        String answers = files.getValue().get("detections.md");
        assertNotNull(answers, "the flagged answers ride in the dossier");
        assertTrue(answers.contains("trace `" + flaggedA + "` span `answer-a`"), answers);
        assertTrue(answers.contains("All 2 flagged answer(s) are shown."), answers);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("done", report.status());
        assertEquals(RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES, report.reportKind());
        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, report.verdict());
        assertEquals(Set.of("failing_cohort_shape"), storedChecks(report).keySet());

        RcaDtos.RcaReportView view = RcaDtos.RcaReportView.of(report, new ObjectMapper());
        assertEquals(List.of(cause), view.causes());
        assertTrue(view.hypotheses().isEmpty());
    }

    /**
     * Catches shares and cohorts counted per span instead of per trace, a succeeding tool counted as failing, an
     * empty dimension rendered as a blank line, and no-data sides read as a zero share.
     */
    @Test
    void theChecklistMeasuresEachSideFromItsOwnSpans() {
        String pid = TenantFixture.bootstrap(tenants, "rca-checklist").project().id();
        String baseline = SubstrateV2Fixtures.traceId();
        fx().spanSeed(pid).traceId(baseline).at(FROM).model("gpt-4o").write();
        fx().spanSeed(pid).traceId(baseline).at(FROM).model("gpt-4o").write();
        String tf1 = SubstrateV2Fixtures.traceId();
        fx().spanSeed(pid).traceId(tf1).at(SPLIT).model("gpt-4o").write();
        fx().spanSeed(pid)
                .traceId(tf1)
                .at(SPLIT)
                .kind("tool")
                .name("search")
                .status("error")
                .write();
        String tf2 = SubstrateV2Fixtures.traceId();
        fx().spanSeed(pid)
                .traceId(tf2)
                .at(SPLIT)
                .model("gpt-5-canary")
                .errorType("Timeout")
                .write();
        fx().spanSeed(pid)
                .traceId(tf2)
                .at(SPLIT)
                .kind("tool")
                .name("search")
                .errorType("Timeout")
                .write();
        String tf3 = SubstrateV2Fixtures.traceId();
        fx().spanSeed(pid).traceId(tf3).at(SPLIT).model("gpt-4o").write();
        fx().spanSeed(pid)
                .traceId(tf3)
                .at(SPLIT)
                .kind("tool")
                .name("lookup")
                .status("ok")
                .write();
        List<String> flagged = List.of(tf1, tf2, tf3);

        assertEquals(
                List.of(new RcaChecklist.Measurement(
                        "serving_model",
                        "Share of LLM spans by serving model.\n"
                                + "Baseline side: gpt-4o 100% (2)\n"
                                + "Flagged side: gpt-4o 67% (2), gpt-5-canary 33% (1)\n"
                                + "A model appearing only on the flagged side may be the cause, or a canary too"
                                + " small to move the score — check whether it actually serves the failing traces.")),
                checklist.measure(pid, List.of(baseline), flagged));
        assertEquals(
                new RcaChecklist.Measurement(
                        "failing_cohort_shape",
                        "Top facet values across the 3 failing trace(s):\n"
                                + "- model: 'gpt-4o' on 2 (67%), 'gpt-5-canary' on 1 (33%)\n"
                                + "- span error: 'Timeout' on 1 (33%)\n"
                                + "- failing tool: 'search' on 2 (67%)"),
                checklist.failingCohortShape(pid, new LinkedHashSet<>(flagged)));
        assertEquals(
                "Top facet values across the 1 failing trace(s):\n- model: 'gpt-4o' on 1 (100%)",
                checklist.failingCohortShape(pid, Set.of(baseline)).finding(),
                "a dimension with no values is left out, not printed empty");

        assertTrue(
                checklist.measure(pid, List.of(), flagged).get(0).finding().contains("Baseline side: (no traffic)\n"));
        assertTrue(checklist
                .measure(pid, List.of("tr_none_a"), List.of("tr_none_b"))
                .get(0)
                .finding()
                .startsWith("No model-tagged traffic on either side"));
        assertEquals(
                "No flagged traces on this finding — nothing to group.",
                checklist.failingCohortShape(pid, Set.of()).finding());
        assertTrue(checklist
                .failingCohortShape(pid, Set.of("tr_none"))
                .finding()
                .startsWith("1 failing trace(s), but none has readable observations"));
    }

    /** Catches a finding with no evidence reaching the agent, which would still stamp a verdict. */
    @Test
    void aFindingWithNoEvidenceFailsWithoutReachingTheAgent() {
        String pid =
                TenantFixture.bootstrap(tenants, "rca-no-evidence").project().id();

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of()));
        worker.run(job);

        assertEquals("failed", reports.findByJobId(pid, job.id()).orElseThrow().status());
        verify(engine, never()).run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet());
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
        stubEngine(RcaReportRow.Verdict.INCONCLUSIVE, List.of());

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

    private void stubEngine(String verdict, List<ChecklistAssessment> checklist) {
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(
                        verdict, "summary", List.of(), List.of(), checklist, "## report", true));
    }

    private static Map<String, RuledOutCheck> storedChecks(RcaReportRow report) {
        try {
            List<RuledOutCheck> parsed =
                    new ObjectMapper().readValue(report.ruledOut(), new TypeReference<List<RuledOutCheck>>() {});
            Map<String, RuledOutCheck> byCheck = new LinkedHashMap<>();
            for (RuledOutCheck c : parsed) byCheck.put(c.check(), c);
            return byCheck;
        } catch (Exception e) {
            throw new AssertionError("ruled_out was not a checklist array: " + report.ruledOut(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedDossier() {
        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        verify(engine).run(any(), any(), anyString(), files.capture(), anySet(), anySet(), anySet(), anySet());
        return files.getValue();
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
