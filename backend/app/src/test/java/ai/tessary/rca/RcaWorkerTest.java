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
 * Acceptance for the RCA worker + analysis pipeline against the real Testcontainers Postgres, with
 * only the sandboxed-agent seam ({@link AgenticRcaEngine}) mocked. Pins the properties that make
 * the pipeline gate-free: a grader-version flip still reaches the agent instead of short-circuiting
 * to {@code definition_change}; an empty failing cohort still reaches it instead of auto-stamping
 * {@code inconclusive}; the agent's verdict and checklist assessments persist merged with the
 * measurements they judged; and an engine failure stamps the report {@code failed} so polling
 * converges.
 *
 * <p>The subject is a FINDING, and its two sides are its {@code finding_evidence} rows split by role:
 * {@code baseline} is the "before", everything else is what the classifier flagged. Nothing here is
 * time-sliced any more — a trace is on the side the classifier filed it under.
 */
// Parks the scheduled drain so a direct `run` is the ONLY thing that executes a job. Scheduling is
// live in @SpringBootTest, and these tests enqueue a job and then run it by hand — if a tick lands
// in that window the worker claims and runs it a second time, and the `verify(engine)` in
// capturedDossier() fails with two invocations.
//
// batch-size=0 is what actually parks it: claimBatch's LIMIT 0 returns nothing, tickInner breaks on
// the empty batch, and no job is ever dispatched. A direct `run` bypasses claiming, so the tests are
// unaffected. Lengthening the heartbeat CANNOT do this on its own — @Scheduled(fixedDelay) has no
// initial delay, so the first tick always fires at context startup, inside the test window.
//
// Both properties are static @TestPropertySource, not @DynamicPropertySource. Dynamic properties DO
// reach the context cache key -- DynamicPropertiesContextCustomizer.equals compares the Set<Method>
// it was built from -- but that is exactly the problem: the key would turn on which class declared
// the method rather than on what it registered, so this class would fork a context of its own for a
// value @TestPropertySource states in the cache key directly, where two classes wanting the same
// parking share one context.
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
     * A classifier that writes BOTH halves of a fraction must not have its denominator measured as the
     * problem. {@code tool_error} files every call in the spell as {@code member} and the failing subset
     * as {@code witness}; the checklist used to bucket "everything that is not baseline" as flagged, so
     * the failing-cohort shape described ordinary traffic and called it the failure. Findings with no
     * witnesses are unaffected — for those, member IS the flagged population.
     */
    @Test
    void witnessesAreTheFlaggedSideWhenAClassifierWroteBoth() {
        var fix = TenantFixture.bootstrap(tenants, "rca-witness-side");
        String pid = fix.project().id();

        String baselineTrace = seedTrace(pid, seedSession(pid), FROM.plus(Duration.ofHours(2)));

        // One failing call, and two healthy ones from the same window. All three are `member` — that is
        // the denominator the rate was computed over — and only the first is `witness`.
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
        // ONE failing trace, not three: the flagged side is the witness, not the population it was drawn
        // from. The observable used to be the grading-health line; grading left the platform, so the
        // cohort-shape check carries the same count. Asserted on the bare "N failing trace(s)" phrase
        // rather than the "across the N" header because this fixture seeds traces with no spans, so the
        // check lands on its no-readable-observations branch — which states the same count, and it is
        // the count, not the sentence around it, that distinguishes witness from member.
        assertTrue(
                checklist.contains("1 failing trace(s)") && !checklist.contains("3 failing trace(s)"),
                "the flagged side measured the whole population instead of the failures:\n" + checklist);
    }

    @Test
    void agentVerdictAndChecklistPersistMergedWithTheMeasurements() {
        var fix = TenantFixture.bootstrap(tenants, "rca-behavior");
        String pid = fix.project().id();
        String sessionId = seedSession(pid);
        // Which SIDE a trace is on is decided by the evidence role the classifier filed it under, not
        // by its timestamp — the times here only keep the ledger ordering readable.
        String failingTrace = seedTrace(pid, sessionId, SPLIT.plus(Duration.ofHours(2)));
        String passingTrace = seedTrace(pid, sessionId, FROM.plus(Duration.ofHours(2)));

        // behaviour_change is the agent's to assign — it read the call site's prompt in the repo.
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

        // Each stored item carries BOTH the agent's call and the numbers it judged, and a check the
        // agent skipped survives as unknown rather than vanishing.
        Map<String, RuledOutCheck> checks = storedChecks(report);
        RuledOutCheck model = checks.get("serving_model");
        assertEquals(Assessment.EXPLAINS, model.assessment());
        assertEquals("commit abc123 moved the call site to a new model", model.detail());
        assertTrue(model.measurement().contains("serving model"), model.measurement());
        assertFalse(model.passed());

        // Unassessed checks still reach the report — with their numbers and no verdict.
        RuledOutCheck skipped = checks.get("failing_cohort_shape");
        assertEquals(Assessment.UNKNOWN, skipped.assessment());
        assertFalse(skipped.passed());
        assertNotNull(skipped.measurement());

        // The dossier is finding-specific and nothing else: the claim, the detector's numbers, the
        // checklist. No hydrated trace bodies and no per-side ledger — the agent pages the evidence
        // refs over MCP and states the sample it took, so nothing here pre-chooses one for it.
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
     * THE FIREWALL. Layer 2 has already ruled on this finding and written its verdict, summary and
     * citations onto the row; not one word of that may reach the materialized dossier.
     *
     * <p>Greps the whole dossier for Layer 2's vocabulary rather than asserting on one field, because
     * the leak this guards against is not a deliberate read — it is a future edit that widens the
     * finding projection or drops the detector's own prose in verbatim. {@code FindingRepository
     * .findClaim} is the structural half of the enforcement; this is the half that fails loudly.
     */
    @Test
    void theDossierCarriesNoTriageRuling() {
        var fix = TenantFixture.bootstrap(tenants, "rca-firewall");
        String pid = fix.project().id();
        String sessionId = seedSession(pid);
        String failingTrace = seedTrace(pid, sessionId, SPLIT.plus(Duration.ofHours(2)));
        String passingTrace = seedTrace(pid, sessionId, FROM.plus(Duration.ofHours(2)));
        String findingId = seedFinding(pid, List.of(passingTrace), List.of(failingTrace));

        // A full triage ruling, in the shape the lane writes it — verdict, action, prose summary and
        // citations carrying a check script and its stdout.
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

        // A run that cannot reach the evidence door is a deployment fault, and the worker must stamp
        // `failed` (fail closed) rather than a bogus inconclusive: the agent reads every row it cites
        // through MCP, so there is no degraded mode to fall back to.
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenThrow(new TessaryException(RcaError.NO_EVIDENCE_DOOR, "mcp base url unset"));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of(failingTrace)));
        worker.run(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("failed", report.status());
        assertNotNull(report.summary());
    }

    @Test
    void aFindingThatNoLongerExistsFailsTheJobAndStampsTheReport() {
        var fix = TenantFixture.bootstrap(tenants, "rca-missing-subject");
        String pid = fix.project().id();

        RcaJobRow job = enqueue(pid, "fnd_does_not_exist");
        worker.run(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("failed", report.status());
        assertNotNull(report.summary());
        verify(engine, never()).run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet());
    }

    /**
     * A frustration finding cites every scored session as a member and the frustrated ones as witness session
     * refs beside the turns that fired. The run gets only the frustrated sessions as citable receipts, never the
     * calm members, measures only the cohort shape (there is no baseline side for serving_model to compare), and
     * its ranked causes persist on a frustration_causes report.
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
     * A groundedness finding cites every scored trace as a member, each trace with a flagged answer as a witness,
     * and each flagged answer as a witness span beside it. The run gets the traces with a flagged answer as its
     * only receipts, measures only the cohort shape, is handed the flagged answers as {@code detections.md}, and
     * its ranked causes persist on a groundedness_causes report.
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
     * The checklist measured over real spans. Catches a share or cohort count taken per SPAN instead of per
     * trace (two gpt-4o spans on one trace are one trace), a failing tool counted when it succeeded, a
     * dimension with no values rendered as an empty line, and the no-data sides reading as a zero share
     * rather than saying there is nothing to compare.
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

    /**
     * Catches a finding with no evidence at all (no baseline, no flagged trace, no session) being handed to the
     * agent, which would investigate nothing and still stamp a verdict. It fails the job instead.
     */
    @Test
    void aFindingWithNoEvidenceFailsWithoutReachingTheAgent() {
        String pid =
                TenantFixture.bootstrap(tenants, "rca-no-evidence").project().id();

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of()));
        worker.run(job);

        assertEquals("failed", reports.findByJobId(pid, job.id()).orElseThrow().status());
        verify(engine, never()).run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet());
    }

    /** Catches the finding's own title and basis being left out of the dossier the agent starts from. */
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
     * Full-column round trip of a claimed job. Catches a column read into the wrong field of the row the worker
     * runs, which would analyse the wrong finding or issue the MCP key to the wrong principal.
     */
    @Test
    void aClaimedJobReadsBackEveryColumn() {
        String pid = TenantFixture.bootstrap(tenants, "rca-claim").project().id();
        String findingId = seedFinding(pid, List.of(), List.of());
        RcaJobRow enqueued = enqueue(pid, findingId);

        // The drain is parked (batch-size=0), so nothing else claims; a wide batch reaches this job.
        List<RcaJobRow> claimed = jobs.claimBatch("rca-claim-test", 10_000, 60, 5);

        assertTrue(claimed.contains(enqueued), claimed.toString());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void stubEngine(String verdict, List<ChecklistAssessment> checklist) {
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(
                        verdict, "summary", List.of(), List.of(), checklist, "## report", true));
    }

    /** The persisted checklist, by check id. */
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

    /** The dossier file map the pipeline handed the agent on the most recent run. */
    @SuppressWarnings("unchecked")
    private Map<String, String> capturedDossier() {
        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        verify(engine).run(any(), any(), anyString(), files.capture(), anySet(), anySet(), anySet(), anySet());
        return files.getValue();
    }

    // ---- seeding -----------------------------------------------------------------------------

    /** The finding shape these fixtures file: a classifier's armed window, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** One open classifier finding, citing {@code baseline} and {@code flagged} traces as its
     *  two evidence sides — the input the analysis dereferences. */
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

    /** {@code at} is the trace's started_at — the trace-time basis the RCA window reads bucket by. */
    private String seedTrace(String pid, String sessionId, Instant at) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx().trace(pid, traceId, sessionId, at);
        return traceId;
    }

    private SubstrateV2Fixtures fx() {
        return new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }
}
