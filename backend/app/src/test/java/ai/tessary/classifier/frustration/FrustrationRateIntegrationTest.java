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
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.StubEncoderScorerConfig;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A rising call site end to end against Postgres: the replay files one finding for the spell, ruled positive at
 * filing, with the frustrated conversations as session and trace witness pairs; the case opens under detector
 * {@code frustration}; a second pass refreshes rather than re-files; and once someone runs RCA on the case, the
 * next spell opens a new one.
 *
 * <p>Shares the turn-grain fingerprint, whose test table stands in for {@code frustration_detection}.
 */
@SpringBootTest
@Import(StubEncoderScorerConfig.class)
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
    BehaviorTriageSource triageSource;

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

    @Test
    void aRiseFilesOneRuledFindingWithConversationWitnessesAndOpensItsCase() {
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
        assertEquals("Users are frustrated with cs-chat", FindingTitle.of(finding));
        assertEquals(180, finding.sampleCount(), "conversations since onset");
        assertEquals(210, finding.payload().path("baseline_conversations").asLong());
        assertEquals(VERSION, finding.payload().path("scorer_version").asText());

        List<FindingEvidenceRow> rows = evidence.listByFinding(pid, finding.id());
        assertTrue(rows.stream().allMatch(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role())), "witness only");
        long sessions = rows.stream().filter(r -> "session".equals(r.grain())).count();
        long traces = rows.stream().filter(r -> "trace".equals(r.grain())).count();
        assertEquals(FrustrationRateService.MAX_WITNESSES, sessions, "capped at fifty conversations");
        assertEquals(sessions, traces, "each conversation beside the turn that fired in it");
        FindingEvidenceRow first = rows.get(0);
        FindingEvidenceRow second = rows.get(1);
        assertEquals("session", first.grain());
        assertEquals("trace", second.grain());
        assertEquals("conv-" + second.traceId(), first.sessionId(), "the pair names one conversation");
        assertEquals("conv-cs-chat-12-11", first.sessionId(), "newest conversations first");

        assertNotNull(finding.caseId(), "the case opened in the same pass");
        CaseRow opened = cases.findById(pid, finding.caseId()).orElseThrow();
        assertEquals(CaseRow.Detector.FRUSTRATION, opened.detector());
        assertEquals(CaseRow.SubjectKind.CALL_SITE, opened.subjectKind());
        assertEquals("cs-chat", opened.subjectId());
        assertEquals(FrustrationEvidence.MEASURE, opened.metric());
        assertEquals("Users are frustrated with cs-chat", opened.title());
        assertTrue(opened.basis().startsWith("72 of the 180 conversations since"), opened.basis());

        BehaviorFindingDetailView detail =
                triageSource.detail(pid, finding.id()).orElseThrow();
        FrustrationDetail block = detail.frustration();
        assertNotNull(block, "the finding page gets its frustration block");
        assertEquals(180, block.rate().nCur());
        assertEquals(72, block.rate().failuresCur());
        assertEquals(210, block.rate().nRef());
        assertEquals(14, block.baselineFrustrated(), "two of every thirty in the seven reference hours");
        assertEquals(FrustrationRateService.MAX_WITNESSES, block.conversations().size());
        FrustratedConversationView row = block.conversations().get(0);
        assertEquals(first.sessionId(), row.conversationId());
        assertEquals(0.71, row.score());
        assertEquals("cs-chat", row.callSiteId());
        assertFalse(row.cleared());
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
}
