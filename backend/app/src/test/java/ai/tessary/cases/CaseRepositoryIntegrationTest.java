// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The two partial indexes on {@code eval_case}, the display-number allocation against them, and the filtered keyset
 * page, none visible to a unit test: {@code ux_eval_case_live}'s {@code WHERE state &lt;&gt; 'resolved'} predicate is
 * the point, the {@code MAX(seq)+1} allocator fails only when an unexpected row exists, and a keyset page is a claim
 * about Postgres row-constructor ordering.
 */
@SpringBootTest
@TestPropertySource(properties = "test.context-group=case-repository")
class CaseRepositoryIntegrationTest {

    @Autowired
    CaseRepository cases;

    @Autowired
    FindingRepository findings;

    /** Memoized per (project, subject): a fresh finding per pass would make each refresh look like a new cause. */
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

    // No concurrent-seq test: the reconciler whose batch opens needed CaseLedger's lock is gone (decision 1). A
    // residual race, two causes ruled at once onto one CaseKey, fails the ruling's transaction for a retry rather
    // than dropping silently.

    /**
     * Each filter narrows on its own column and they compose. Catches a filter appended to the SQL but never bound.
     */
    @Test
    void pageFiltersOnStateDetectorAndCallSiteTogether() {
        Project p = project("repo-page-filters");
        open(p, "drift-a", CaseRow.Detector.CLASSIFIER, "cs-1", 0.5, at("2026-08-10T00:00:00Z"));
        open(p, "drift-b", CaseRow.Detector.CLASSIFIER, "cs-2", 0.5, at("2026-08-10T00:00:00Z"));
        open(p, "tool-a", CaseRow.Detector.TOOL_ERROR, "cs-1", 0.5, at("2026-08-10T00:00:00Z"));
        CaseRow muted = open(p, "drift-c", CaseRow.Detector.CLASSIFIER, "cs-1", 0.5, at("2026-08-10T00:00:00Z"));
        cases.mute(p.id(), muted.id(), "priya@example.com", Instant.now());

        assertEquals(3, ids(page(p, CaseRow.State.OPEN, null, null)).size(), "the muted case is not open");
        assertEquals(
                List.of(muted.id()),
                ids(page(p, CaseRow.State.MUTED, null, null)),
                "muted is a state you can ask for, not a hidden bucket");
        assertEquals(
                2,
                ids(page(p, CaseRow.State.OPEN, CaseRow.Detector.CLASSIFIER, null))
                        .size());
        assertEquals(
                1,
                ids(page(p, CaseRow.State.OPEN, CaseRow.Detector.CLASSIFIER, "cs-1"))
                        .size(),
                "detector AND call site, not detector OR call site");
        assertEquals(4, ids(page(p, null, null, null)).size(), "a null state is every state, live and closed alike");
    }

    /**
     * Walking the live page visits every case once, worst first. Two cases share a severity and two of those an
     * {@code opened_at}, so the {@code id} tiebreaker is load-bearing: a non-unique tail skips or repeats a row.
     */
    @Test
    void livePageWalksWorstFirstAndTheKeysetNeitherSkipsNorRepeats() {
        Project p = project("repo-page-keyset");
        Instant sameMoment = at("2026-08-10T00:00:00Z");
        CaseRow worst = open(p, "s-worst", CaseRow.Detector.CLASSIFIER, null, 0.9, at("2026-08-09T00:00:00Z"));
        CaseRow tieNewer = open(p, "s-tie-newer", CaseRow.Detector.CLASSIFIER, null, 0.5, sameMoment);
        CaseRow tieSame = open(p, "s-tie-same", CaseRow.Detector.CLASSIFIER, null, 0.5, sameMoment);
        CaseRow older = open(p, "s-older", CaseRow.Detector.CLASSIFIER, null, 0.5, at("2026-08-01T00:00:00Z"));
        CaseRow mildest = open(p, "s-mildest", CaseRow.Detector.CLASSIFIER, null, 0.1, sameMoment);

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

    /** Closed cases rank by when they closed. */
    @Test
    void resolvedPageWalksNewestClosureFirst() {
        Project p = project("repo-page-resolved");
        CaseRow first = open(p, "r-1", CaseRow.Detector.CLASSIFIER, null, 0.9, at("2026-08-01T00:00:00Z"));
        CaseRow second = open(p, "r-2", CaseRow.Detector.CLASSIFIER, null, 0.1, at("2026-08-02T00:00:00Z"));
        cases.resolve(p.id(), first.id(), CaseRow.Resolution.ABSORBED, null, null, at("2026-08-05T00:00:00Z"));
        cases.resolve(p.id(), second.id(), CaseRow.Resolution.ABSORBED, null, null, at("2026-08-06T00:00:00Z"));

        List<CaseRow> firstPage = cases.page(p.id(), CaseRow.State.RESOLVED, null, null, 1, null);
        assertEquals(
                List.of(second.id()),
                ids(firstPage),
                "the most recent closure leads, even though it is the milder case");

        CaseRow last = firstPage.get(0);
        // The resolved key carries no severity: this order never reads it.
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
                new CaseKey(CaseRow.Detector.CLASSIFIER, CaseRow.SubjectKind.CLASSIFIER, subjectId, "pass_rate"),
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

    /** The finding shape these fixtures file: a classifier's armed window, which rules by the verb alone. */
    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    /** The forward CHECK on {@code eval_case} requires a finding. */
    private String finding(Project p, String subjectId) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        p.id(),
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + subjectId,
                        "cause-" + subjectId,
                        1,
                        "call-site-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }
}
