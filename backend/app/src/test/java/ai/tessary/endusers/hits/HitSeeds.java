// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.tenant.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Raw rows for the hit tests: traces, spans, findings, evidence, cases and the classifier sweep cursor. */
record HitSeeds(JdbcClient jdbc, String pid) {

    void trace(String id, @Nullable String session, @Nullable String userId, Instant started) {
        trace(id, session, userId, "chat.turn", null, started);
    }

    void trace(
            String id,
            @Nullable String session,
            @Nullable String userId,
            String name,
            @Nullable String callSite,
            Instant started) {
        jdbc.sql("""
                INSERT INTO trace (project_id, id, session_id, user_id, name, call_site_id, started_at, event_ts,
                                   is_settled, created_at)
                VALUES (:pid, :id, :session, :user, :name, :cs, :at, :at, true, :at)
                """)
                .param("pid", pid)
                .param("id", id)
                .param("session", session)
                .param("user", userId)
                .param("name", name)
                .param("cs", callSite)
                .param("at", Timestamp.from(started))
                .update();
    }

    /** A trace with one span: the trace carries the user ID, the span the call site, output and store time. */
    void tracedSpan(
            String traceId,
            String spanId,
            @Nullable String userId,
            String callSite,
            Instant started,
            Instant stored,
            String output) {
        trace(traceId, null, userId, "chat.turn", callSite, started);
        jdbc.sql("""
                INSERT INTO span (project_id, trace_id, id, kind, call_site_id, started_at, event_ts, output_preview,
                                  created_at)
                VALUES (:pid, :trace, :id, 'llm', :cs, :at, :at, :output, :stored)
                """)
                .param("pid", pid)
                .param("trace", traceId)
                .param("id", spanId)
                .param("cs", callSite)
                .param("at", Timestamp.from(started))
                .param("output", output)
                .param("stored", Timestamp.from(stored))
                .update();
    }

    void callSite(String id, @Nullable String schema) {
        jdbc.sql("INSERT INTO call_site (project_id, id, output_schema) VALUES (:pid, :id, :schema)")
                .param("pid", pid)
                .param("id", id)
                .param("schema", schema)
                .update();
    }

    void finding(String id, String classifierKey, String payload) {
        finding(id, classifierKey, payload, "cls-" + id, null, "2026-01-01T00:00:00Z", "2026-06-15T00:00:00Z");
    }

    void finding(
            String id,
            String classifierKey,
            String payload,
            String subjectId,
            @Nullable String callSite,
            String onset,
            String lastSeen) {
        jdbc.sql("""
                INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                                     status, onset_at, last_seen_at, payload, created_at, updated_at)
                VALUES (:id, :pid, :key, :id, 'classifier', :subject, :cs, 'open', :onset, :lastSeen,
                        CAST(:payload AS jsonb), :onset, :lastSeen)
                """)
                .param("id", id)
                .param("pid", pid)
                .param("key", classifierKey)
                .param("subject", subjectId)
                .param("cs", callSite)
                .param("onset", onset)
                .param("lastSeen", lastSeen)
                .param("payload", payload)
                .update();
    }

    void evidence(String findingId, String role, @Nullable String trace, @Nullable String span, String createdAt) {
        evidence(findingId, role, null, trace, span, createdAt);
    }

    void sessionEvidence(String findingId, String role, String session, String createdAt) {
        evidence(findingId, role, session, null, null, createdAt);
    }

    private void evidence(
            String findingId,
            String role,
            @Nullable String session,
            @Nullable String trace,
            @Nullable String span,
            String createdAt) {
        jdbc.sql("""
                INSERT INTO finding_evidence (id, project_id, finding_id, session_id, trace_id, span_id, role,
                                              created_at)
                VALUES (:id, :pid, :fid, :session, :trace, :span, :role, :at)
                """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("fid", findingId)
                .param("session", session)
                .param("trace", trace)
                .param("span", span)
                .param("role", role)
                .param("at", createdAt)
                .update();
    }

    void session(String id, Instant started) {
        jdbc.sql("""
                INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
                VALUES (:pid, :id, :at, :at, :at)
                """)
                .param("pid", pid)
                .param("id", id)
                .param("at", Timestamp.from(started))
                .update();
    }

    /** A case with the case key {@code (detector, subjectKind, subjectId, metric)}; resolved when {@code resolved}. */
    void caseRow(
            String id,
            long seq,
            String detector,
            String subjectKind,
            String subjectId,
            String metric,
            boolean resolved,
            @Nullable String disposition) {
        jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, metric,
                                       state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at,
                                       resolved_at, resolution, disposition)
                VALUES (:id, :pid, :seq, :detector, :kind, :subject, :subject, :metric, :state, 'title', 'basis', 0.5,
                        '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z',
                        :resolvedAt, :resolution, :disposition)
                """)
                .param("id", id)
                .param("pid", pid)
                .param("seq", seq)
                .param("detector", detector)
                .param("kind", subjectKind)
                .param("subject", subjectId)
                .param("metric", metric)
                .param("state", resolved ? "resolved" : "open")
                .param("resolvedAt", resolved ? "2026-06-02T00:00:00Z" : null)
                .param("resolution", resolved ? "human" : null)
                .param("disposition", disposition)
                .update();
    }

    void attach(String findingId, String caseId, @Nullable String verdict) {
        jdbc.sql("""
                UPDATE finding SET case_id = :case, triage_verdict = :verdict,
                       triage_action = CASE WHEN :verdict = 'positive' THEN 'opened_case' ELSE 'closed' END
                 WHERE project_id = :pid AND id = :id
                """)
                .param("pid", pid)
                .param("id", findingId)
                .param("case", caseId)
                .param("verdict", verdict)
                .update();
    }

    /** The classifier sweep's checked-before cursor. */
    void classifierCursor(String classifierId, Instant cursor) {
        jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, cursor_at, created_at,
                                 updated_at)
                VALUES (:id, :pid, 'classifier', 'pending', 0, :cid, jsonb_build_object('classifier_id', :cid),
                        :cursor, :cursor, :cursor)
                """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", classifierId)
                .param("cursor", cursor.toString())
                .update();
    }
}
