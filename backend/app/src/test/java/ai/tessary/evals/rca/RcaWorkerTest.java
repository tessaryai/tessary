// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

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

import ai.tessary.evals.classifier.finding.FindingEvidenceRepository;
import ai.tessary.evals.classifier.finding.FindingEvidenceRow;
import ai.tessary.evals.classifier.finding.FindingRepository;
import ai.tessary.evals.classifier.finding.FindingRow;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.RcaError;
import ai.tessary.evals.pipeline.PipelineService;
import ai.tessary.evals.rca.RcaDtos.Hypothesis;
import ai.tessary.evals.rca.RcaDtos.RuledOutCheck;
import ai.tessary.evals.rca.RcaDtos.RuledOutCheck.Assessment;
import ai.tessary.evals.rca.RcaSynthesisOutput.ChecklistAssessment;
import ai.tessary.evals.storage.SessionRepository;
import ai.tessary.evals.storage.SpanPayloadRepository;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.TenantFixture;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
// Parks the scheduled drain so `runForTest` is the ONLY thing that executes a job. Scheduling is
// live in @SpringBootTest, and these tests enqueue a job and then run it by hand — if a tick lands
// in that window the worker claims and runs it a second time, and the `verify(engine)` in
// capturedDossier() fails with two invocations.
//
// batch-size=0 is what actually parks it: claimBatch's LIMIT 0 returns nothing, tickInner breaks on
// the empty batch, and no job is ever dispatched. runForTest bypasses claiming, so the tests are
// unaffected. Lengthening the heartbeat CANNOT do this on its own — @Scheduled(fixedDelay) has no
// initial delay, so the first tick always fires at context startup, inside the test window.
//
// Both properties are static @TestPropertySource, not @DynamicPropertySource, because dynamic
// properties are NOT part of the context cache key: ~90 other @SpringBootTest classes register only
// `evals.secret-key` and share this exact MergedContextConfiguration, so whichever builds the
// context first wins and a dynamic parking value silently never applies.
@SpringBootTest
@TestPropertySource(properties = {"evals.rca.batch-size=0", "evals.rca.heartbeat-ms=3600000"})
class RcaWorkerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
        worker.runForTest(job);

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
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.BEHAVIOR_CHANGE,
                        "The prompt was rewritten.",
                        List.of(new Hypothesis("stricter prompt", "high", "because", List.of(failingTrace))),
                        List.of(new ChecklistAssessment(
                                "serving_model", "explains", "commit abc123 moved the call site to a new model")),
                        "## Investigation"));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(passingTrace), List.of(failingTrace)));
        worker.runForTest(job);

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
        worker.runForTest(enqueue(pid, findingId));

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
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet()))
                .thenThrow(new EvalsException(RcaError.NO_EVIDENCE_DOOR, "mcp base url unset"));

        RcaJobRow job = enqueue(pid, seedFinding(pid, List.of(), List.of(failingTrace)));
        worker.runForTest(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("failed", report.status());
        assertNotNull(report.summary());
    }

    @Test
    void aFindingThatNoLongerExistsFailsTheJobAndStampsTheReport() {
        var fix = TenantFixture.bootstrap(tenants, "rca-missing-subject");
        String pid = fix.project().id();

        RcaJobRow job = enqueue(pid, "fnd_does_not_exist");
        worker.runForTest(job);

        RcaReportRow report = reports.findByJobId(pid, job.id()).orElseThrow();
        assertEquals("failed", report.status());
        assertNotNull(report.summary());
        verify(engine, never()).run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void stubEngine(String verdict, List<ChecklistAssessment> checklist) {
        when(engine.run(any(), any(), anyString(), anyMap(), anySet(), anySet(), anySet()))
                .thenReturn(new AgenticRcaEngine.Result(verdict, "summary", List.of(), checklist, "## report"));
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
        verify(engine).run(any(), any(), anyString(), files.capture(), anySet(), anySet(), anySet());
        return files.getValue();
    }

    // ---- seeding -----------------------------------------------------------------------------

    /** One open behaviour-drift finding, citing {@code baseline} and {@code flagged} traces as its
     *  two evidence sides — the input the analysis dereferences. */
    private String seedFinding(String pid, List<String> baseline, List<String> flagged) {
        String now = Instant.now().toString();
        String findingId = findings.recordFiring(
                        Ids.ulid(),
                        pid,
                        "profile-1",
                        FindingRow.Cause.NOVELTY,
                        "cause-" + Ids.ulid(),
                        FindingRow.GLOBAL_WORKFLOW,
                        1,
                        null,
                        null,
                        "cs_extract",
                        now)
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
        String jobId = jobs.createOrGet(
                pid, findingId, "behavior_profile", "profile-1", "behavior_drift", FROM, SPLIT, TO, "user-1");
        reports.insertPendingIfAbsent(
                pid,
                jobId,
                findingId,
                "behavior_profile",
                "profile-1",
                "deadline grader",
                null,
                "behavior_drift",
                FROM,
                SPLIT,
                TO,
                0.62,
                0.0,
                0.62,
                RcaReportRow.Engine.AGENTIC);
        return jobs.findById(pid, jobId).orElseThrow();
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
