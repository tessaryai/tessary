--liquibase formatted sql

--changeset evals:0034-conversation-is-session
-- A conversation is a session. trace.thread_id is only a column: a producer that sends one thread id per
-- user for all time keeps one conversation per session. Every read of a conversation's turns is now
-- trace.session_id on a top-level trace, which ix_trace_session already serves, so the expression index
-- 0023 built on COALESCE(thread_id, session_id) has no reader left.
--
-- Rows written under the thread key are not rewritten. The frustration scorer_version now hashes the key,
-- so the rate reads only rows scored per session, and an old detection keyed on a thread id matches no
-- session and stops nothing.
DROP INDEX IF EXISTS ix_trace_conversation;

COMMENT ON COLUMN frustration_assessment.conversation_id IS 'The trace.session_id of the scored turn.';
COMMENT ON COLUMN frustration_detection.subject_session_id IS 'The trace.session_id of the flagged turn. With evidence->>''call_site_id'' it names the session.';

--rollback CREATE INDEX ix_trace_conversation ON trace (project_id, (COALESCE(thread_id, session_id)), started_at DESC) WHERE parent_trace_id IS NULL;
--rollback COMMENT ON COLUMN frustration_assessment.conversation_id IS NULL;
--rollback COMMENT ON COLUMN frustration_detection.subject_session_id IS 'The conversation key, COALESCE(trace.thread_id, trace.session_id), of the flagged turn. With evidence->>''call_site_id'' it names the session.';
