// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.testsupport.RateClassifierFixture;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Resolving a frustration case against Postgres. {@code fixed} restarts the call site and re-learns its reference
 * from the traffic after the resolve, so the next catch-up files nothing off the hours it closed. {@code
 * false_alarm} does the same and clears the flag on every conversation the case cites, so a later turn of one of
 * them is scored again, while a flagged conversation the case does not cite stays flagged.
 */
@SpringBootTest
class FrustrationResolveIntegrationTest {

    @Autowired
    FrustrationRateService rates;

    @Autowired
    CaseService caseService;

    @Autowired
    CaseRepository cases;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RateClassifierFixture fixture;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Test
    void fixedRestartsTheCallSiteAndRelearnsWithoutClearingAnything() {
        String pid = fixture.project("fr-fixed", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        CaseRow opened = rise(pid, signal);
        assertNotNull(
                fixture.state("frustration", pid, "cs-chat").get("baseline_calls"),
                "the reference was learned before the resolve");

        caseService.resolve(pid, opened.id(), "rolled back the prompt", "priya@example.com", "fixed");

        CaseRow resolved = cases.findById(pid, opened.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, resolved.state());
        assertEquals("fixed", resolved.disposition());
        Map<String, Object> state = fixture.state("frustration", pid, "cs-chat");
        assertNull(state.get("baseline_calls"), "the reference is re-learned from here");
        assertNull(state.get("baseline_failures"));
        assertNotNull(state.get("reset_at"));
        assertEquals("rolled back the prompt", state.get("reset_note"));
        assertEquals(0, fixture.clearedDetections("frustration", pid), "fixed clears no session");
        assertEquals(
                "{\"disposition\": \"fixed\", \"sessions_cleared\": 0}", fixture.resolvedEventDetail(pid, opened.id()));

        rates.refresh(pid, signal, Instant.now());

        List<FindingRow> filed = findings.listByProject(pid, null, null, "frustration", false, 10);
        assertEquals(1, filed.size(), "the closed hours are fenced off, so nothing is re-filed");
        assertEquals(FindingRow.Status.CLOSED, filed.get(0).status());
        assertTrue(cases.listLive(pid).isEmpty());
    }

    @Test
    void falseAlarmClearsEveryFrustratedSessionOfTheSpellSoItIsScoredAgain() {
        String pid = fixture.project("fr-false-alarm", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        CaseRow opened = rise(pid, signal);
        FindingRow finding = findings.listByCase(pid, opened.id()).get(0);
        List<String> cited = evidence.listByFinding(pid, finding.id()).stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()))
                .map(FindingEvidenceRow::sessionId)
                .filter(s -> s != null)
                .toList();
        assertEquals(72, cited.size(), "every frustrated session since onset, past the fifty a page shows");
        String conversation = cited.get(0);
        new SubstrateV2Fixtures(sessions, traces, spans, payloads)
                .trace(pid, "tr-later", "sess-later", conversation, null, Instant.now());
        assertEquals(
                Set.of("tr-later"),
                detections.tracesInUnclearedFlaggedConversations(
                        BuiltInDetector.Kind.FRUSTRATION, pid, signal.id(), List.of("tr-later")),
                "a flagged conversation is not sent again");

        caseService.resolve(pid, opened.id(), "sarcasm, not frustration", "priya@example.com", "false_alarm");

        assertEquals(
                CaseRow.Disposition.FALSE_ALARM,
                cases.findById(pid, opened.id()).orElseThrow().disposition());
        assertEquals(
                cited.size(),
                fixture.clearedDetections("frustration", pid),
                "every frustrated session of the spell, and only those");
        assertTrue(
                fixture.detections("frustration", pid) > cited.size(),
                "flags from before the spell are not this case's");
        assertEquals(
                Set.of(),
                detections.tracesInUnclearedFlaggedConversations(
                        BuiltInDetector.Kind.FRUSTRATION, pid, signal.id(), List.of("tr-later")),
                "its later turn is scorable again");
        assertNull(fixture.state("frustration", pid, "cs-chat").get("baseline_calls"), "a false alarm re-learns too");
        assertEquals(
                "{\"disposition\": \"false_alarm\", \"sessions_cleared\": 72}",
                fixture.resolvedEventDetail(pid, opened.id()));
        assertEquals(1, assessmentsFlagged(pid, conversation), "the assessment still says what the scorer said");
    }

    @Test
    void aDispositionIsRefusedOnAnyOtherCase() {
        String pid = fixture.project("fr-other", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        CaseRow opened = rise(pid, signal);
        jdbc.sql("UPDATE eval_case SET detector = 'tool_error' WHERE id = :id")
                .param("id", opened.id())
                .update();

        assertThrows(
                TessaryException.class,
                () -> caseService.resolve(pid, opened.id(), "done", "priya@example.com", "fixed"));
        assertEquals(
                CaseRow.State.OPEN,
                cases.findById(pid, opened.id()).orElseThrow().state());
    }

    // ---- fixtures

    /** A call site at 5% for 210 conversations, then 40% for 180: one spell, one finding, one case. */
    private CaseRow rise(String pid, ClassifierRow signal) {
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
        rates.refresh(pid, signal, Instant.now());
        List<CaseRow> live = cases.listLive(pid);
        assertEquals(1, live.size());
        assertEquals(CaseRow.Detector.FRUSTRATION, live.get(0).detector());
        return live.get(0);
    }

    private long assessmentsFlagged(String pid, String conversation) {
        return jdbc.sql("SELECT COUNT(*) FROM frustration_assessment"
                        + " WHERE project_id = :pid AND conversation_id = :conv AND frustrated")
                .param("pid", pid)
                .param("conv", conversation)
                .query(Long.class)
                .single();
    }
}
