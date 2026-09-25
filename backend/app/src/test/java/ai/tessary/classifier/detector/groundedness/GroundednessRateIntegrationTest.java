// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.cases.CaseOpener;
import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository.Assessment;
import ai.tessary.classifier.finding.BehaviorTriageEngine;
import ai.tessary.classifier.finding.BehaviorTriageJobRow;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A call site whose answers became less grounded, end to end against Postgres: the replay files one unruled
 * finding for the spell, with every scored trace as a member and every flagged one as a trace and span witness
 * pair; the same onset refreshes it; triage's positive opens a case under detector {@code groundedness}; a
 * false-alarm resolve clears the cited flags, past the first statement's worth too, and re-learns; an absorb
 * re-learns; and a call site still learning files nothing.
 */
@SpringBootTest
class GroundednessRateIntegrationTest {

    private static final String VERSION = GroundednessDetector.scorerVersion(GroundednessConfig.DEFAULT_THRESHOLD);

    private static final String CALL_SITE = "cs-rag";

    @Autowired
    GroundednessAssessmentRepository assessments;

    @Autowired
    GroundednessRateService service;

    @Autowired
    GroundednessRateRepository rateRows;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseEventRepository events;

    @Autowired
    CaseOpener caseOpener;

    @Autowired
    CaseService caseService;

    @Autowired
    BehaviorTriageEngine triageEngine;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    GroundednessAnswerClearer clearer;

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
    void aRiseFilesOneUnruledFindingWithEveryScoredTraceAsEvidence() {
        String pid = project("gr-rise");
        ClassifierRow signal = groundedness(pid);
        Instant start = start();
        seedHours(pid, signal, CALL_SITE, start, 0, 7, 30, 0.05); // 210 traces of reference at 5%
        seedHours(pid, signal, CALL_SITE, start, 7, 6, 30, 0.40); // then 180 at 40%
        seedHours(pid, signal, "cs-calm", start, 0, 13, 30, 0.05);

        service.refresh(pid, signal, Instant.now());

        List<FindingRow> filed = findings.listByProject(pid, null, null, "groundedness", false, 10);
        assertEquals(1, filed.size(), "one finding, on the call site that rose");
        FindingRow finding = filed.get(0);
        assertEquals(CALL_SITE, finding.callSiteId());
        assertEquals(FindingRow.Cause.GROUNDEDNESS_RATE, finding.causeKind());
        assertEquals(FindingRow.SubjectKind.CLASSIFIER, finding.subjectKind());
        assertEquals(signal.id(), finding.subjectId());
        assertNull(finding.triageVerdict(), "filed unruled, for triage");
        assertNull(finding.caseId(), "no case until a ruling");
        assertEquals("Answers on cs-rag became less grounded", FindingTitle.of(finding));

        // From the seed plan: every cs-rag hour from the onset on, 30 traces each, of which 2 flagged in a
        // reference hour (0-6) and 12 in a spell hour (7-12). cs-calm is another call site and counts nowhere.
        Instant onset = Instant.parse(Objects.requireNonNull(finding.onsetAt()));
        assertEquals(onset.truncatedTo(ChronoUnit.HOURS), onset, "the onset is an hour bucket");
        long onsetHour = Duration.between(start, onset).toHours();
        assertTrue(onsetHour > 0 && onsetHour < 13, "setup: the onset is inside the window, not its start: " + onset);
        long referenceHours = Math.max(0, 7 - onsetHour);
        long spellHours = 13 - Math.max(7, onsetHour);
        long tracesSinceOnset = 30 * (referenceHours + spellHours);
        long flaggedSinceOnset = 2 * referenceHours + 12 * spellHours;
        assertEquals(
                tracesSinceOnset, finding.payload().path("traces_since_onset").asLong(), finding.payloadJson());
        assertEquals(
                flaggedSinceOnset, finding.payload().path("flagged_since_onset").asLong(), finding.payloadJson());
        assertEquals(flaggedSinceOnset, finding.sampleCount(), "the flagged traces triage has to read");
        assertEquals(0.975, finding.payload().path("flag_threshold").asDouble());
        assertEquals(1_000, finding.payload().path("learning_until").asLong());
        assertEquals(50_000, finding.payload().path("arl_target").asLong());
        assertEquals(VERSION, finding.payload().path("scorer_version").asText());

        List<FindingEvidenceRow> all = evidence.listByFinding(pid, finding.id());
        List<FindingEvidenceRow> members = all.stream()
                .filter(r -> FindingEvidenceRow.Role.MEMBER.equals(r.role()))
                .toList();
        List<FindingEvidenceRow> witnessTraces = all.stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()) && r.spanId() == null)
                .toList();
        List<FindingEvidenceRow> witnessAnswers = all.stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()) && r.spanId() != null)
                .toList();
        assertEquals(tracesSinceOnset, members.size(), "every trace the rate counted, clean ones included");
        assertEquals(flaggedSinceOnset, witnessTraces.size(), "every flagged trace, not a sample of them");
        long doubled = witnessTraces.stream().filter(r -> doubled(r.traceId())).count();
        assertTrue(doubled > 0);
        assertEquals(witnessTraces.size() + doubled, witnessAnswers.size(), "each flagged answer in them");
        assertTrue(members.stream().allMatch(r -> r.traceId() != null && r.spanId() == null));

        assertTrue(
                findings
                        .listAutoEscalatable(pid, List.of(BuiltInDetector.Kind.GROUNDEDNESS), flaggedSinceOnset, 10)
                        .stream()
                        .anyMatch(f -> f.id().equals(finding.id())),
                "eligible for triage: its sample count is the flagged traces and it cites traces");
    }

    @Test
    void aTraceWithTwoFlaggedAnswersIsOneFailure() {
        String pid = project("gr-trace");
        ClassifierRow signal = groundedness(pid);
        Instant start = start();
        seedHours(pid, signal, CALL_SITE, start, 0, 1, 30, 0.40); // 12 flagged traces, 6 with two flags

        List<HourlyToolTally> tallies =
                rateRows.hourlyTallies(pid, signal.id(), VERSION, start.minus(Duration.ofHours(1)));

        assertEquals(1, tallies.size());
        assertEquals(30, tallies.get(0).calls(), "a trace is one trial");
        assertEquals(12, tallies.get(0).failures(), "and one failure however many of its answers were flagged");
    }

    @Test
    void aSecondPassOnTheSameOnsetRefreshesTheFindingRatherThanFilingAnother() {
        String pid = project("gr-refresh");
        ClassifierRow signal = groundedness(pid);
        Instant start = start();
        seedHours(pid, signal, CALL_SITE, start, 0, 7, 30, 0.05);
        seedHours(pid, signal, CALL_SITE, start, 7, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());
        FindingRow before = findings.listByProject(pid, null, null, "groundedness", false, 10)
                .get(0);

        seedHours(pid, signal, CALL_SITE, start, 13, 1, 30, 0.40); // the spell runs on another hour
        service.refresh(pid, signal, Instant.now());

        List<FindingRow> after = findings.listByProject(pid, null, null, "groundedness", false, 10);
        assertEquals(1, after.size(), "the same spell is one finding");
        FindingRow refreshed = after.get(0);
        assertEquals(before.id(), refreshed.id());
        assertEquals(before.onsetAt(), refreshed.onsetAt());
        assertEquals(before.sampleCount() + 12, refreshed.sampleCount(), "today's numbers, not the filing's");
        assertNull(refreshed.triageVerdict(), "still unruled");
        assertEquals(
                refreshed.payload().path("traces_since_onset").asLong(),
                evidence.listByFinding(pid, refreshed.id()).stream()
                        .filter(r -> FindingEvidenceRow.Role.MEMBER.equals(r.role()))
                        .count(),
                "the evidence grows with the spell");
    }

    @Test
    void aPositiveOpensACaseAndTheDossierListsTheFlaggedAnswersAtItsCallSite() {
        String pid = project("gr-case");
        ClassifierRow signal = groundedness(pid);
        FindingRow finding = rise(pid, signal);
        CaseRow opened = open(pid, finding);

        assertEquals(CaseRow.Detector.GROUNDEDNESS, opened.detector());
        assertEquals(CaseRow.SubjectKind.CALL_SITE, opened.subjectKind());
        assertEquals(CALL_SITE, opened.subjectId());
        assertEquals(GroundednessEvidence.MEASURE, opened.metric());
        assertEquals(FindingTitle.of(finding), opened.title());
        assertTrue(
                opened.basis()
                        .startsWith(finding.sampleCount() + " of the "
                                + finding.payload().path("traces_since_onset").asLong() + " traces since"),
                opened.basis());

        // Two flagged answers whose spans are stored at the call site, and one stored at another call site.
        SubstrateV2Fixtures fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        List<FindingEvidenceRow> answers = evidence.listByFinding(pid, finding.id()).stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()) && r.spanId() != null)
                .filter(r -> !doubled(r.traceId())) // one answer per trace, so each span seeds its own trace
                .toList();
        for (int i = 0; i < 3; i++) {
            FindingEvidenceRow a = answers.get(i);
            fx.spanSeed(pid)
                    .traceId(Objects.requireNonNull(a.traceId()))
                    .spanId(Objects.requireNonNull(a.spanId()))
                    .callSiteId(i < 2 ? CALL_SITE : "cs-elsewhere")
                    .at(Instant.parse(finding.onsetAt()).plusSeconds(60))
                    .write();
        }

        String md = triageEngine.dossier(job(pid, finding.id()), finding).get("detections.md");

        assertNotNull(md, "a groundedness rate finding ships its flagged answers");
        assertTrue(md.contains("span `" + answers.get(0).spanId() + "`"), md);
        assertTrue(md.contains("span `" + answers.get(1).spanId() + "`"), md);
        assertTrue(!md.contains("span `" + answers.get(2).spanId() + "`"), "another call site's answer: " + md);
        assertTrue(md.contains("strongest: \"unsupported sentence\""), md);
        assertTrue(md.contains("2 flagged answer(s)"), md);
    }

    @Test
    void aFalseAlarmResolveClearsTheCitedAnswersAndReLearns() {
        String pid = project("gr-false-alarm");
        ClassifierRow signal = groundedness(pid);
        FindingRow finding = rise(pid, signal);
        CaseRow opened = open(pid, finding);
        long cited = evidence.listByFinding(pid, finding.id()).stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()) && r.spanId() != null)
                .count();
        assertNotNull(state(pid).get("baseline_calls"), "the reference was learned before the resolve");

        caseService.resolve(pid, opened.id(), "the documents were stale", "priya@example.com", "false_alarm");

        CaseRow resolved = cases.findById(pid, opened.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, resolved.state());
        assertEquals(CaseRow.Disposition.FALSE_ALARM, resolved.disposition());
        assertEquals(cited, clearedRows(pid), "every flagged answer of the spell, and only those");
        assertTrue(flaggedRows(pid) > cited, "flags from before the spell are not this case's");
        Map<String, Object> state = state(pid);
        assertNull(state.get("baseline_calls"), "the reference is re-learned from here");
        assertNotNull(state.get("reset_at"));
        assertEquals("the documents were stale", state.get("reset_note"));
        assertEquals(
                "{\"disposition\": \"false_alarm\", \"answers_cleared\": " + cited + "}",
                resolvedEventDetail(pid, opened.id()));

        service.refresh(pid, signal, Instant.now());
        List<FindingRow> filed = findings.listByProject(pid, null, null, "groundedness", false, 10);
        assertEquals(1, filed.size(), "the closed hours are fenced off, so nothing is re-filed");
        assertEquals(FindingRow.Status.CLOSED, filed.get(0).status());
    }

    @Test
    void aFalseAlarmClearsEveryCitedAnswerPastTheFirstChunk() {
        String pid = project("gr-clear-chunks");
        ClassifierRow signal = groundedness(pid);
        FindingRow finding = rise(pid, signal);
        CaseRow opened = open(pid, finding);
        long citedByReplay = evidence.listByFinding(pid, finding.id()).stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()) && r.spanId() != null)
                .count();
        // Top the cited answers up to one past a statement's chunk, each with its standing detection row.
        int cited = GroundednessAnswerClearer.CLEAR_CHUNK + 1;
        Instant at = Instant.parse(finding.onsetAt()).plusSeconds(60);
        List<FindingEvidenceRepository.Ref> extra = new ArrayList<>();
        for (int i = 0; i < cited - citedByReplay; i++) {
            String trace = CALL_SITE + "-extra-" + i;
            extra.add(FindingEvidenceRepository.Ref.span(trace, trace + "-a"));
            detection(pid, signal, trace, trace + "-a", at);
        }
        evidence.record(
                pid,
                finding.id(),
                FindingEvidenceRow.Role.WITNESS,
                extra,
                Instant.now().toString());

        assertEquals(cited, clearer.clear(pid, opened.id(), Instant.now().toString()));

        assertEquals(cited, clearedRows(pid), "the answers past the first chunk are cleared too");
    }

    @Test
    void anAbsorbReLearnsTheCallSitesReference() {
        String pid = project("gr-absorb");
        ClassifierRow signal = groundedness(pid);
        FindingRow finding = rise(pid, signal);
        CaseRow opened = open(pid, finding);
        assertNotNull(state(pid).get("baseline_calls"));

        caseService.absorb(pid, opened.id(), "priya@example.com");

        CaseRow absorbed = cases.findById(pid, opened.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, absorbed.state());
        assertEquals(CaseRow.Resolution.ABSORBED, absorbed.resolution());
        Map<String, Object> state = state(pid);
        assertNull(state.get("baseline_calls"), "the new normal is learned from the traffic after the press");
        assertEquals("Absorbed.", state.get("reset_note"));
        assertEquals(0, clearedRows(pid), "an absorb clears no flag");

        service.refresh(pid, signal, Instant.now());
        assertEquals(
                1,
                findings.listByProject(pid, null, null, "groundedness", false, 10)
                        .size(),
                "the absorbed hours are fenced off");
    }

    @Test
    void aCallSiteStillLearningFilesNothing() {
        String pid = project("gr-learning");
        ClassifierRow signal = groundedness(pid);
        seedHours(pid, signal, CALL_SITE, start(), 0, 5, 30, 0.50); // 150 traces, half flagged

        assertTrue(service.refresh(pid, signal, Instant.now()).isEmpty());

        assertTrue(findings.listByProject(pid, null, null, "groundedness", false, 10)
                .isEmpty());
        assertEquals(
                0,
                jdbc.sql("SELECT COUNT(*) FROM groundedness_state WHERE project_id = :pid")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "no reference yet, so no state row");
    }

    // ---- fixtures

    private static Instant start() {
        return Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * A page past the last cited answer still carries how many the finding cites: the count rides on the rows,
     * and a page with no rows reporting zero would tell the reader the finding cites nothing.
     */
    @Test
    void aPagePastTheLastAnswerStillCarriesTheTotal() {
        String pid = project("gr-past-end");
        ClassifierRow signal = groundedness(pid);
        FindingRow finding = rise(pid, signal);
        long cited =
                rateRows.answerPage(pid, signal.id(), finding.id(), null, 1, 0).total();
        assertTrue(cited > 0, "setup: the finding cites answers");

        assertEquals(
                new GroundednessRateRepository.AnswerPage(List.of(), cited),
                rateRows.answerPage(pid, signal.id(), finding.id(), null, 10, (int) cited + 5));
    }

    /** A call site at 5% for 210 traces, then 40% for 180: one spell, one finding. */
    private FindingRow rise(String pid, ClassifierRow signal) {
        Instant start = start();
        seedHours(pid, signal, CALL_SITE, start, 0, 7, 30, 0.05);
        seedHours(pid, signal, CALL_SITE, start, 7, 6, 30, 0.40);
        service.refresh(pid, signal, Instant.now());
        List<FindingRow> filed = findings.listByProject(pid, null, null, "groundedness", false, 10);
        assertEquals(1, filed.size());
        return filed.get(0);
    }

    /** Triage's positive ruling, and the case it opens. */
    private CaseRow open(String pid, FindingRow finding) {
        assertEquals(
                1,
                findings.recordTriage(
                        pid,
                        finding.id(),
                        FindingRow.TriageVerdict.POSITIVE,
                        "The flagged answers state what their documents do not.",
                        null,
                        Instant.now().toString()));
        return caseOpener.ensureCaseFor(pid, finding.id(), null).orElseThrow();
    }

    private static BehaviorTriageJobRow job(String pid, String findingId) {
        return new BehaviorTriageJobRow(
                "job-1",
                pid,
                findingId,
                "claimed",
                null,
                null,
                0,
                null,
                "2026-09-01T00:00:00Z",
                "2026-09-01T00:00:00Z");
    }

    /** Whether a seeded trace had a second flagged answer: the even-numbered flagged ones do. */
    private static boolean doubled(@Nullable String traceId) {
        return traceId != null && Integer.parseInt(traceId.substring(traceId.lastIndexOf('-') + 1)) % 2 == 0;
    }

    private Map<String, Object> state(String pid) {
        return jdbc.sql("SELECT baseline_calls, baseline_failures, reset_at, reset_note FROM groundedness_state"
                        + " WHERE project_id = :pid AND call_site_id = :cs")
                .param("pid", pid)
                .param("cs", CALL_SITE)
                .query()
                .singleRow();
    }

    private long clearedRows(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM groundedness_detection"
                        + " WHERE project_id = :pid AND cleared_at IS NOT NULL")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    private long flaggedRows(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM groundedness_detection WHERE project_id = :pid")
                .param("pid", pid)
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
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.GROUNDEDNESS))
                .project()
                .id();
    }

    private ClassifierRow groundedness(String pid) {
        classifierService.seedBuiltIns(pid);
        return ClassifierRows.byKey(classifiers, pid, "groundedness").orElseThrow();
    }

    /**
     * {@code perHour} traces an hour, each with one scored answer; the first {@code rate} of each hour flagged,
     * and every even-numbered flagged trace flagged on a second answer too.
     */
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
                answer(pid, signal, trace, trace + "-a", callSite, at, flagged);
                if (flagged && c % 2 == 0) answer(pid, signal, trace, trace + "-b", callSite, at.plusMillis(500), true);
            }
        }
    }

    private void answer(
            String pid, ClassifierRow signal, String trace, String span, String callSite, Instant at, boolean flagged) {
        assessments.insert(new Assessment(
                Ids.ulid(),
                pid,
                signal.id(),
                null,
                trace,
                span,
                callSite,
                flagged ? 0.99 : 0.10,
                flagged,
                VERSION,
                at.toString()));
        if (flagged) detection(pid, signal, trace, span, at);
    }

    private void detection(String pid, ClassifierRow signal, String trace, String span, Instant at) {
        jdbc.sql("INSERT INTO groundedness_detection"
                        + " (id, project_id, classifier_id, classifier_key, subject_trace_id, subject_span_id,"
                        + " severity, confidence, evidence, subject_started_at)"
                        + " VALUES (:id, :pid, :cid, 'groundedness', :trace, :span, 'warn', 'high',"
                        + " CAST(:evidence AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("trace", trace)
                .param("span", span)
                .param(
                        "evidence",
                        "{\"head\":\"groundedness\",\"unsupported\":0.99,\"flagged_sentences\":"
                                + "[{\"start\":0,\"end\":20,\"unsupported\":0.99}],\"claim\":\"unsupported sentence\"}")
                .param("at", Timestamp.from(at))
                .update();
    }
}
