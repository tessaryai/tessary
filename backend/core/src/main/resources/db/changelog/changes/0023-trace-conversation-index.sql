--liquibase formatted sql

--changeset evals:0023-trace-conversation-index
-- A conversation is COALESCE(thread_id, session_id) on a top-level trace. The frustration finding and
-- case pages read the turns before each flagged one by that key, newest first, and nothing indexed it:
-- each read scanned the project's traces.
CREATE INDEX ix_trace_conversation
    ON trace (project_id, (COALESCE(thread_id, session_id)), started_at DESC)
    WHERE parent_trace_id IS NULL;
--rollback DROP INDEX IF EXISTS ix_trace_conversation;
