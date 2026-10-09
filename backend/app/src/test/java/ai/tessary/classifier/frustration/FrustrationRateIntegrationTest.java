// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorTriageSource;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedConversationView;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedSessionPage;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.plan.Capability;
import ai.tessary.testsupport.RateClassifierFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A rising call site against Postgres: one finding per spell, ruled positive at filing, scored sessions as members
 * and frustrated ones as witnesses, paged; the case opens under {@code frustration}; a second pass refreshes; after
 * RCA runs, the next spell opens a new case.
 */
@SpringBootTest
class FrustrationRateIntegrationTest {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

    @Autowired
    FrustrationRateService service;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseService caseService;

    @Autowired
    BehaviorTriageSource triageSource;

    @Autowired
    FrustrationDetailService frustrationDetail;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FrustrationRateRepository rates;

    @Autowired
    RateClassifierFixture fixture;

    @Test
    void aRiseFilesOneRuledFindingWithEverySessionAsEvidenceAndOpensItsCase() {
        String pid = fixture.project("fr-case", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        // Judged from 100, and every hour is added to the reference until 1,000, the risen ones included.
        fixture.frustrationHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05); // 210 conversations at 5%
        fixture.frustrationHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40); // then 180 at 40%
        fixture.frustrationHours(pid, signal, "cs-calm", start, 0, 13, 30, 0.05);

        service.refresh(pid, signal, Instant.now());

        List<FindingRow> filed = findings.listByProject(pid, null, null, "frustration", false, 10);
        assertEquals(1, filed.size(), "one finding, on the call site that rose");
        FindingRow finding = filed.get(0);
        assertEquals("cs-chat", finding.callSiteId());
        assertEquals(FindingRow.Cause.FRUSTRATION_RATE, finding.causeKind());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, finding.triageVerdict(), "ruled at filing, no triage");
        assertEquals(FrustrationEvidence.SUMMARY, finding.triageSummary());
        assertNull(finding.escalatedAt(), "never escalated to Layer 2");
        String title = FindingTitle.of(finding);
        assertTrue(title.matches("Frustrated sessions increased from \\d+\\.\\d% to \\d+\\.\\d% on cs-chat"), title);
        assertEquals(180, finding.sampleCount(), "sessions since onset");
        assertEquals(390, finding.payload().path("baseline_conversations").asLong());
        assertEquals(VERSION, finding.payload().path("scorer_version").asText());

        List<FindingEvidenceRow> all = evidence.listByFinding(pid, finding.id());
        List<FindingEvidenceRow> members = all.stream()
                .filter(r -> FindingEvidenceRow.Role.MEMBER.equals(r.role()))
                .toList();
        List<FindingEvidenceRow> rows = all.stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()))
                .toList();
        assertEquals(180, members.size(), "every session the rate counted, calm ones included");
        assertTrue(members.stream().allMatch(r -> "session".equals(grain(r))));
        long sessions = rows.stream().filter(r -> "session".equals(grain(r))).count();
        long traces = rows.stream().filter(r -> "trace".equals(grain(r))).count();
        assertEquals(72, sessions, "every frustrated session, not a sample of them");
        assertEquals(sessions, traces, "each session beside the turn that fired in it");
        FindingEvidenceRow first = rows.get(0);
        FindingEvidenceRow second = rows.get(1);
        assertEquals("session", grain(first));
        assertEquals("trace", grain(second));
        assertEquals("conv-" + second.traceId(), first.sessionId(), "the pair names one session");

        assertNotNull(finding.caseId(), "the case opened in the same pass");
        CaseRow opened = cases.findById(pid, finding.caseId()).orElseThrow();
        assertEquals(CaseRow.Detector.FRUSTRATION, opened.detector());
        assertEquals(CaseRow.SubjectKind.CALL_SITE, opened.subjectKind());
        assertEquals("cs-chat", opened.subjectId());
        assertEquals(FrustrationEvidence.MEASURE, opened.metric());
        assertEquals(FindingTitle.of(finding), opened.title());
        assertTrue(opened.basis().startsWith("72 of the 180 sessions since"), opened.basis());

        BehaviorFindingDetailView detail =
                triageSource.detail(pid, finding.id()).orElseThrow();
        FrustrationDetail block = detail.frustration();
        assertNotNull(block, "the finding page gets its frustration block");
        assertEquals(180, block.rate().nCur());
        assertEquals(72, block.rate().failuresCur());
        assertEquals(390, block.rate().nRef());
        assertEquals(86, block.baselineFrustrated(), "two of every thirty for seven hours, then twelve for six");
        assertEquals(FrustrationDetailService.PAGE_SIZE, block.conversations().size(), "the first page");
        assertEquals("50", block.conversationsNextCursor());
        FrustratedConversationView row = block.conversations().get(0);
        assertEquals("conv-cs-chat-12-11", row.conversationId(), "newest flag first");
        assertEquals(0.71, row.score());
        assertEquals("cs-chat", row.callSiteId());
        assertFalse(row.cleared());
        assertEquals(
                row.traceId(), row.contextTraceIds().get(row.contextTraceIds().size() - 1), "flagged turn last");

        FrustratedSessionPage rest = frustrationDetail.page(finding, null, FrustrationDetailService.PAGE_SIZE, "50");
        assertEquals(22, rest.rows().size(), "the rest, on the next page");
        assertEquals(72, rest.total());
        assertNull(rest.nextCursor(), "nothing after it");
        assertTrue(
                rest.rows().stream()
                        .noneMatch(r -> block.conversations().stream()
                                .anyMatch(b -> b.traceId().equals(r.traceId()))),
                "no session on both pages");

        // The second cause's session and trace come off the stored report.
        String report = fixture.rcaReport(
                pid,
                finding.id(),
                "call_site",
                "cs-chat",
                "cs-chat",
                "frustration_rate",
                "frustration_causes",
                "[{\"evidence_session_ids\":[\"conv-cs-chat-12-00\"],\"evidence_trace_ids\":[]},"
                        + "{\"evidence_session_ids\":[\"conv-cs-chat-10-00\"],"
                        + "\"evidence_trace_ids\":[\"cs-chat-11-01\"]}]");
        FrustratedSessionPage oneCause =
                frustrationDetail.page(finding, new FrustrationRateRepository.CauseRef(report, 1), 50, null);
        assertEquals(
                List.of("cs-chat-11-01", "cs-chat-10-00"),
                oneCause.rows().stream()
                        .map(FrustratedConversationView::traceId)
                        .toList(),
                "a cause's share, by its sessions or its traces");
        assertEquals(2, oneCause.total());

        FrustrationDetail onCase = caseService.detail(pid, opened.id()).frustration();
        assertNotNull(onCase, "the case page gets the same block");
        assertEquals(block.rate().nCur(), onCase.rate().nCur());
        assertEquals(block.conversations().size(), onCase.conversations().size());
    }

    @Test
    void aSecondPassOnTheSameSpellRefreshesTheFindingRatherThanFilingAnother() {
        String pid = fixture.project("fr-refresh", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());
        FindingRow before = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);

        fixture.frustrationHours(pid, signal, "cs-chat", start, 13, 1, 30, 0.40); // the spell runs on another hour
        service.refresh(pid, signal, Instant.now());

        List<FindingRow> after = findings.listByProject(pid, null, null, "frustration", false, 10);
        assertEquals(1, after.size(), "the same spell is one finding");
        FindingRow refreshed = after.get(0);
        assertEquals(before.id(), refreshed.id());
        assertEquals(before.onsetAt(), refreshed.onsetAt());
        assertEquals(210, refreshed.sampleCount(), "today's numbers, not the filing's");
        assertTrue(Instant.parse(refreshed.lastSeenAt()).isAfter(Instant.parse(before.lastSeenAt())));
        assertEquals(FindingRow.TriageVerdict.POSITIVE, refreshed.triageVerdict(), "the ruling is untouched");
        assertEquals(FrustrationEvidence.SUMMARY, refreshed.triageSummary());
        assertEquals(
                "frustration_rate",
                refreshed.payload().path("cause_kind").asText(),
                "the native vocabulary survives the refresh");
        assertEquals(before.caseId(), refreshed.caseId());
        assertEquals(1, cases.listLive(pid).size());
    }

    /** A page past the last session still carries the count, and an unparseable score lists the session unscored. */
    @Test
    void aPagePastTheEndKeepsTheTotalAndAnUnreadableScoreIsUnscored() {
        String pid = fixture.project("fr-past-end", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());
        FindingRow finding = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);

        FrustratedSessionPage past = frustrationDetail.page(finding, null, 50, "500");
        assertEquals(List.of(), past.rows());
        assertEquals(72, past.total(), "the finding still cites every frustrated session");
        assertNull(past.nextCursor());

        String trace = "cs-chat-12-00";
        jdbc.sql("UPDATE frustration_detection SET evidence = CAST(:evidence AS jsonb)"
                        + " WHERE project_id = :pid AND subject_trace_id = :trace")
                .param("evidence", "{\"score\":\"high\",\"call_site_id\":\"cs-chat\"}")
                .param("pid", pid)
                .param("trace", trace)
                .update();
        FrustrationRateRepository.FlaggedTurn turn =
                rates.flaggedTurns(pid, signal.id(), "cs-chat", List.of(trace)).get(trace);
        assertNotNull(turn);
        assertNull(turn.score());
        assertEquals("cs-chat", turn.callSiteId());
    }

    /** A span ref carries a span id, a trace ref a trace id, a session ref neither. */
    private static String grain(FindingEvidenceRow row) {
        if (row.spanId() != null) return "span";
        return row.traceId() != null ? "trace" : "session";
    }
}
