// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.Assessment;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Resolving a frustration case against Postgres. {@code fixed} restarts the call site and re-learns its reference
 * from the traffic after the resolve, so the next catch-up files nothing off the hours it closed. {@code
 * false_alarm} does the same and clears the flag on every conversation the case cites, so a later turn of one of
 * them is scored again, while a flagged conversation the case does not cite stays flagged.
 *
 * <p>Shares the turn-grain fingerprint, whose test table stands in for {@code frustration_detection}.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class FrustrationResolveIntegrationTest {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

    @Autowired
    FrustrationAssessmentRepository assessments;

    @Autowired
    FrustrationRateService rates;

    @Autowired
    CaseService caseService;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseEventRepository events;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

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
        String pid = project("fr-fixed");
        ClassifierRow signal = frustration(pid);
        CaseRow opened = rise(pid, signal);
        assertNotNull(state(pid).get("baseline_calls"), "the reference was learned before the resolve");

        caseService.resolve(pid, opened.id(), "rolled back the prompt", "priya@example.com", "fixed");

        CaseRow resolved = cases.findById(pid, opened.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, resolved.state());
        assertEquals(CaseRow.Disposition.FIXED, resolved.disposition());
        Map<String, Object> state = state(pid);
        assertNull(state.get("baseline_calls"), "the reference is re-learned from here");
        assertNull(state.get("baseline_failures"));
        assertNotNull(state.get("reset_at"));
        assertEquals("rolled back the prompt", state.get("reset_note"));
        assertEquals(0, clearedRows(pid), "fixed clears no conversation");
        assertEquals(
                "{\"disposition\": \"fixed\", \"conversations_cleared\": 0}", resolvedEventDetail(pid, opened.id()));

        rates.refresh(pid, signal, Instant.now());

        List<FindingRow> filed = findings.listByProject(pid, null, null, "frustration", false, 10);
        assertEquals(1, filed.size(), "the closed hours are fenced off, so nothing is re-filed");
        assertEquals(FindingRow.Status.CLOSED, filed.get(0).status());
        assertTrue(cases.listLive(pid).isEmpty());
    }

    @Test
    void falseAlarmAlsoClearsEveryCitedConversationSoItIsScoredAgain() {
        String pid = project("fr-false-alarm");
        ClassifierRow signal = frustration(pid);
        CaseRow opened = rise(pid, signal);
        FindingRow finding = findings.listByCase(pid, opened.id()).get(0);
        List<String> cited = evidence.listByFinding(pid, finding.id()).stream()
                .map(FindingEvidenceRow::sessionId)
                .filter(s -> s != null)
                .toList();
        assertEquals(FrustrationRateService.MAX_WITNESSES, cited.size());
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
        assertEquals(cited.size(), clearedRows(pid), "every cited conversation, and only those");
        assertTrue(flaggedRows(pid) > cited.size(), "the uncited flagged conversations stay flagged");
        assertEquals(
                Set.of(),
                detections.tracesInUnclearedFlaggedConversations(
                        BuiltInDetector.Kind.FRUSTRATION, pid, signal.id(), List.of("tr-later")),
                "its later turn is scorable again");
        assertNull(state(pid).get("baseline_calls"), "a false alarm re-learns too");
        assertEquals(
                "{\"disposition\": \"false_alarm\", \"conversations_cleared\": 50}",
                resolvedEventDetail(pid, opened.id()));
        assertEquals(1, assessmentsFlagged(pid, conversation), "the assessment still says what the scorer said");
    }

    @Test
    void aDispositionIsRefusedOnAnyOtherCase() {
        String pid = project("fr-other");
        ClassifierRow signal = frustration(pid);
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
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        seedHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
        rates.refresh(pid, signal, Instant.now());
        List<CaseRow> live = cases.listLive(pid);
        assertEquals(1, live.size());
        assertEquals(CaseRow.Detector.FRUSTRATION, live.get(0).detector());
        return live.get(0);
    }

    private Map<String, Object> state(String pid) {
        return jdbc.sql("SELECT baseline_calls, baseline_failures, reset_at, reset_note FROM frustration_state"
                        + " WHERE project_id = :pid AND call_site_id = 'cs-chat'")
                .param("pid", pid)
                .query()
                .singleRow();
    }

    private long clearedRows(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM " + TurnGrainTestDetectionConfig.TABLE
                        + " WHERE project_id = :pid AND cleared_at IS NOT NULL")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    private long flaggedRows(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM " + TurnGrainTestDetectionConfig.TABLE + " WHERE project_id = :pid")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    private long assessmentsFlagged(String pid, String conversation) {
        return jdbc.sql("SELECT COUNT(*) FROM frustration_assessment"
                        + " WHERE project_id = :pid AND conversation_id = :conv AND frustrated")
                .param("pid", pid)
                .param("conv", conversation)
                .query(Long.class)
                .single();
    }

    private String resolvedEventDetail(String pid, String caseId) {
        return events.listByCase(pid, caseId).stream()
                .filter(e -> CaseEventRow.Kind.RESOLVED.equals(e.kind()))
                .findFirst()
                .map(CaseEventRow::detail)
                .orElseThrow();
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
    }

    private ClassifierRow frustration(String pid) {
        classifierService.seedBuiltIns(pid);
        return classifiers.findByKey(pid, "frustration").orElseThrow();
    }

    /** {@code perHour} one-turn conversations an hour, the first {@code rate} of each hour flagged. */
    private void seedHours(
            String pid,
            ClassifierRow signal,
            String callSite,
            Instant start,
            int fromHour,
            int hours,
            int perHour,
            double rate) {
        long flaggedPerHour = Math.round(perHour * rate);
        for (int h = fromHour; h < fromHour + hours; h++) {
            for (int c = 0; c < perHour; c++) {
                String trace = callSite + "-" + h + "-" + String.format(Locale.ROOT, "%02d", c);
                boolean flagged = c < flaggedPerHour;
                Instant at = start.plus(Duration.ofHours(h)).plusSeconds(c);
                assessments.insert(new Assessment(
                        Ids.ulid(),
                        pid,
                        signal.id(),
                        trace,
                        "span-" + trace,
                        "conv-" + trace,
                        callSite,
                        at,
                        flagged,
                        VERSION,
                        "TYPESAFE",
                        "typesafe/jev-1.13-20260917",
                        null,
                        "{}",
                        null,
                        null,
                        null));
                if (flagged) flag(pid, signal, trace, "conv-" + trace, callSite, at);
            }
        }
    }

    private void flag(
            String pid, ClassifierRow signal, String traceId, String conversation, String callSite, Instant at) {
        jdbc.sql("INSERT INTO " + TurnGrainTestDetectionConfig.TABLE
                        + " (id, project_id, classifier_id, classifier_key, subject_session_id, subject_trace_id,"
                        + " severity, confidence, evidence, subject_started_at)"
                        + " VALUES (:id, :pid, :cid, 'frustration', :conv, :trace, 'warn', 'high',"
                        + " CAST(:evidence AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("conv", conversation)
                .param("trace", traceId)
                .param("evidence", "{\"score\":0.71,\"call_site_id\":\"" + callSite + "\"}")
                .param("at", Timestamp.from(at))
                .update();
    }
}
