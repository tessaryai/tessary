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
import ai.tessary.classifier.frustration.FrustrationRateRepository.SessionOrder;
import ai.tessary.plan.Capability;
import ai.tessary.testsupport.RateClassifierFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A rising call site against Postgres: one finding per spell, ruled positive at filing, a turn per session-hour as
 * members and the frustrated ones' flagged turns as witnesses, paged; the case opens under {@code frustration}; a second pass refreshes; after
 * RCA runs, the next spell opens a new case.
 */
@SpringBootTest
record FrustrationRateIntegrationTest(
        FrustrationRateService service,
        FindingRepository findings,
        FindingEvidenceRepository evidence,
        CaseRepository cases,
        CaseService caseService,
        BehaviorTriageSource triageSource,
        FrustrationDetailService frustrationDetail,
        JdbcClient jdbc,
        FrustrationRateRepository rates,
        RateClassifierFixture fixture) {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

    /**
     * Session rows written beside or instead of turn rows, a witness turn that is not also a member, or a turn row
     * without its session (which the clearer, the retention pin and RCA's session citations read) are the bugs; so is
     * a grain check that refuses a row naming both a session and a trace.
     */
    @Test
    void aRiseFilesOneRuledFindingWithEveryTrialAsATurnRowAndOpensItsCase() {
        String pid = fixture.project("fr-case", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        risenCallSite(pid, signal, start);
        fixture.frustrationHours(pid, signal, "cs-calm", start, 0, 46, 30, 0.05);

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
        assertEquals(180, finding.sampleCount(), "session-hours since onset");
        assertEquals(1020, finding.payload().path("baseline_conversations").asLong());
        assertEquals(VERSION, finding.payload().path("scorer_version").asText());

        List<FindingEvidenceRow> all = evidence.listByFinding(pid, finding.id());
        List<FindingEvidenceRow> members = all.stream()
                .filter(r -> FindingEvidenceRow.Role.MEMBER.equals(r.role()))
                .toList();
        List<FindingEvidenceRow> rows = all.stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()))
                .toList();
        assertEquals(252, all.size(), "a member row per trial and a witness row per frustrated one, nothing else");
        assertTrue(
                all.stream().allMatch(r -> r.traceId() != null && r.spanId() == null),
                "every row is a turn, no session rows");
        assertTrue(
                all.stream().allMatch(r -> ("conv-" + r.traceId()).equals(r.sessionId())),
                "every turn row names its own session");
        assertEquals(180, members.size(), "every session-hour the rate counted, calm ones included");
        assertEquals(72, rows.size(), "every frustrated session-hour, not a sample of them");
        Set<String> memberTraces =
                members.stream().map(FindingEvidenceRow::traceId).collect(Collectors.toSet());
        assertTrue(
                rows.stream().allMatch(r -> memberTraces.contains(r.traceId())), "every witness turn is also a member");

        assertNotNull(finding.caseId(), "the case opened in the same pass");
        CaseRow opened = cases.findById(pid, finding.caseId()).orElseThrow();
        assertEquals(CaseRow.Detector.FRUSTRATION, opened.detector());
        assertEquals(CaseRow.SubjectKind.CALL_SITE, opened.subjectKind());
        assertEquals("cs-chat", opened.subjectId());
        assertEquals(FrustrationEvidence.MEASURE, opened.metric());
        assertEquals(FindingTitle.of(finding), opened.title());
        assertTrue(opened.basis().startsWith("72 of the 180 session-hours since"), opened.basis());

        BehaviorFindingDetailView detail =
                triageSource.detail(pid, finding.id()).orElseThrow();
        FrustrationDetail block = detail.frustration();
        assertNotNull(block, "the finding page gets its frustration block");
        assertEquals(180, block.rate().nCur());
        assertEquals(72, block.rate().failuresCur());
        assertEquals(1020, block.rate().nRef());
        assertEquals(68, block.baselineFrustrated(), "two of every thirty for 34 hours, and not the rise");
        assertEquals(FrustrationDetailService.PAGE_SIZE, block.conversations().size(), "the first page");
        assertEquals("50", block.conversationsNextCursor());
        FrustratedConversationView row = block.conversations().get(0);
        assertEquals("conv-cs-chat-45-11", row.conversationId(), "every score ties at 0.71, so the newest flag first");
        assertEquals(0.71, row.score());
        assertEquals("cs-chat", row.callSiteId());
        assertFalse(row.cleared());
        assertEquals(
                row.traceId(), row.contextTraceIds().get(row.contextTraceIds().size() - 1), "flagged turn last");

        FrustratedSessionPage rest =
                frustrationDetail.page(finding, null, SessionOrder.DEFAULT, FrustrationDetailService.PAGE_SIZE, "50");
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
                "[{\"evidence_session_ids\":[\"conv-cs-chat-45-00\"],\"evidence_trace_ids\":[]},"
                        + "{\"evidence_session_ids\":[\"conv-cs-chat-43-00\"],"
                        + "\"evidence_trace_ids\":[\"cs-chat-44-01\"]}]");
        FrustratedSessionPage oneCause = frustrationDetail.page(
                finding, new FrustrationRateRepository.CauseRef(report, 1), SessionOrder.DEFAULT, 50, null);
        assertEquals(
                List.of("cs-chat-44-01", "cs-chat-43-00"),
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
        risenCallSite(pid, signal, start);
        service.refresh(pid, signal, Instant.now());
        FindingRow before = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);

        fixture.frustrationHours(pid, signal, "cs-chat", start, 46, 1, 30, 0.40); // the spell runs on another hour
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

    /**
     * The turn that stands for a conversation-hour moves when a later turn in that hour is scored. A row left on the
     * turn it replaced counts that conversation-hour twice, and on both sides when the later turn is flagged.
     */
    @Test
    void aLaterTurnInTheSameHourReplacesTheRowsOfTheTurnBeforeIt() {
        String pid = fixture.project("fr-two-pass", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        risenCallSite(pid, signal, start);
        Instant hour = start.plus(45, ChronoUnit.HOURS);
        fixture.frustrationAssessment(
                pid, signal, "g-1010", "conv-g", "cs-chat", hour.plus(10, ChronoUnit.MINUTES), false, VERSION);
        fixture.frustrationAssessment(
                pid, signal, "e-1010", "conv-e", "cs-chat", hour.plus(10, ChronoUnit.MINUTES), false, VERSION);
        service.refresh(pid, signal, Instant.now());

        Instant later = hour.plus(40, ChronoUnit.MINUTES);
        fixture.frustrationAssessment(pid, signal, "g-1040", "conv-g", "cs-chat", later, true, VERSION);
        fixture.frustrationFlag(pid, signal, "g-1040", "conv-g", "cs-chat", later);
        fixture.frustrationAssessment(pid, signal, "e-1040", "conv-e", "cs-chat", later, false, VERSION);
        service.refresh(pid, signal, Instant.now());

        List<FindingRow> filed = findings.listByProject(pid, null, null, "frustration", false, 10);
        assertEquals(1, filed.size(), "the same spell is one finding");
        FindingRow finding = filed.get(0);
        List<FindingEvidenceRow> all = evidence.listByFinding(pid, finding.id());
        assertEquals(
                List.of("member g-1040", "witness g-1040"),
                all.stream()
                        .filter(r -> "conv-g".equals(r.sessionId()))
                        .map(r -> r.role() + " " + r.traceId())
                        .sorted()
                        .toList(),
                "the flagged turn alone stands for conv-g's hour, on both sides");
        assertEquals(
                List.of("member e-1040"),
                all.stream()
                        .filter(r -> "conv-e".equals(r.sessionId()))
                        .map(r -> r.role() + " " + r.traceId())
                        .toList(),
                "the latest calm turn alone stands for conv-e's hour");
        long members = all.stream()
                .filter(r -> FindingEvidenceRow.Role.MEMBER.equals(r.role()))
                .count();
        assertEquals(finding.sampleCount(), members, "one member row per trial");
        assertEquals(members, finding.evidenceCount(FindingEvidenceRow.Role.MEMBER), "the count follows the rows");
        assertEquals(
                all.size() - members,
                finding.evidenceCount(FindingEvidenceRow.Role.WITNESS),
                "the witness count follows the rows");
    }

    /** A page past the last session still carries the count, and an unparseable score lists the session unscored. */
    @Test
    void aPagePastTheEndKeepsTheTotalAndAnUnreadableScoreIsUnscored() {
        String pid = fixture.project("fr-past-end", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        risenCallSite(pid, signal, start);
        service.refresh(pid, signal, Instant.now());
        FindingRow finding = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);

        FrustratedSessionPage past = frustrationDetail.page(finding, null, SessionOrder.DEFAULT, 50, "500");
        assertEquals(List.of(), past.rows());
        assertEquals(72, past.total(), "the finding still cites every frustrated session-hour");
        assertNull(past.nextCursor());

        String trace = "cs-chat-45-00";
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

    /**
     * Highest score first: a tie goes to the newer flag, and a missing or non-numeric score goes last instead of failing
     * the page or sorting first; the finding page's first page is in that order too, Newest keeps the old order, and
     * two pages read in one order never share a session.
     */
    @Test
    void sessionsSortByScoreWithTiesToTheNewerFlagAndNoScoreLast() {
        String pid = fixture.project("fr-sort", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, "frustration");
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        risenCallSite(pid, signal, start);
        service.refresh(pid, signal, Instant.now());
        FindingRow finding = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);
        storeEvidence(pid, "cs-chat-40-00", "{\"score\":0.99,\"call_site_id\":\"cs-chat\"}");
        storeEvidence(pid, "cs-chat-41-03", "{\"score\":0.95,\"call_site_id\":\"cs-chat\"}");
        storeEvidence(pid, "cs-chat-43-07", "{\"score\":0.95,\"call_site_id\":\"cs-chat\"}");
        storeEvidence(pid, "cs-chat-44-05", "{\"score\":\"high\",\"call_site_id\":\"cs-chat\"}");
        storeEvidence(pid, "cs-chat-42-02", "{\"call_site_id\":\"cs-chat\"}");

        FrustratedSessionPage first = frustrationDetail.page(finding, null, SessionOrder.SCORE, 50, null);
        assertEquals(
                List.of("cs-chat-40-00", "cs-chat-43-07", "cs-chat-41-03", "cs-chat-45-11"),
                traces(first).subList(0, 4),
                "the highest score, then the tie by the newer flag, then the 0.71s newest first");
        FrustratedSessionPage second =
                frustrationDetail.page(finding, null, SessionOrder.SCORE, 50, first.nextCursor());
        List<String> rest = traces(second);
        assertEquals(
                List.of("cs-chat-44-05", "cs-chat-42-02"),
                rest.subList(rest.size() - 2, rest.size()),
                "no readable score goes last, the newer flag first");
        assertEquals(72, Set.copyOf(concat(traces(first), rest)).size(), "two pages, every session once");

        FrustrationDetail block =
                triageSource.detail(pid, finding.id()).orElseThrow().frustration();
        assertNotNull(block);
        assertEquals("cs-chat-40-00", block.conversations().get(0).traceId(), "the finding page opens in score order");

        FrustratedSessionPage newest = frustrationDetail.page(finding, null, SessionOrder.NEWEST, 50, null);
        assertEquals("cs-chat-45-11", traces(newest).get(0), "Newest ignores the score");
        assertFalse(traces(newest).contains("cs-chat-40-00"), "the top score is on Newest's last page");
    }

    private void storeEvidence(String pid, String trace, String evidenceJson) {
        jdbc.sql("UPDATE frustration_detection SET evidence = CAST(:evidence AS jsonb)"
                        + " WHERE project_id = :pid AND subject_trace_id = :trace")
                .param("evidence", evidenceJson)
                .param("pid", pid)
                .param("trace", trace)
                .update();
    }

    private static List<String> traces(FrustratedSessionPage page) {
        return page.rows().stream().map(FrustratedConversationView::traceId).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    /**
     * 34 hours at 5%, which freezes the reference at 1,020 session-hours with 68 frustrated, then 180 at 40% from hour
     * 40: one-turn conversations, so each is one trial.
     */
    private void risenCallSite(String pid, ClassifierRow signal, Instant start) {
        fixture.frustrationHours(pid, signal, "cs-chat", start, 0, 34, 30, 0.05);
        fixture.frustrationHours(pid, signal, "cs-chat", start, 40, 6, 30, 0.40);
    }
}
