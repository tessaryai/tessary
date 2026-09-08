// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.open.errors.ClassifierError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.storage.AnnotationRepository;
import ai.tessary.evals.storage.AnnotationRow;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.CapabilityFixture;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The invariants the shared {@code finding} table introduces, against the real schema — every one of
 * them is a database constraint or a predicate rather than Java, so a unit test would assert the mock.
 *
 * <p>What is pinned here is the set of rules that make ONE table safe for four classifiers: the live
 * uniqueness arbiter and its {@code blocked} arm, the bounded append-only evidence set, the case's
 * mandatory pointer at a finding, and the correction anchor that outlives the verdict TTL.
 */
@SpringBootTest
class SharedFindingTableIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    FindingService behaviorDrift;

    @Autowired
    AnnotationRepository annotations;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    JdbcClient jdbc;

    /**
     * A detector records the POPULATION it measured, so the writer truncates nothing and the finding
     * carries the size of what it wrote. The retention pin widens with it, by decision: a claim about a
     * population cannot be audited against a sample whose selection rule nobody stated.
     */
    @Test
    @DisplayName("evidence is uncapped and counted, and a repeat reference is a no-op rather than a duplicate")
    void evidenceIsUncappedAndAppendOnly() {
        Project p = project("finding-evidence-population");
        String findingId = firing(p, "gram-population");
        String now = Instant.now().toString();

        int population = 70;
        List<FindingEvidenceRepository.Ref> many = new ArrayList<>();
        for (int i = 0; i < population; i++) {
            many.add(FindingEvidenceRepository.Ref.trace("trace-" + i));
        }
        int written = evidence.record(p.id(), findingId, FindingEvidenceRow.Role.MEMBER, many, now);
        assertEquals(population, written, "the writer records every ref the classifier handed it");

        // Re-offering the same window must write nothing, which is what makes a sweep that re-reads its
        // own page idempotent — and it must not double the recorded count either.
        assertEquals(
                0,
                evidence.record(p.id(), findingId, FindingEvidenceRow.Role.MEMBER, many, now),
                "a repeated reference is absorbed by ux_finding_evidence_ref");

        assertEquals(1, evidence.recordExemplarTrace(p.id(), findingId, "trace-exemplar", now));
        assertTrue(evidence.exemplarTraceId(p.id(), findingId).isPresent());
        assertEquals(population + 1, evidence.listByFinding(p.id(), findingId).size());

        // The cost of the claim, readable off the finding without a count(*).
        FindingRow finding = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(population, finding.evidenceCount(FindingEvidenceRow.Role.MEMBER));
        assertEquals(1, finding.evidenceCount(FindingEvidenceRow.Role.EXEMPLAR));
        assertEquals(0, finding.evidenceCount(FindingEvidenceRow.Role.BASELINE));
    }

    /**
     * The read side of the population: {@code get_finding_evidence} pages this repository, and its keyset
     * is SQL — a row comparison over {@code (role, rank, id)} with a cast on each slot — so a unit test
     * against a mock would prove nothing about the one thing that can be wrong here.
     *
     * <p>The property that matters is completeness. A population is only auditable if walking it returns
     * every row exactly once, so the walk below is asserted against the whole set rather than against the
     * first page, and it crosses a role boundary on the way (the order is role-major).
     */
    @Test
    @DisplayName("the evidence page walks the whole population once, and the counts report every role")
    void evidencePagesInStableOrderAndCountsEveryRole() {
        Project p = project("finding-evidence-paging");
        String findingId = firing(p, "gram-paging");
        String now = Instant.now().toString();

        List<FindingEvidenceRepository.Ref> members = new ArrayList<>();
        for (int i = 0; i < 5; i++) members.add(FindingEvidenceRepository.Ref.span("trace-" + i, "span-" + i));
        evidence.record(p.id(), findingId, FindingEvidenceRow.Role.MEMBER, members, now);
        evidence.record(
                p.id(),
                findingId,
                FindingEvidenceRow.Role.BASELINE,
                List.of(
                        FindingEvidenceRepository.Ref.trace("ref-0"),
                        FindingEvidenceRepository.Ref.trace("ref-1"),
                        FindingEvidenceRepository.Ref.trace("ref-2")),
                now);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            FindingEvidenceRepository.Page page = evidence.page(p.id(), findingId, null, 3, cursor);
            for (FindingEvidenceRow row : page.rows()) walked.add(row.role() + ':' + row.id());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(8, walked.size(), "the walk returned " + walked.size() + " of 8 refs: " + walked);
        assertEquals(
                8,
                walked.stream().distinct().count(),
                "a keyset that re-emits a row is as wrong as one that skips it: " + walked);
        assertEquals(
                evidence.listByFinding(p.id(), findingId).stream()
                        .map(r -> r.role() + ':' + r.id())
                        .toList(),
                walked,
                "the paged order must be the unpaged order");

        // Narrowing to one role pages only that role, and the page still ends with a null cursor.
        FindingEvidenceRepository.Page baseline =
                evidence.page(p.id(), findingId, FindingEvidenceRow.Role.BASELINE, 50, null);
        assertEquals(3, baseline.rows().size());
        assertNull(baseline.nextCursor(), "a page that exhausted the set mints no cursor");

        // An unreadable token is page one, never an error: the only failure mode a feed can absorb.
        assertEquals(
                walked.size(),
                evidence.page(p.id(), findingId, null, 50, "not-a-cursor")
                        .rows()
                        .size());

        // Every role in the vocabulary is reported, so a detector with no reference side reads as an
        // explicit zero rather than as a key somebody forgot to send.
        var counts = evidence.countsByRole(p.id(), findingId);
        assertEquals(FindingEvidenceRow.Role.ALL, List.copyOf(counts.keySet()));
        assertEquals(5L, counts.get(FindingEvidenceRow.Role.MEMBER));
        assertEquals(3L, counts.get(FindingEvidenceRow.Role.BASELINE));
        assertEquals(0L, counts.get(FindingEvidenceRow.Role.WITNESS));

        // count_only sizes the set without walking it: rows omitted on a finding that HAS eight of them,
        // so an empty refs list here is the caller's own request rather than an evidence set that vanished.
        var sized = behaviorDrift.findingEvidence(p.id(), findingId, null, 100, null, true);
        assertTrue(sized.refs().isEmpty(), "the cheap first call spends nothing on rows");
        assertTrue(sized.rowsOmitted(), "rowsOmitted is what separates 'did not ask' from 'has none'");
        assertNull(sized.nextCursor(), "a call that returned no rows must not offer to resume after them");
        assertEquals(5L, sized.counts().get(FindingEvidenceRow.Role.MEMBER));
        assertEquals(5L, sized.recordedCounts().get(FindingEvidenceRow.Role.MEMBER));
    }

    /**
     * The evidence door's project scope, through the real service rather than a stubbed one.
     *
     * <p>The MCP-side test can only prove that a thrown not-found maps to a clean tool error, because it
     * mocks the service that would have decided. The decision is here: {@code findingEvidence} resolves
     * the finding under the CALLER's project first, so a finding id lifted from another tenant is
     * indistinguishable from one that never existed — not-found, never forbidden, since a distinguishable
     * 403 is itself a disclosure that the id is real.
     *
     * <p>The repository is asserted directly beside it, so the scope does not rest on the service guard
     * alone: a {@code page} or a {@code countsByRole} that dropped its {@code project_id} predicate would
     * leak the population to anyone who could guess a finding id.
     */
    @Test
    @DisplayName("another tenant's finding id reads as not-found, and its evidence does not page")
    void evidenceIsScopedToTheCallersProject() {
        Project owner = project("finding-evidence-owner");
        Project stranger = project("finding-evidence-stranger");
        String findingId = firing(owner, "gram-scope");
        evidence.record(
                owner.id(),
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.trace("trace-owned")),
                Instant.now().toString());

        // The positive control. Without it every assertion below would also pass on a finding that was
        // never written, and the test would be proving nothing but its own fixture failing quietly.
        assertEquals(
                1,
                behaviorDrift
                        .findingEvidence(owner.id(), findingId, null, 100, null, false)
                        .refs()
                        .size(),
                "the owner reads its own evidence");

        EvalsException e = assertThrows(
                EvalsException.class,
                () -> behaviorDrift.findingEvidence(stranger.id(), findingId, null, 100, null, false));
        assertEquals(ClassifierError.FINDING_NOT_FOUND, e.error(), "a cross-tenant id must not read as forbidden");

        assertThrows(
                EvalsException.class,
                () -> behaviorDrift.findingEvidence(stranger.id(), findingId, null, 100, null, true),
                "count_only is the cheap first call, so it is also the cheap first probe");

        assertTrue(
                evidence.page(stranger.id(), findingId, null, 100, null).rows().isEmpty(),
                "the page's own project predicate is what makes the service guard belt-and-braces");
        assertEquals(
                0L,
                evidence.countsByRole(stranger.id(), findingId).get(FindingEvidenceRow.Role.MEMBER),
                "a count that ignored the project would report the owner's population to a stranger");
    }

    /**
     * {@code ux_finding_live} keeps {@code blocked} inside its predicate, and dropping it would
     * re-introduce 0033's bug class: the blocked row would stop matching the index, the next firing
     * would conflict with nothing, a second finding would be INSERTed beside it, and the human's verdict
     * would be silently discarded.
     */
    @Test
    @DisplayName("a blocked finding still owns its cause, so a later firing lands on it")
    void blockedStaysInsideTheLiveArbiter() {
        Project p = project("finding-blocked-arm");
        String findingId = firing(p, "gram-blocked");
        findings.setStatus(
                p.id(), findingId, FindingRow.Status.BLOCKED, Instant.now().toString());

        String second = firing(p, "gram-blocked");

        assertEquals(findingId, second, "the firing landed on the blocked row rather than beside it");
        FindingRow after = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.BLOCKED, after.status(), "the human verdict survives the firing");
        assertEquals(
                1,
                after.recurrencesSinceVerdict(),
                "only a blocked row counts recurrences — firings AFTER a person said this must not happen");
    }

    /**
     * The forward CHECK, as {@code 0095} rewrote it: {@code finding_id IS NOT NULL OR state = 'resolved'}.
     * A LIVE case names the finding it is about, because a case that cannot say what it is about is a
     * triage row nobody can act on.
     *
     * <p><b>The state arm is what let both of 0086's escape arms go</b> — a detector list and an
     * {@code opened_at < '2026-08-13'} cutoff. 0090 explained why they could not simply be dropped:
     * force-resolving a retired detector's cases sets {@code state} and does NOT backfill
     * {@code finding_id}, and the CHECK had no state arm, so every post-cutoff findingless row would fail
     * {@code ADD CONSTRAINT} and take the migration down. Giving it one says the invariant directly
     * instead of naming the detectors and the date it happened to be true on, and history stays legal.
     * The second half of this test is that row: findingless, post-cutoff, and resolved.
     */
    @Test
    @DisplayName("a live case must name a finding; a resolved case may be findingless")
    void everyLiveCasePointsAtAFinding() {
        Project p = project("finding-case-check");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCase(p, "behavior_drift", null, "open"),
                "behaviour drift has a finding by construction, so a live case without one is a bug");

        // What 0086/0090/0095 leave behind: cases opened by a source that no longer exists, closed by the
        // migration that retired it, still carrying no finding. They have to remain legal or no migration
        // after the one that closed them could re-add this constraint.
        insertCase(p, "classifier", null, "resolved");
        // `grader_degradation` was the second half of that pair and is no longer insertable: 0016 DELETED
        // its rows and narrowed eval_case_detector_check off it, rather than leaving them resolved-and-
        // findingless like the `classifier` ones above. The asymmetry is deliberate and is the migration's
        // own comment: a retired-source row stays legal when some detector could still re-assert or close
        // it, and grader_degradation's could not — it was the grader CUSUM watcher, and grading is gone.

        insertCase(p, "behavior_drift", firing(p, "gram-case"), "open");
    }

    /**
     * Mark-wrong re-anchored. {@code of_verdict_id} pointed at a detection verdict, and those aged out on
     * the 90-day verdict TTL — so a human's correction outlived the row it was attached to and the
     * training signal was lost by a clock rather than by a decision. Track A finished the argument by
     * deleting the verdict table outright; {@code of_finding_id} is now the only anchor there is, and
     * the {@code annotation} table itself survives precisely because the classifier still trains on it.
     */
    @Test
    @DisplayName("a correction anchors on the finding, the only anchor left")
    void annotationsAnchorOnTheFinding() {
        Project p = project("finding-annotation-anchor");
        String findingId = firing(p, "gram-annotation");
        annotations.upsert(new AnnotationRow(
                Ids.ulid(),
                p.id(),
                AnnotationRow.SubjectKind.TRACE,
                "session-1",
                "trace-1",
                null,
                "behavior_drift",
                "user-1",
                AnnotationRow.AnnotatorKind.HUMAN,
                "boolean",
                null,
                null,
                null,
                null,
                findingId,
                true,
                null,
                Instant.now().toString(),
                null));

        assertEquals(
                1L,
                jdbc.sql("SELECT count(*) FROM annotation WHERE project_id = :pid AND of_finding_id = :fid")
                        .param("pid", p.id())
                        .param("fid", findingId)
                        .query(Long.class)
                        .single(),
                "the anchor is stored, and it is the finding rather than a verdict");
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private Project project(String name) {
        return bootstrapGranted(name).project();
    }

    /** One behaviour-drift firing against {@code gram}, returning the finding that owns the cause. */
    private String firing(Project p, String gram) {
        return findings.recordFiring(
                        Ids.ulid(),
                        p.id(),
                        "profile-1",
                        FindingRow.Cause.NOVELTY,
                        gram,
                        FindingRow.GLOBAL_WORKFLOW,
                        1,
                        null,
                        null,
                        "cs-a",
                        Instant.now().toString())
                .findingId();
    }

    private void insertCase(Project p, String detector, @Nullable String findingId, String state) {
        jdbc.sql("""
            INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label,
                                   metric, finding_id, state, resolution, resolved_at, title, basis,
                                   severity, onset_at, opened_at, last_seen_at, updated_at)
            SELECT :id, :pid, COALESCE(MAX(seq), 0) + 1, :detector, 'behavior_profile', :subject, 'label',
                   'pass_rate', :fid, :state,
                   CASE WHEN :state = 'resolved' THEN 'recovered' END,
                   CASE WHEN :state = 'resolved' THEN :now END,
                   'title', 'basis', 0.5, :now, :now, :now, :now
              FROM eval_case WHERE project_id = :pid
            """)
                .param("id", Ids.ulid())
                .param("pid", p.id())
                .param("detector", detector)
                .param("subject", "subject-" + detector + "-" + Ids.ulid())
                .param("fid", findingId)
                .param("state", state)
                .param("now", Instant.now().toString())
                .update();
    }

    /**
     * Bootstrap a tenant whose org has behaviour drift switched ON <b>before its project is created</b>.
     *
     * <p>Two things make this necessary. The open-edition default has behavior_drift OFF
     * (the paid classifiers {@code CapabilityService} reports as unavailable), so without a grant these cases
     * would assert the capability default rather than the behaviour they name. And the grant has to precede the
     * project, because project creation is what seeds the built-in classifiers: grant afterwards and the
     * classifier row is never inserted, leaving the test hunting findings from a classifier the project does not
     * have. The suite used to get all of this ambiently from {@code evals.plan.default-key=enterprise} in
     * surefire, which went away with plan tiers (open-core epic 1 issue 1).
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.BEHAVIOR_DRIFT);
        });
    }
}
