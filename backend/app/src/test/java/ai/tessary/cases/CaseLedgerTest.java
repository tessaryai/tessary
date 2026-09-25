// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link CaseLedger#openOrJoin} against real Postgres, so the partial indexes ({@code
 * ux_eval_case_live}, {@code locked_at IS NULL}) and the ISO-text comparisons are exercised rather
 * than simulated.
 *
 * <p>Event-driven now (decision 1): there is no periodic sweep to reconcile, so every test here calls
 * {@code openOrJoin} directly on one finding at a time, exactly as {@link CaseOpener} does inside a
 * ruling's own transaction.
 */
@SpringBootTest
@TestPropertySource(properties = "test.context-group=case-ledger")
class CaseLedgerTest {

    @Autowired
    CaseLedger ledger;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseEventRepository events;

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    private static final String DETECTOR = CaseRow.Detector.CLASSIFIER;

    @Test
    void opensOnceAndRecordsOpened() {
        Project p = project("ledger-open");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");

        CaseRow row = ledger.openOrJoin(p.id(), detection(p, "grader-a", 0.4, onset), null, now());

        assertEquals(CaseRow.State.OPEN, row.state());
        assertEquals(onset.toString(), row.onsetAt());
        assertEquals(List.of(CaseEventRow.Kind.OPENED), kinds(p, row));
        assertEquals(1, row.findingCount());
    }

    @Test
    void aSecondFindingOnTheSameKeyJoinsTheLiveCaseAndRecordsRecurred() {
        Project p = project("ledger-join");
        CaseRow first = ledger.openOrJoin(
                p.id(), detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());

        String secondFinding = freshFinding(p, "grader-a-again");
        CaseRow second = ledger.openOrJoin(
                p.id(),
                withFinding(detection(p, "grader-a", 0.4, Instant.parse("2026-07-05T10:00:00Z")), secondFinding),
                "priya@example.com",
                now());

        assertEquals(first.id(), second.id(), "one live case per key, joined rather than duplicated");
        assertEquals(2, second.findingCount());
        assertEquals(secondFinding, second.latestFindingId());
        assertEquals(List.of(CaseEventRow.Kind.OPENED, CaseEventRow.Kind.RECURRED), kinds(p, second));
    }

    @Test
    void joiningRefreshesTheHeadlineFromTheNewestFindingAndKeepsTheOnset() {
        Project p = project("ledger-headline");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");
        ledger.openOrJoin(p.id(), detection(p, "grader-a", 0.3, onset), null, now());

        String secondFinding = freshFinding(p, "grader-a-refresh");
        CaseDetection second = withFinding(
                new CaseDetection(
                        new CaseKey(DETECTOR, CaseRow.SubjectKind.CLASSIFIER, "grader-a", "pass_rate"),
                        "grader-a",
                        "call-site-a",
                        "unused",
                        "grader-a pass rate fell further",
                        "Sustained drop, now deeper.",
                        0.35,
                        Instant.parse("2026-07-05T10:00:00Z"),
                        0.40,
                        0.95,
                        -0.55),
                secondFinding);
        CaseRow joined = ledger.openOrJoin(p.id(), second, null, now());

        assertEquals("grader-a pass rate fell further", joined.title());
        assertEquals("Sustained drop, now deeper.", joined.basis());
        assertEquals(0.40, joined.currentValue());
        assertEquals(onset.toString(), joined.onsetAt(), "the onset never moves once a case exists");
    }

    @Test
    void severityIsTheHighestAcrossFindingsAndEscalatesOnlyOnANewPeak() {
        Project p = project("ledger-severity-peak");
        ledger.openOrJoin(p.id(), detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());

        // A milder second finding must not pull the case's severity down.
        CaseRow milder = ledger.openOrJoin(
                p.id(),
                withFinding(
                        detection(p, "grader-a", 0.2, Instant.parse("2026-07-05T10:00:00Z")),
                        freshFinding(p, "grader-a-milder")),
                null,
                now());
        assertEquals(0.4, milder.severity(), "severity only ever rises");
        assertEquals(
                List.of(CaseEventRow.Kind.OPENED, CaseEventRow.Kind.RECURRED),
                kinds(p, milder),
                "a milder finding recurs but does not escalate");

        // A genuinely worse one raises the peak and escalates.
        CaseRow worse = ledger.openOrJoin(
                p.id(),
                withFinding(
                        detection(p, "grader-a", 0.7, Instant.parse("2026-07-10T10:00:00Z")),
                        freshFinding(p, "grader-a-worse")),
                null,
                now());
        assertEquals(0.7, worse.severity());
        assertTrue(kinds(p, worse).contains(CaseEventRow.Kind.ESCALATED));
    }

    @Test
    void reapplyingAnAlreadyLinkedFindingRecordsNoRecurred() {
        Project p = project("ledger-reapply");
        CaseDetection detection = detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z"));
        ledger.openOrJoin(p.id(), detection, null, now());

        // The SAME finding, re-applied — ClassifierArming does exactly this on every sweep of a
        // still-firing facet.
        CaseRow reapplied = ledger.openOrJoin(p.id(), detection, null, now());

        assertEquals(1, reapplied.findingCount(), "no second link for a finding already on this case");
        assertEquals(
                List.of(CaseEventRow.Kind.OPENED), kinds(p, reapplied), "a re-application is not a new occurrence");
    }

    @Test
    void aFindingAlreadyOnAnotherCaseIsNotStolen() {
        Project p = project("ledger-not-stolen");
        CaseRow caseA = ledger.openOrJoin(
                p.id(), detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());
        CaseRow caseB = ledger.openOrJoin(
                p.id(), detection(p, "grader-b", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());
        String findingOnB = Objects.requireNonNull(caseB.latestFindingId());

        // A detection under case A's key, but carrying B's already-linked finding — never a real shape
        // ensureCaseFor produces (a finding it hands over is always freshly qualified, case_id NULL),
        // but the ledger must still refuse to relabel it.
        CaseRow result = ledger.openOrJoin(
                p.id(),
                withFinding(detection(p, "grader-a", 0.9, Instant.parse("2026-07-01T10:00:00Z")), findingOnB),
                null,
                now());

        assertEquals(caseA.id(), result.id());
        assertEquals(
                0.4, cases.findById(p.id(), caseA.id()).orElseThrow().severity(), "case A's headline is untouched");
        assertEquals(
                caseB.id(),
                findings.findById(p.id(), findingOnB).orElseThrow().caseId(),
                "the finding stays on the case that actually holds it");
    }

    @Test
    void afterAHumanResolveTheNextPositiveOpensAFreshNumber() {
        Project p = project("ledger-fresh-after-resolve");
        CaseRow first = ledger.openOrJoin(
                p.id(), detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());
        cases.resolve(p.id(), first.id(), CaseRow.Resolution.HUMAN, "shipped a fix", "priya@example.com", now());

        CaseRow second = ledger.openOrJoin(
                p.id(),
                withFinding(
                        detection(p, "grader-a", 0.5, Instant.parse("2026-07-10T10:00:00Z")),
                        freshFinding(p, "grader-a-after-resolve")),
                null,
                now());

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.seq(), second.seq());
        assertEquals(List.of(CaseEventRow.Kind.OPENED), kinds(p, second));
    }

    @Test
    void aLockedCaseIsNeverJoined() {
        Project p = project("ledger-locked-not-joined");
        CaseRow row = ledger.openOrJoin(
                p.id(), detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());
        cases.lock(p.id(), row.id(), now());

        CaseRow second = ledger.openOrJoin(
                p.id(),
                withFinding(
                        detection(p, "grader-a", 0.5, Instant.parse("2026-07-05T10:00:00Z")),
                        freshFinding(p, "grader-a-locked-out")),
                null,
                now());

        assertNotEquals(row.id(), second.id(), "a locked case is invisible to openOrJoin's key lookup");
        assertEquals(
                CaseRow.State.OPEN,
                cases.findById(p.id(), row.id()).orElseThrow().state(),
                "the locked case itself is untouched");
    }

    @Test
    void absorbClosesWithReasonAndEvent() {
        Project p = project("ledger-absorb");
        CaseRow row = ledger.openOrJoin(
                p.id(), detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z")), null, now());
        String findingId = Objects.requireNonNull(row.latestFindingId());

        ledger.absorb(p.id(), row.id(), "priya@example.com", now());

        CaseRow closed = cases.findById(p.id(), row.id()).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, closed.state());
        assertEquals(CaseRow.Resolution.ABSORBED, closed.resolution());
        assertTrue(kinds(p, closed).contains(CaseEventRow.Kind.ABSORBED));
        assertEquals(
                FindingRow.Status.CLOSED,
                findings.findById(p.id(), findingId).orElseThrow().status(),
                "absorbing closes what the case holds");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    private static Instant now() {
        return Instant.now();
    }

    private List<String> kinds(Project p, CaseRow row) {
        return events.listByCase(p.id(), row.id()).stream()
                .map(CaseEventRow::kind)
                .toList();
    }

    private CaseDetection detection(Project p, String subjectId, double severity, Instant onset) {
        String findingId = freshFinding(p, subjectId + "-" + Ids.ulid());
        return new CaseDetection(
                new CaseKey(DETECTOR, CaseRow.SubjectKind.CLASSIFIER, subjectId, "pass_rate"),
                subjectId,
                "call-site-a",
                findingId,
                subjectId + " pass rate fell 40 pts",
                "Sustained drop against its own baseline.",
                severity,
                onset,
                0.55,
                0.95,
                -0.4);
    }

    /** The finding shape these fixtures file: a classifier's armed window, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** A finding of this project's own, for a test that needs a SECOND one under the same case. */
    private String freshFinding(Project p, String cause) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        p.id(),
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + cause,
                        "cause-" + cause,
                        1,
                        "call-site-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }

    private static CaseDetection withFinding(CaseDetection d, String findingId) {
        return new CaseDetection(
                d.key(),
                d.subjectLabel(),
                d.callSiteId(),
                findingId,
                d.title(),
                d.basis(),
                d.severity(),
                d.onsetAt(),
                d.currentValue(),
                d.baselineValue(),
                d.delta());
    }
}
