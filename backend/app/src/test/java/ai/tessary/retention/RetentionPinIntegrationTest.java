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
 * The retention pin, both directions: substrate a live claim stands on survives, and substrate nothing stands on
 * still ages out. Each survival test has an unpinned twin, which also proves the sweep reached the project at all.
 *
 * <p>Run through the real {@link RetentionSweeper}, because its statement order (payload, structure, then orphaned
 * evidence) is part of the contract. A finding stays {@code open} while unruled or backing an unresolved case (0011),
 * so the pin reads {@code status = 'open'} alone.
 *
 * <p>Fixtures are aged 200 days against the 90-day default, so only the pin saves them.
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
     * An open finding pins its trace and that trace's payload: a surviving row with lost text is the same dead
     * evidence link.
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
     * Closing a case closes its findings in one transaction (0011), so retention reads the finding only, even beside
     * a stale open case row.
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

    /** Closed releases: substrate ages normally and evidence rows follow only once their target is gone. */
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
     * Span-grain evidence pins the whole trace: {@code fk_span_trace} cascades, so pinning the span alone would not
     * survive.
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

    /** Session grain pins every trace of the session; a conversation with its turns deleted is evidence of nothing. */
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
     * The session leg releases on the turns: retention never deletes a {@code session} row, so waiting for it would
     * pin forever.
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
     * A closed finding keeps its references while its traces are inside their TTL; a recently resolved case page is
     * still worth opening.
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
     * A live finding's dangling reference is left alone: backfilled findings point at traces that aged out before the
     * pin existed.
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

    /** Without this count an unbounded pin looks like a healthy policy: deletions fall to zero either way. */
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
     * Frustration's copy of turn text ages with its payload: an aged turn's {@code request} is nulled and the verdict
     * kept; a pinned turn keeps both.
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

    /**
     * The real sweep over every project; other tests' traffic is minutes old, so it reaches only the rows aged here.
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

    /** An aged trace with one span and its payload: the three tiers the traces class deletes. */
    private void trace(Project p, String traceId, @Nullable String sessionId) {
        insertTrace(p, traceId, sessionId, AGED);
    }

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

    /**
     * A case row pointing at the finding. Retention never reads this table; it exists for the stale-open-case test.
     */
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
