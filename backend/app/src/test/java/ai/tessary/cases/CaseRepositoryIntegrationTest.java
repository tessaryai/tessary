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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The two partial indexes the baseline changeset defines on {@code eval_case}, the display-number
 * allocation that runs against them, and the filtered keyset page that reads them. All three are invisible
 * to a unit test: {@code ux_eval_case_live} is a filtered unique index whose {@code WHERE state &lt;&gt;
 * 'resolved'} predicate is the whole point, the seq allocator is a {@code MAX(seq)+1} whose failure mode only
 * appears when a row it did not expect is already there, and a keyset page is a claim about what Postgres
 * returns for a row-constructor comparison against a real ordering — mocking the query would only assert
 * that the string was assembled.
 */
@SpringBootTest
class CaseRepositoryIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("tessary.cases.heartbeat-ms", () -> "3600000");
    }

    @Autowired
    CaseRepository cases;

    @Autowired
    CaseLedger ledger;

    @Autowired
    FindingRepository findings;

    /** Memoized per (project, subject): every case needs a finding, and a fresh one per pass would
     *  make each refresh look like a different cause. */
    private final Map<String, String> findingIds = new HashMap<>();

    @Autowired
    TenantService tenants;

    @Test
    void oneLiveCasePerSubjectKey() {
        Project p = project("repo-live");
        assertTrue(cases.open(p.id(), detection(p, "grader-a"), Instant.now()).isPresent());

        assertTrue(
                cases.open(p.id(), detection(p, "grader-a"), Instant.now()).isEmpty(),
                "a second live case on one key is what ux_eval_case_live exists to prevent");
    }

    @Test
    void mutedCountsAsLiveAndStillHoldsTheKey() {
        Project p = project("repo-muted");
        CaseRow row =
                cases.open(p.id(), detection(p, "grader-a"), Instant.now()).orElseThrow();
        cases.mute(p.id(), row.id(), "priya@example.com", Instant.now());

        assertTrue(
                cases.open(p.id(), detection(p, "grader-a"), Instant.now()).isEmpty(),
                "a muted case is still THE case for its spell — a continuing detection updates it");
    }

    @Test
    void resolvingFreesTheKeyForAFreshCase() {
        Project p = project("repo-resolved-frees");
        CaseRow first =
                cases.open(p.id(), detection(p, "grader-a"), Instant.now()).orElseThrow();
        cases.resolve(p.id(), first.id(), CaseRow.Resolution.HUMAN, "done", "priya@example.com", Instant.now());

        CaseRow second =
                cases.open(p.id(), detection(p, "grader-a"), Instant.now()).orElseThrow();
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void displayNumbersCountUpWithinAProjectAndRestartAcrossProjects() {
        Project a = project("repo-seq-a");
        Project b = project("repo-seq-b");

        assertEquals(
                1,
                cases.open(a.id(), detection(a, "grader-a"), Instant.now())
                        .orElseThrow()
                        .seq());
        assertEquals(
                2,
                cases.open(a.id(), detection(a, "grader-b"), Instant.now())
                        .orElseThrow()
                        .seq());
        assertEquals(
                1,
                cases.open(b.id(), detection(b, "grader-a"), Instant.now())
                        .orElseThrow()
                        .seq());
    }

    /**
     * The regression for the silent-drop defect, exercised the only way it actually arises.
     *
     * <p>{@code MAX(seq)+1} cannot collide with itself inside one transaction — it collides when two
     * transactions read the same {@code MAX} before either commits, which is precisely two backends
     * sweeping one project. The loser's insert was swallowed by a bare {@code ON CONFLICT DO NOTHING}
     * and its detection vanished: no row, no event, no log. Both cases must survive, on distinct
     * numbers.
     */
    @Test
    void concurrentReconcilesPlaceEveryCaseOnItsOwnNumber() throws Exception {
        Project p = project("repo-seq-race");
        int backends = 4;
        int subjects = 6;
        // Every backend reports the SAME live set — which is what N replicas of CaseWorker actually do,
        // and why the opens race while nobody's sweep closes anybody else's case.
        List<CaseDetection> firing = IntStream.range(0, subjects)
                .mapToObj(i -> detection(p, "grader-" + i))
                .toList();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(backends);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < backends; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // Through the ledger, not the repository: apply() is the transactional unit that
                    // takes the advisory lock, and the lock is what makes this deterministic.
                    ledger.apply(p.id(), CaseRow.Detector.BEHAVIOR_DRIFT, firing, Instant.now());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<CaseRow> live = cases.listLive(p.id());
        assertEquals(subjects, live.size(), "every detection must have produced exactly one case");
        assertEquals(
                subjects,
                live.stream().map(CaseRow::seq).distinct().count(),
                "and each must hold its own display number");
    }

    // ---- the paged, filtered read ------------------------------------------------------------

    /**
     * Each filter narrows on its own column and they compose. Cheap to write, and the bug it catches is the
     * one a hand-built {@code WHERE} clause always eventually has: a filter that is accepted, appended to the
     * SQL, and never bound — which returns MORE rows than asked for and reads like a working query.
     */
    @Test
    void pageFiltersOnStateDetectorAndCallSiteTogether() {
        Project p = project("repo-page-filters");
        open(p, "drift-a", CaseRow.Detector.BEHAVIOR_DRIFT, "cs-1", 0.5, at("2026-08-10T00:00:00Z"));
        open(p, "drift-b", CaseRow.Detector.BEHAVIOR_DRIFT, "cs-2", 0.5, at("2026-08-10T00:00:00Z"));
        open(p, "tool-a", CaseRow.Detector.TOOL_ERROR, "cs-1", 0.5, at("2026-08-10T00:00:00Z"));
        CaseRow muted = open(p, "drift-c", CaseRow.Detector.BEHAVIOR_DRIFT, "cs-1", 0.5, at("2026-08-10T00:00:00Z"));
        cases.mute(p.id(), muted.id(), "priya@example.com", Instant.now());

        assertEquals(3, ids(page(p, CaseRow.State.OPEN, null, null)).size(), "the muted case is not open");
        assertEquals(
                List.of(muted.id()),
                ids(page(p, CaseRow.State.MUTED, null, null)),
                "muted is a state you can ask for, not a hidden bucket");
        assertEquals(
                2,
                ids(page(p, CaseRow.State.OPEN, CaseRow.Detector.BEHAVIOR_DRIFT, null))
                        .size());
        assertEquals(
                1,
                ids(page(p, CaseRow.State.OPEN, CaseRow.Detector.BEHAVIOR_DRIFT, "cs-1"))
                        .size(),
                "detector AND call site, not detector OR call site");
        assertEquals(4, ids(page(p, null, null, null)).size(), "a null state is every state, live and closed alike");
    }

    /**
     * Walking the live page with the cursor visits every case exactly once, worst first.
     *
     * <p>The seeded set is deliberately degenerate: two cases share a severity, and two of THOSE share an
     * {@code opened_at}. That is what makes the {@code id} tiebreaker load-bearing — a keyset whose tail is
     * not unique either skips a row (it lands past a tie) or serves one twice (it lands before it), and both
     * look like a working page until someone counts.
     */
    @Test
    void livePageWalksWorstFirstAndTheKeysetNeitherSkipsNorRepeats() {
        Project p = project("repo-page-keyset");
        Instant sameMoment = at("2026-08-10T00:00:00Z");
        CaseRow worst = open(p, "s-worst", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.9, at("2026-08-09T00:00:00Z"));
        CaseRow tieNewer = open(p, "s-tie-newer", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.5, sameMoment);
        CaseRow tieSame = open(p, "s-tie-same", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.5, sameMoment);
        CaseRow older = open(p, "s-older", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.5, at("2026-08-01T00:00:00Z"));
        CaseRow mildest = open(p, "s-mildest", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.1, sameMoment);

        List<String> walked = new ArrayList<>();
        CaseRepository.PageKey key = null;
        for (int guard = 0; guard < 10; guard++) {
            List<CaseRow> rows = cases.page(p.id(), CaseRow.State.OPEN, null, null, 2, key);
            if (rows.isEmpty()) break;
            rows.forEach(r -> walked.add(r.id()));
            CaseRow last = rows.get(rows.size() - 1);
            key = new CaseRepository.PageKey(last.severity(), last.openedAt(), last.id());
        }

        assertEquals(5, walked.size(), "every case exactly once: " + walked);
        assertEquals(worst.id(), walked.get(0), "highest severity first, regardless of age");
        assertEquals(mildest.id(), walked.get(4), "lowest severity last, regardless of age");
        assertEquals(
                List.of(worst.id(), tieNewer.id(), tieSame.id(), older.id(), mildest.id())
                        .size(),
                walked.stream().distinct().count(),
                "no case served twice");
        assertTrue(
                walked.indexOf(older.id()) > walked.indexOf(tieSame.id()),
                "inside one severity, the newer spell outranks the older one");
    }

    /** Closed cases are a history, so they rank by when they closed — severity says nothing about recency. */
    @Test
    void resolvedPageWalksNewestClosureFirst() {
        Project p = project("repo-page-resolved");
        CaseRow first = open(p, "r-1", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.9, at("2026-08-01T00:00:00Z"));
        CaseRow second = open(p, "r-2", CaseRow.Detector.BEHAVIOR_DRIFT, null, 0.1, at("2026-08-02T00:00:00Z"));
        cases.resolve(p.id(), first.id(), CaseRow.Resolution.RECOVERED, null, null, at("2026-08-05T00:00:00Z"));
        cases.resolve(p.id(), second.id(), CaseRow.Resolution.RECOVERED, null, null, at("2026-08-06T00:00:00Z"));

        List<CaseRow> firstPage = cases.page(p.id(), CaseRow.State.RESOLVED, null, null, 1, null);
        assertEquals(
                List.of(second.id()),
                ids(firstPage),
                "the most recent closure leads, even though it is the milder case");

        CaseRow last = firstPage.get(0);
        // The resolved key carries no severity: this order does not rank by it, and passing one would be
        // asserting a column the query never reads.
        List<CaseRow> nextPage = cases.page(
                p.id(),
                CaseRow.State.RESOLVED,
                null,
                null,
                1,
                new CaseRepository.PageKey(null, Objects.requireNonNull(last.resolvedAt()), last.id()));
        assertEquals(List.of(first.id()), ids(nextPage));
    }

    private List<CaseRow> page(Project p, @Nullable String state, @Nullable String detector, @Nullable String cs) {
        return cases.page(p.id(), state, detector, cs, 50, null);
    }

    private static List<String> ids(List<CaseRow> rows) {
        return rows.stream().map(CaseRow::id).toList();
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private CaseRow open(
            Project p,
            String subjectId,
            String detector,
            @Nullable String callSiteId,
            double severity,
            Instant openedAt) {
        CaseDetection detection = new CaseDetection(
                new CaseKey(detector, CaseRow.SubjectKind.CLASSIFIER, subjectId, "pass_rate"),
                subjectId,
                callSiteId,
                findingIds.computeIfAbsent(p.id() + ":" + subjectId, k -> finding(p, subjectId)),
                subjectId + " fell",
                "Sustained drop against its own baseline.",
                severity,
                Instant.parse("2026-07-01T10:00:00Z"),
                0.55,
                0.95,
                -0.4);
        return cases.open(p.id(), detection, openedAt).orElseThrow();
    }

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    private CaseDetection detection(Project p, String subjectId) {
        return new CaseDetection(
                new CaseKey(CaseRow.Detector.BEHAVIOR_DRIFT, CaseRow.SubjectKind.CLASSIFIER, subjectId, "pass_rate"),
                subjectId,
                null,
                findingIds.computeIfAbsent(p.id() + ":" + subjectId, k -> finding(p, subjectId)),
                subjectId + " fell",
                "Sustained drop against its own baseline.",
                0.4,
                Instant.parse("2026-07-01T10:00:00Z"),
                0.55,
                0.95,
                -0.4);
    }

    /** Every case points at a finding — the forward CHECK on {@code eval_case} requires one. */
    private String finding(Project p, String subjectId) {
        return findings.recordFiring(
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
                .findingId();
    }
}
