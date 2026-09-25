// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorTriageSource;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.Assessment;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedConversationView;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedSessionPage;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A rising call site end to end against Postgres: the replay files one finding for the spell, ruled positive at
 * filing, with every scored session as a member and every frustrated one as a session and trace witness pair,
 * read back a page at a time; the case opens under detector
 * {@code frustration}; a second pass refreshes rather than re-files; and once someone runs RCA on the case, the
 * next spell opens a new one.
 */
@SpringBootTest
class FrustrationRateIntegrationTest {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);

    @Autowired
    FrustrationAssessmentRepository assessments;

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
    ClassifierRepository classifiers;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    FrustrationDetailService frustrationDetail;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FrustrationRateRepository rates;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Test
    void aRiseFilesOneRuledFindingWithEverySessionAsEvidenceAndOpensItsCase() {
        String pid = project("fr-case");
        ClassifierRow signal = frustration(pid);
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05); // 210 conversations of reference at 5%
        seedHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40); // then 180 at 40%
        seedHours(pid, signal, "cs-calm", start, 0, 13, 30, 0.05);

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
        assertEquals(210, finding.payload().path("baseline_conversations").asLong());
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
        assertEquals(210, block.rate().nRef());
        assertEquals(14, block.baselineFrustrated(), "two of every thirty in the seven reference hours");
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

        // The second cause names one session and cites one trace: the page reads them off the stored report.
        String report = rcaReport(
                pid,
                finding.id(),
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
        String pid = project("fr-refresh");
        ClassifierRow signal = frustration(pid);
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        seedHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());
        FindingRow before = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);

        seedHours(pid, signal, "cs-chat", start, 13, 1, 30, 0.40); // the spell runs on another hour
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

    @Test
    void afterRcaLocksTheCaseTheNextSpellOpensANewOne() {
        String pid = project("fr-lock");
        ClassifierRow signal = frustration(pid);
        Instant start = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        seedHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());
        FindingRow firstSpell = findings.listByProject(pid, null, null, "frustration", false, 10)
                .get(0);
        String firstCase = firstSpell.caseId();
        assertNotNull(firstCase);
        cases.lock(pid, firstCase, Instant.now());

        // Calm traffic drains the accumulator back to zero, then the rate climbs again: a new spell.
        seedHours(pid, signal, "cs-chat", start, 13, 30, 30, 0.0);
        seedHours(pid, signal, "cs-chat", start, 43, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());

        List<FindingRow> spells = findings.listByProject(pid, null, null, "frustration", false, 10);
        assertEquals(2, spells.size(), "one finding per spell");
        FindingRow secondSpell = spells.stream()
                .filter(f -> !f.id().equals(firstSpell.id()))
                .findFirst()
                .orElseThrow();
        assertNotEquals(firstSpell.onsetAt(), secondSpell.onsetAt());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, secondSpell.triageVerdict());
        assertNotNull(secondSpell.caseId());
        assertNotEquals(firstCase, secondSpell.caseId(), "a locked case takes no new finding");
        assertEquals(
                CaseRow.Detector.FRUSTRATION,
                cases.findById(pid, secondSpell.caseId()).orElseThrow().detector());
    }

    // ---- fixtures

    /** A finished frustration RCA report on {@code findingId} carrying {@code causes}, and its job. */
    private String rcaReport(String pid, String findingId, String causes) {
        String job = Ids.ulid();
        String report = Ids.ulid();
        String now = Instant.now().toString();
        jdbc.sql("INSERT INTO job (id, project_id, kind, status, payload, created_at, updated_at)"
                        + " VALUES (:id, :pid, 'rca', 'done', CAST('{}' AS jsonb), :now, :now)")
                .param("id", job)
                .param("pid", pid)
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO rca_report (id, project_id, job_id, subject_kind, subject_id, subject_label, metric,"
                        + " window_from, window_split, window_to, current_value, prior_value, delta, status,"
                        + " created_at, engine, finding_id, report_kind, causes)"
                        + " VALUES (:id, :pid, :job, 'call_site', 'cs-chat', 'cs-chat', 'frustration_rate',"
                        + " :now, :now, :now, 0, 0, 0, 'done', :now, 'agentic', :fid, 'frustration_causes',"
                        + " CAST(:causes AS jsonb))")
                .param("id", report)
                .param("pid", pid)
                .param("job", job)
                .param("now", now)
                .param("fid", findingId)
                .param("causes", causes)
                .update();
        return report;
    }

    /**
     * A page past the last frustrated session still carries how many the finding cites, and a stored score the
     * reader cannot parse shows the session unscored rather than failing the finding page.
     */
    @Test
    void aPagePastTheEndKeepsTheTotalAndAnUnreadableScoreIsUnscored() {
        String pid = project("fr-past-end");
        ClassifierRow signal = frustration(pid);
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, "cs-chat", start, 0, 7, 30, 0.05);
        seedHours(pid, signal, "cs-chat", start, 7, 6, 30, 0.40);
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
                rates.flaggedTurns(pid, signal.id(), List.of(trace)).get(trace);
        assertNotNull(turn);
        assertNull(turn.score());
        assertEquals("cs-chat", turn.callSiteId());
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
    }

    private ClassifierRow frustration(String pid) {
        classifierService.seedBuiltIns(pid);
        return ClassifierRows.byKey(classifiers, pid, "frustration").orElseThrow();
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
        jdbc.sql("INSERT INTO " + "frustration_detection"
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

    /** The grain a ref is at: a span ref carries a span id, a trace ref a trace id, a session ref neither. */
    private static String grain(FindingEvidenceRow row) {
        if (row.spanId() != null) return "span";
        return row.traceId() != null ? "trace" : "session";
    }
}
