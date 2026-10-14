--liquibase formatted sql

--changeset evals:0038-trace-session-ended runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_trace_session_ended' AND NOT i.indisvalid
-- The session timeline reads a session's traces latest end first, a page at a time, and stops once it has enough.
-- ix_trace_session orders by start, so each read fetched and sorted every trace of the session, and a session kept
-- for months (0034) has tens of thousands. This index holds the settled traces with an end in the read's order, so a
-- page reads only its own rows. CONCURRENTLY keeps ingest writing while it builds; a build that failed leaves an
-- INVALID index that IF NOT EXISTS would skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_trace_session_ended
    ON trace (project_id, session_id, ended_at DESC, started_at DESC, id DESC)
    WHERE session_id IS NOT NULL AND NOT is_deleted AND is_settled AND ended_at IS NOT NULL;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_trace_session_ended;

--changeset evals:0038-trace-session-unended runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_trace_session_unended' AND NOT i.indisvalid
-- The traces of a session with no settled end, which the timeline reads first and whole: the traces still receiving
-- spans, and the settled ones none of whose spans ended. The index holds only those, so it stays small.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_trace_session_unended
    ON trace (project_id, session_id, started_at DESC, id DESC)
    WHERE session_id IS NOT NULL AND NOT is_deleted AND (NOT is_settled OR ended_at IS NULL);
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_trace_session_unended;
