// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
 * <p>Every fixture trace is aged 200 days against the 90-day platform default, so the only reason any of
 * them survives a pass is the pin. Other tests' projects are untouched by construction — their traffic is
 * minutes old.
 */
@SpringBootTest
class RetentionPinIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
     * {@code blocked} is inside the live predicate, exactly as it is inside {@code ux_finding_live}.
     * Dropping it here would age out the evidence under a finding a human has ruled on — the one row
     * whose substrate is most worth keeping, because someone is going to be asked about it again.
     */
    @Test
    @DisplayName("a blocked finding pins as hard as an open one")
    void blockedFindingPins() {
        Project p = project("pin-blocked");
        trace(p, "trace-blocked", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-blocked", "blocked");
        evidence(p, finding, null, "trace-blocked", null);

        sweep();

        assertTrue(traceExists(p, "trace-blocked"), "a human's ruling keeps its substrate readable");
        assertFalse(traceExists(p, "trace-loose"), "and the sweep did reach this project, so that means something");
    }

    /**
     * The case leg, which the finding leg does not subsume. A finding resolves the moment the detector
     * stops seeing the cause; the case someone opened on it stays in their queue until they close it, and
     * their queue is the surface that renders the traces.
     */
    @Test
    @DisplayName("a resolved finding still pins while an unresolved case stands on it")
    void unresolvedCasePinsAResolvedFinding() {
        Project p = project("pin-case-open");
        trace(p, "trace-cased", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-cased", "resolved");
        evidence(p, finding, null, "trace-cased", null);
        openCase(p, finding, "open");

        sweep();

        assertTrue(traceExists(p, "trace-cased"), "the case is still on someone's desk");
        assertTrue(payloadExists(p, "trace-cased"), "with its text intact");
        assertFalse(traceExists(p, "trace-loose"), "and the sweep did reach this project, so that means something");
    }

    /** Muted is "not now", not "done with" — {@code state <> 'resolved'} is deliberate. */
    @Test
    @DisplayName("a muted case pins, because muted is not resolved")
    void mutedCasePins() {
        Project p = project("pin-muted");
        trace(p, "trace-muted", null);
        trace(p, "trace-loose", null);
        String finding = finding(p, "cause-muted", "resolved");
        evidence(p, finding, null, "trace-muted", null);
        openCase(p, finding, "muted");

        sweep();

        assertTrue(traceExists(p, "trace-muted"), "an unmute must not reveal a page of dead links");
        assertFalse(traceExists(p, "trace-loose"), "and the sweep did reach this project, so that means something");
    }

    /**
     * The release. Both legs terminal means nobody can act on the claim any more, so its substrate ages on
     * the ordinary clock and the evidence rows follow it — collected only once the thing they point at is
     * already gone, which keeps a closed finding's reference count honest right up until it is unusable.
     */
    @Test
    @DisplayName("a resolved finding under a resolved case releases, substrate and evidence both")
    void bothTerminalAgesOut() {
        Project p = project("pin-released");
        trace(p, "trace-released", null);
        String finding = finding(p, "cause-released", "resolved");
        evidence(p, finding, null, "trace-released", null);
        openCase(p, finding, "resolved");

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
        String finding = finding(p, "cause-session-released", "resolved");
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
        String finding = finding(p, "cause-recent", "resolved");
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

        setStatus(p, finding, "resolved");
        assertEquals(0, retention.countPinnedTraces(p.id()), "and the count follows the claim's lifecycle");
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

    private void openCase(Project p, String findingId, String state) {
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id,
                                               subject_label, metric, finding_id, state, resolved_at, title,
                                               basis, severity, onset_at, opened_at, last_seen_at, updated_at)
                        SELECT :id, :pid, COALESCE(MAX(seq), 0) + 1, 'behavior_drift', 'behavior_profile',
                               :subject, 'label', 'pass_rate', :fid, :state, :resolved, 'title', 'basis',
                               0.5, :now, :now, :now, :now
                          FROM eval_case WHERE project_id = :pid
                        """)
                .param("id", Ids.ulid())
                .param("pid", p.id())
                .param("subject", "subject-" + Ids.ulid())
                .param("fid", findingId)
                .param("state", state)
                .param("resolved", "resolved".equals(state) ? now : null)
                .param("now", now)
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
