// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The retention pin, both directions. Substrate a live claim stands on is never deleted, and substrate
 * nothing stands on still ages out — a pin that never releases is a disk leak wearing a correctness
 * argument, so every test here that proves something survives also proves its unpinned twin does not.
 * The twin does a second job: without it, a survival assertion passes just as happily when the sweep
 * never reached the project at all, which is the shape a broken test takes when it breaks quietly.
 *
 * <p>These run against the real sweep, not the repository alone: {@link RetentionSweeper} is where the
 * three statements of the traces class are ordered, and the ordering is part of the contract (payload,
 * then structure, then the evidence whose substrate has gone). A test that called the repository methods
 * in its own order would pass while the sweeper called them in the wrong one.
 *
 * <p><b>One rule now, not two.</b> A finding stays {@code open} for as long as it is unruled OR its
 * positive ruling backs a case nobody has resolved — the case model's own liveness is folded into
 * {@code finding.status} by construction (migration {@code 0011}), so the pin reads {@code status =
 * 'open'} alone. There is no separate case leg to test: a case's own state cannot keep a CLOSED
 * finding's substrate alive, because closing a case closes the findings it holds in the same
 * transaction.
 *
 * <p>Every fixture trace is aged 200 days against the 90-day platform default, so the only reason any of
 * them survives a pass is the pin. Other tests' projects are untouched by construction — their traffic is
 * minutes old.
 */
@SpringBootTest
class RetentionPinIntegrationTest {

    private static final String AGED = Instant.now().minus(200, ChronoUnit.DAYS).toString();

    @Autowired
    RetentionSweeper sweeper;

    @Autowired
    RetentionRepository retention;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    /**
     * The base case, and the one that says what "pinned" buys. An open finding holds its exemplar trace
     * AND that trace's payload: a case page whose trace row survived but whose text did not is the same
     * dead evidence link by a slower route, which is why the payload tier carries the guard too.
     */
    @Test
    @DisplayName("an open finding pins its trace and that trace's payload; an unreferenced trace ages out")
    void openFindingPinsTraceAndPayload() {
        Project p = project("pin-open");
        trace(p, "trace-pinned", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-open", "open");
        evidence(p, finding, null, "trace-pinned", null);

        sweep();

        assertTrue(traceExists(p, "trace-pinned"), "an open finding's trace is not deletable");
        assertTrue(payloadExists(p, "trace-pinned"), "and neither is its text");
        assertFalse(traceExists(p, "trace-loose"), "a trace no live claim stands on still ages out");
        assertEquals(1, evidenceCount(p, finding), "the pin holds, so nothing is released");
    }

    /**
     * A positive human ruling keeps the finding {@code open} — the same status column an unruled
     * finding carries — so it pins exactly as hard, through the one rule rather than a second arm.
     */
    @Test
    @DisplayName("a finding a human ruled a real deviation pins as hard as an unruled one")
    void humanRuledFindingPins() {
        Project p = project("pin-human-ruled");
        trace(p, "trace-ruled", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-ruled", "open");
        evidence(p, finding, null, "trace-ruled", null);

        sweep();

        assertTrue(traceExists(p, "trace-ruled"), "a human's ruling keeps its substrate readable");
        assertFalse(traceExists(p, "trace-loose"), "and the sweep did reach this project, so that means something");
    }

    /**
     * The case leg is gone: closing a case closes the findings it holds in the same transaction
     * (migration {@code 0011}), so a case's own {@code state} cannot keep a CLOSED finding's substrate
     * alive even in the inconsistent state of a case row that was never updated to match. Retention
     * reads the finding, and only the finding.
     */
    @Test
    @DisplayName("a closed finding ages out even if a case row pointing at it still reads open")
    void aClosedFindingAgesOutRegardlessOfItsCase() {
        Project p = project("pin-case-orphan");
        trace(p, "trace-cased", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-cased", "closed");
        evidence(p, finding, null, "trace-cased", null);
        openCase(p, finding, "open");

        sweep();

        assertFalse(traceExists(p, "trace-cased"), "the finding closed, so its substrate is no longer pinned");
        assertFalse(traceExists(p, "trace-loose"), "and the sweep did reach this project, so that means something");
    }

    /**
     * The release. Closed means nobody can act on the claim any more, so its substrate ages on the
     * ordinary clock and the evidence rows follow it — collected only once the thing they point at is
     * already gone, which keeps a closed finding's reference count honest right up until it is unusable.
     */
    @Test
    @DisplayName("a closed finding with no case releases, substrate and evidence both")
    void aClosedFindingWithoutACaseAgesOut() {
        Project p = project("pin-released");
        trace(p, "trace-released", null);
        String finding = finding(p, "cause-released", "closed");
        evidence(p, finding, null, "trace-released", null);

        sweep();

        assertFalse(traceExists(p, "trace-released"), "nothing holds it open, so the TTL applies");
        assertEquals(0, evidenceCount(p, finding), "and the reference it left behind is collected");
        assertTrue(findingExists(p, finding), "the finding itself survives — its payload still says what it found");
    }

    /**
     * Span-grain evidence pins the whole trace. The alternative — deleting the trace and keeping the span
     * — is not available: {@code fk_span_trace} cascades, so a span-grain reference that did not reach up
     * to the parent would be aged out by the statement above it every time.
     */
    @Test
    @DisplayName("span-grain evidence blocks the parent trace's delete, through the denormalized trace_id")
    void spanGrainPinsTheParentTrace() {
        Project p = project("pin-span-grain");
        trace(p, "trace-span-grain", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-span", "open");
        evidence(p, finding, null, "trace-span-grain", "span-trace-span-grain");

        sweep();

        assertTrue(traceExists(p, "trace-span-grain"), "the span's parent is held by the span's own row");
        assertTrue(payloadExists(p, "trace-span-grain"), "and the span keeps the text the evidence is for");
        assertFalse(traceExists(p, "trace-loose"), "and the sweep did reach this project, so that means something");
    }

    /**
     * Session grain pins every trace of the session. A conformance claim about a conversation is a claim
     * about its turns; a session whose turns were deleted underneath it is evidence of nothing.
     */
    @Test
    @DisplayName("session-grain evidence pins every trace of that session, and only that session")
    void sessionGrainPinsTheWholeConversation() {
        Project p = project("pin-session-grain");
        session(p, "sess-pinned");
        session(p, "sess-loose");
        trace(p, "trace-turn-1", "sess-pinned");
        trace(p, "trace-turn-2", "sess-pinned");
        trace(p, "trace-other", "sess-loose");
        String finding = finding(p, "cause-session", "open");
        evidence(p, finding, "sess-pinned", null, null);

        sweep();

        assertTrue(traceExists(p, "trace-turn-1"), "the first turn of the pinned conversation survives");
        assertTrue(traceExists(p, "trace-turn-2"), "so does the second — the claim is about the whole thread");
        assertTrue(payloadExists(p, "trace-turn-2"), "including the text, read through the trace's session");
        assertFalse(traceExists(p, "trace-other"), "a different session is not pinned by association");
        assertEquals(1, evidenceCount(p, finding), "and the reference is held, because its turns are");
    }

    /**
     * The session leg's release, which is the half that is easy to get wrong: nothing in retention ever
     * deletes a {@code session} row, so a release conditioned on the session disappearing would never
     * fire and this leg would hold its evidence for ever. It releases on the turns instead.
     */
    @Test
    @DisplayName("session-grain evidence releases once the conversation's last turn has aged out")
    void sessionGrainReleasesWhenItsTurnsHaveGone() {
        Project p = project("pin-session-release");
        session(p, "sess-released");
        trace(p, "trace-only-turn", "sess-released");
        String finding = finding(p, "cause-session-released", "closed");
        evidence(p, finding, "sess-released", null, null);

        sweep();

        assertFalse(traceExists(p, "trace-only-turn"), "no live claim stands on the conversation");
        assertEquals(0, evidenceCount(p, finding), "so the reference to it is collected rather than kept for ever");
    }

    /**
     * Evidence collection is guarded in both directions, and this is the direction that would go unnoticed:
     * a closed finding whose traces are still inside their TTL keeps its references, because the case page
     * for a recently-resolved finding is still worth opening.
     */
    @Test
    @DisplayName("a closed finding keeps its evidence while the substrate is still there")
    void evidenceSurvivesWhileItsSubstrateDoes() {
        Project p = project("pin-evidence-live-substrate");
        freshTrace(p, "trace-recent");
        String finding = finding(p, "cause-recent", "closed");
        evidence(p, finding, null, "trace-recent", null);

        sweep();

        assertTrue(traceExists(p, "trace-recent"), "inside the TTL, nothing deletes it");
        assertEquals(1, evidenceCount(p, finding), "so the reference still resolves and is not collected");
    }

    /**
     * And the other direction: a LIVE finding's dangling reference is not collected either. Backfilled
     * findings point at traces that aged out before the pin existed, and silently deleting those rows
     * would shrink the evidence set of a finding someone is currently triaging.
     */
    @Test
    @DisplayName("a live finding's dangling reference is left alone, however dead the pointer")
    void danglingEvidenceUnderALiveFindingIsNotCollected() {
        Project p = project("pin-evidence-dangling");
        String finding = finding(p, "cause-dangling", "open");
        evidence(p, finding, null, "trace-never-ingested", null);

        sweep();

        assertEquals(1, evidenceCount(p, finding), "the pin releases on the claim's lifecycle, not on a lookup");
    }

    /**
     * The number the sweep reports. Without it an unbounded pin is indistinguishable from a healthy policy:
     * the deletion counts fall to zero either way, and only this one says whether that is because there is
     * nothing left to delete or because something is holding everything open.
     */
    @Test
    @DisplayName("the pinned-trace count reports what the pin is holding, and drops when it releases")
    void pinnedCountIsVisible() {
        Project p = project("pin-count");
        trace(p, "trace-counted", null);
        trace(p, "trace-uncounted", null);
        String finding = finding(p, "cause-counted", "open");
        evidence(p, finding, null, "trace-counted", null);

        assertEquals(1, retention.countPinnedTraces(p.id()), "one trace is held open");

        setStatus(p, finding, "closed");
        assertEquals(0, retention.countPinnedTraces(p.id()), "and the count follows the claim's lifecycle");
    }

    /**
     * The Frustration classifier's copy of a turn's text ages with the payload it was built from: the
     * aged turn's {@code request} is nulled and the rest of its assessment stays, while a pinned turn's
     * copy is kept, as its payload is.
     */
    @Test
    @DisplayName("an aged frustration assessment loses its request text and keeps its verdict; a pinned one keeps both")
    void frustrationAssessmentRequestsAgeWithTheirPayloads() {
        Project p = project("pin-frustration-request");
        trace(p, "trace-assessed", null);
        trace(p, "trace-assessed-pinned", null);
        String classifier = classifier(p);
        assessment(p, classifier, "trace-assessed");
        assessment(p, classifier, "trace-assessed-pinned");
        String finding = finding(p, "cause-frustration", "open");
        evidence(p, finding, null, "trace-assessed-pinned", null);

        sweep();

        assertFalse(traceExists(p, "trace-assessed"), "the aged turn itself is gone");
        assertTrue(requestNulled(p, "trace-assessed"), "so is the copy of its text");
        assertEquals(
                "v1|true|{\"model\": \"jev\"}",
                jdbc.sql("SELECT scorer_version || '|' || frustrated || '|' || response::text"
                                + " FROM frustration_assessment WHERE project_id = :pid AND trace_id = :tid")
                        .param("pid", p.id())
                        .param("tid", "trace-assessed")
                        .query(String.class)
                        .single(),
                "the verdict, the version and the answer stay");
        assertFalse(requestNulled(p, "trace-assessed-pinned"), "a pinned turn keeps its text, as its payload does");
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /**
     * The real sweep, over every active project. Deletion is by event time, and every other test's traffic
     * is minutes old against a 90-day default, so this only ever reaches the rows aged below.
     */
    private void sweep() {
        sweeper.sweep();
    }

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    private void session(Project p, String sessionId) {
        jdbc.sql("""
                        INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
                        VALUES (:pid, :id, :at::timestamptz, :at::timestamptz, :at::timestamptz)
                        """)
                .param("pid", p.id())
                .param("id", sessionId)
                .param("at", AGED)
                .update();
    }

    /** An aged trace with one span and that span's payload — the three tiers the traces class deletes. */
    private void trace(Project p, String traceId, @Nullable String sessionId) {
        insertTrace(p, traceId, sessionId, AGED);
    }

    /** The same, inside its TTL. */
    private void freshTrace(Project p, String traceId) {
        insertTrace(p, traceId, null, Instant.now().toString());
    }

    private void insertTrace(Project p, String traceId, @Nullable String sessionId, String at) {
        jdbc.sql("""
                        INSERT INTO trace (project_id, id, session_id, started_at, event_ts)
                        VALUES (:pid, :id, :sid, :at::timestamptz, :at::timestamptz)
                        """)
                .param("pid", p.id())
                .param("id", traceId)
                .param("sid", sessionId)
                .param("at", at)
                .update();
        jdbc.sql("""
                        INSERT INTO span (project_id, trace_id, id, session_id, kind, started_at, event_ts)
                        VALUES (:pid, :tid, :sid, :sess, 'llm', :at::timestamptz, :at::timestamptz)
                        """)
                .param("pid", p.id())
                .param("tid", traceId)
                .param("sid", "span-" + traceId)
                .param("sess", sessionId)
                .param("at", at)
                .update();
        jdbc.sql("""
                        INSERT INTO span_payload (project_id, trace_id, span_id, input, output, event_ts)
                        VALUES (:pid, :tid, :sid, 'in', 'out', :at::timestamptz)
                        """)
                .param("pid", p.id())
                .param("tid", traceId)
                .param("sid", "span-" + traceId)
                .param("at", at)
                .update();
    }

    /** The Frustration classifier the project was seeded with, as every built-in is at bootstrap. */
    private String classifier(Project p) {
        return jdbc.sql("SELECT id FROM classifier WHERE project_id = :pid AND classifier_key = 'frustration'")
                .param("pid", p.id())
                .query(String.class)
                .single();
    }

    private void assessment(Project p, String classifierId, String traceId) {
        jdbc.sql("""
                        INSERT INTO frustration_assessment (id, project_id, classifier_id, trace_id, span_id,
                            conversation_id, turn_started_at, frustrated, scorer_version, provider, model,
                            request, response)
                        VALUES (:id, :pid, :cid, :tid, :sid, 'conv-1', :at::timestamptz, true, 'v1', 'TYPESAFE',
                            'jev', CAST('{"state": "text"}' AS jsonb), CAST('{"model": "jev"}' AS jsonb))
                        """)
                .param("id", Ids.ulid())
                .param("pid", p.id())
                .param("cid", classifierId)
                .param("tid", traceId)
                .param("sid", "span-" + traceId)
                .param("at", AGED)
                .update();
    }

    private boolean requestNulled(Project p, String traceId) {
        return jdbc.sql(
                        "SELECT request IS NULL FROM frustration_assessment WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", p.id())
                .param("tid", traceId)
                .query(Boolean.class)
                .single();
    }

    private String finding(Project p, String causeKey, String status) {
        String id = Ids.ulid();
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                             status, onset_at, last_seen_at, created_at, updated_at)
                        VALUES (:id, :pid, 'behavior_drift', :cause, 'behavior_profile', 'profile-1',
                                :status, :now, :now, :now, :now)
                        """)
                .param("id", id)
                .param("pid", p.id())
                .param("cause", causeKey)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }

    private void setStatus(Project p, String findingId, String status) {
        jdbc.sql("UPDATE finding SET status = :status WHERE project_id = :pid AND id = :id")
                .param("status", status)
                .param("pid", p.id())
                .param("id", findingId)
                .update();
    }

    private void evidence(
            Project p,
            String findingId,
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId) {
        jdbc.sql("""
                        INSERT INTO finding_evidence (id, project_id, finding_id, session_id, trace_id, span_id,
                                                      role, created_at)
                        VALUES (:id, :pid, :fid, :sess, :tid, :span, 'exemplar', :now)
                        """)
                .param("id", Ids.ulid())
                .param("pid", p.id())
                .param("fid", findingId)
                .param("sess", sessionId)
                .param("tid", traceId)
                .param("span", spanId)
                .param("now", Instant.now().toString())
                .update();
    }

    /** A case row pointing at {@code findingId} through {@code finding.case_id} — the reverse of the
     *  old forward pointer. Retention no longer reads this table at all; it exists only so the "even
     *  if a case row still reads open" test has a case to be inconsistent with. */
    private void openCase(Project p, String findingId, String state) {
        String now = Instant.now().toString();
        String caseId = Ids.ulid();
        jdbc.sql("""
                        INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id,
                                               subject_label, metric, state, resolved_at, title,
                                               basis, severity, onset_at, opened_at, last_seen_at, updated_at)
                        SELECT :id, :pid, COALESCE(MAX(seq), 0) + 1, 'behavior_drift', 'behavior_profile',
                               :subject, 'label', 'pass_rate', :state, :resolved, 'title', 'basis',
                               0.5, :now, :now, :now, :now
                          FROM eval_case WHERE project_id = :pid
                        """)
                .param("id", caseId)
                .param("pid", p.id())
                .param("subject", "subject-" + Ids.ulid())
                .param("state", state)
                .param("resolved", "resolved".equals(state) ? now : null)
                .param("now", now)
                .update();
        jdbc.sql("UPDATE finding SET case_id = :cid WHERE project_id = :pid AND id = :fid")
                .param("cid", caseId)
                .param("pid", p.id())
                .param("fid", findingId)
                .update();
    }

    // ---- assertions -------------------------------------------------------------------------------

    private boolean traceExists(Project p, String traceId) {
        return count("SELECT count(*) FROM trace WHERE project_id = :pid AND id = :key", p, traceId) == 1;
    }

    private boolean payloadExists(Project p, String traceId) {
        return count("SELECT count(*) FROM span_payload WHERE project_id = :pid AND trace_id = :key", p, traceId) == 1;
    }

    private boolean findingExists(Project p, String findingId) {
        return count("SELECT count(*) FROM finding WHERE project_id = :pid AND id = :key", p, findingId) == 1;
    }

    private long evidenceCount(Project p, String findingId) {
        return count(
                "SELECT count(*) FROM finding_evidence WHERE project_id = :pid AND finding_id = :key", p, findingId);
    }

    private long count(String sql, Project p, String key) {
        Long n = jdbc.sql(sql)
                .param("pid", p.id())
                .param("key", key)
                .query(Long.class)
                .single();
        return n == null ? 0L : n;
    }
}
