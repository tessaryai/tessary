// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The case lifecycle state machine, against real Postgres so the partial indexes and the ISO-text
 * comparisons are exercised rather than simulated.
 *
 * <p>The behaviour under test is {@link CaseLedger#apply}'s contract: a detector hands over everything
 * it currently believes, and the ledger makes the table agree — opening what is new, refreshing what
 * continues, closing what dropped out, and <b>declining to reopen what a human closed</b>. That last
 * one is the reason this class exists: a detection whose onset advances every pass reopens a resolved
 * case on the next worker tick, which is how "resolve" comes to look broken.
 */
@SpringBootTest
class CaseLedgerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        // Park CaseWorker's sweep. It reconciles every active project from the REAL sources, which
        // report nothing for a fixture project — so a tick landing mid-test would auto-resolve the
        // cases these assertions just opened.
        r.add("tessary.cases.heartbeat-ms", () -> "3600000");
    }

    @Autowired
    CaseLedger ledger;

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseEventRepository events;

    @Autowired
    CaseService service;

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    private static final String DETECTOR = CaseRow.Detector.BEHAVIOR_DRIFT;

    // ---- open / refresh / recover ------------------------------------------------------------

    @Test
    void opensACaseAndRecordsExactlyOneOpenedEvent() {
        Project p = project("ledger-open");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");

        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, onset)), now());

        CaseRow row = live(p, "grader-a").orElseThrow();
        assertEquals(CaseRow.State.OPEN, row.state());
        assertEquals(onset.toString(), row.onsetAt());
        assertEquals(List.of(CaseEventRow.Kind.OPENED), kinds(p, row));
    }

    @Test
    void reapplyingTheSameDetectionRefreshesRatherThanOpeningASecondCase() {
        Project p = project("ledger-refresh");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");

        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, onset)), now());
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.42, onset)), now());

        assertEquals(1, cases.listLive(p.id()).size());
        CaseRow row = live(p, "grader-a").orElseThrow();
        assertEquals(0.42, row.severity(), 1e-9);
        // 0.02 is under ESCALATION_STEP — the trail must not narrate a heartbeat.
        assertEquals(List.of(CaseEventRow.Kind.OPENED), kinds(p, row));
    }

    @Test
    void aMaterialWorseningAppendsAnEscalatedEvent() {
        Project p = project("ledger-escalate");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");

        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.2, onset)), now());
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.5, onset)), now());

        CaseRow row = live(p, "grader-a").orElseThrow();
        assertEquals(List.of(CaseEventRow.Kind.OPENED, CaseEventRow.Kind.ESCALATED), kinds(p, row));
    }

    @Test
    void aDetectionDroppingOutOfTheLiveSetResolvesItsCaseAsRecovered() {
        Project p = project("ledger-recover");
        ledger.apply(
                p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z"))), now());
        String id = live(p, "grader-a").orElseThrow().id();

        ledger.apply(p.id(), DETECTOR, List.of(), now());

        CaseRow row = cases.findById(p.id(), id).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, row.state());
        assertEquals(CaseRow.Resolution.RECOVERED, row.resolution());
        assertTrue(kinds(p, row).contains(CaseEventRow.Kind.RECOVERED));
    }

    // ---- the reopen guard (the defect this fix exists for) -----------------------------------

    @Test
    void aHumanResolveSurvivesTheDetectionStillFiringOnTheSameSpell() {
        Project p = project("ledger-resolve-sticks");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, onset)), now());
        String id = live(p, "grader-a").orElseThrow().id();

        service.resolve(p.id(), id, "known — fix shipped, window hasn't caught up", "priya@example.com");

        // The detector has not stopped: the very same spell is still firing on the next pass.
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, onset)), now());

        CaseRow row = cases.findById(p.id(), id).orElseThrow();
        assertEquals(CaseRow.State.RESOLVED, row.state(), "a still-firing spell must not undo a human's close");
        assertEquals(CaseRow.Resolution.HUMAN, row.resolution());
        assertTrue(live(p, "grader-a").isEmpty(), "and it must not open a second case beside it");
    }

    @Test
    void anUnbracketedDetectionNeverClaimsANewSpell() {
        Project p = project("ledger-null-onset");
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, null)), now());
        String id = live(p, "grader-a").orElseThrow().id();
        service.resolve(p.id(), id, "handled", "priya@example.com");

        // A source with no onset would previously have stamped `now` here, which always looks newer.
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, null)), now());

        assertEquals(
                CaseRow.State.RESOLVED, cases.findById(p.id(), id).orElseThrow().state());
    }

    @Test
    void aGenuinelyNewSpellReopensTheSameCaseOnTheSameNumber() {
        Project p = project("ledger-reopen");
        ledger.apply(
                p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z"))), now());
        CaseRow opened = live(p, "grader-a").orElseThrow();
        service.resolve(p.id(), opened.id(), "traffic mix", "priya@example.com");

        ledger.apply(
                p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.6, Instant.parse("2026-07-04T09:00:00Z"))), now());

        CaseRow row = cases.findById(p.id(), opened.id()).orElseThrow();
        assertEquals(CaseRow.State.OPEN, row.state());
        assertEquals(opened.seq(), row.seq(), "a re-fire inside the window continues the same story");
        assertEquals("2026-07-04T09:00:00Z", row.onsetAt());
        // Reopening must clear the close, or the page shows "resolved by Priya" on a firing case.
        assertEquals(null, row.resolution());
        assertEquals(null, row.resolvedBy());
        assertTrue(kinds(p, row).contains(CaseEventRow.Kind.REOPENED));
    }

    @Test
    void aSpellPastTheReopenWindowEarnsAFreshCaseAndANewNumber() {
        Project p = project("ledger-fresh");
        Instant longAgo = Instant.now().minus(Duration.ofDays(40));
        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.4, longAgo)), longAgo);
        CaseRow first = live(p, "grader-a").orElseThrow();
        // Resolve as of the old timestamp, so the closure itself falls outside REOPEN_WINDOW.
        cases.resolve(p.id(), first.id(), CaseRow.Resolution.HUMAN, "old", "priya@example.com", longAgo);

        ledger.apply(p.id(), DETECTOR, List.of(detection(p, "grader-a", 0.5, Instant.now())), now());

        CaseRow second = live(p, "grader-a").orElseThrow();
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.seq(), second.seq());
    }

    // ---- the finding behind the case ---------------------------------------------------------

    /**
     * Every case carries the id of the finding it is about, on open and on every refresh after it.
     *
     * <p>The rewrite-on-refresh is not incidental. A tool-error spell that flips direction closes one
     * finding row and opens another WITHIN one spell, and the case must follow it: a case still pointing
     * at the finding that stopped being bumped shows a ruling nobody made about the shift on the page,
     * and the evidence zone renders the wrong traces. Case identity is the {@link CaseKey}; the finding
     * is what the case is currently about, and those are two different questions.
     */
    @Test
    void everyCaseCarriesItsFindingAndFollowsItAcrossARefresh() {
        Project p = project("ledger-finding-id");
        Instant onset = Instant.parse("2026-07-01T10:00:00Z");
        CaseDetection first = detection(p, "grader-a", 0.4, onset);

        ledger.apply(p.id(), DETECTOR, List.of(first), now());
        CaseRow opened = live(p, "grader-a").orElseThrow();
        assertEquals(first.findingId(), opened.findingId());

        String second = freshFinding(p, "grader-a-flipped");
        ledger.apply(p.id(), DETECTOR, List.of(withFinding(first, second, 0.45)), now());

        CaseRow refreshed = live(p, "grader-a").orElseThrow();
        assertEquals(opened.id(), refreshed.id(), "the same spell on the same key stays one case");
        assertEquals(second, refreshed.findingId(), "a case points at the finding it is currently about");
    }

    /** A reopen inside the window re-stamps the finding too — the new spell has its own row. */
    @Test
    void reopeningRepointsTheCaseAtTheSpellsOwnFinding() {
        Project p = project("ledger-finding-reopen");
        CaseDetection first = detection(p, "grader-a", 0.4, Instant.parse("2026-07-01T10:00:00Z"));
        ledger.apply(p.id(), DETECTOR, List.of(first), now());
        CaseRow opened = live(p, "grader-a").orElseThrow();
        service.resolve(p.id(), opened.id(), "traffic mix", "priya@example.com");

        String second = freshFinding(p, "grader-a-second-spell");
        CaseDetection reFire = new CaseDetection(
                first.key(),
                first.subjectLabel(),
                first.callSiteId(),
                second,
                first.title(),
                first.basis(),
                0.6,
                Instant.parse("2026-07-04T09:00:00Z"),
                first.currentValue(),
                first.baselineValue(),
                first.delta());
        ledger.apply(p.id(), DETECTOR, List.of(reFire), now());

        CaseRow row = cases.findById(p.id(), opened.id()).orElseThrow();
        assertEquals(CaseRow.State.OPEN, row.state());
        assertEquals(second, row.findingId());
    }

    /**
     * A case from a detector that no longer has a source is left exactly where it is.
     *
     * <p>Reconciliation is per detector — the ledger is handed one detector's whole live set at a time —
     * so a case opened by a source that has since been deleted is not in anybody's live set. The failure
     * this pins is the tempting one: a global "close everything that nobody is firing on" would resolve
     * those rows and tell an operator that findings from a retired detector recovered, which is a claim
     * nothing made.
     */
    @Test
    void aCaseFromARetiredDetectorSurvivesEveryOtherDetectorsPass() {
        Project p = project("ledger-orphan-detector");
        ledger.apply(
                p.id(),
                CaseRow.Detector.TOOL_ERROR,
                List.of(new CaseDetection(
                        new CaseKey(
                                CaseRow.Detector.TOOL_ERROR, CaseRow.SubjectKind.TOOL, "tool:lookup", "failure_rate"),
                        "lookup",
                        null,
                        freshFinding(p, "tool-lookup"),
                        "lookup is failing more often",
                        "Sustained change against its own past failure rate.",
                        0.5,
                        Instant.parse("2026-07-01T10:00:00Z"),
                        0.2,
                        0.05,
                        0.15)),
                now());
        CaseRow orphan = cases.findLive(
                        p.id(),
                        new CaseKey(
                                CaseRow.Detector.TOOL_ERROR, CaseRow.SubjectKind.TOOL, "tool:lookup", "failure_rate"))
                .orElseThrow();

        // Another detector reconciles an empty live set — everything IT was firing on has recovered.
        ledger.apply(p.id(), DETECTOR, List.of(), now());

        assertEquals(
                CaseRow.State.OPEN,
                cases.findById(p.id(), orphan.id()).orElseThrow().state(),
                "one detector's all-clear must never close another detector's case");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    private static Instant now() {
        return Instant.now();
    }

    private Optional<CaseRow> live(Project p, String subjectId) {
        return cases.findLive(p.id(), new CaseKey(DETECTOR, CaseRow.SubjectKind.CLASSIFIER, subjectId, "pass_rate"));
    }

    private List<String> kinds(Project p, CaseRow row) {
        return events.listByCase(p.id(), row.id()).stream()
                .map(CaseEventRow::kind)
                .toList();
    }

    /** The finding a case is about, memoized per (project, subject): the forward CHECK requires one,
     *  and a fresh finding on every pass would make each refresh look like a different cause. */
    private final Map<String, String> findingIds = new HashMap<>();

    private CaseDetection detection(Project p, String subjectId, double severity, @Nullable Instant onset) {
        String findingId = findingIds.computeIfAbsent(
                p.id() + ":" + subjectId,
                k -> findings.recordFiring(
                                Ids.ulid(),
                                p.id(),
                                "profile-" + subjectId,
                                FindingRow.Cause.NOVELTY,
                                "cause-" + subjectId,
                                FindingRow.GLOBAL_WORKFLOW,
                                1,
                                null,
                                null,
                                "call-site-a",
                                Instant.now().toString())
                        .findingId());
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
    /** A finding of this project's own, for a test that needs a SECOND one under the same case. */
    private String freshFinding(Project p, String cause) {
        return findings.recordFiring(
                        Ids.ulid(),
                        p.id(),
                        "profile-" + cause,
                        FindingRow.Cause.NOVELTY,
                        "cause-" + cause,
                        FindingRow.GLOBAL_WORKFLOW,
                        1,
                        null,
                        null,
                        "call-site-a",
                        Instant.now().toString())
                .findingId();
    }

    private static CaseDetection withFinding(CaseDetection d, String findingId, double severity) {
        return new CaseDetection(
                d.key(),
                d.subjectLabel(),
                d.callSiteId(),
                findingId,
                d.title(),
                d.basis(),
                severity,
                d.onsetAt(),
                d.currentValue(),
                d.baselineValue(),
                d.delta());
    }
}
