--liquibase formatted sql

--changeset evals:0042-trace-project-started-id runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_trace_project_started_id' AND NOT i.indisvalid
-- The traces list reads newest first, ORDER BY started_at DESC, id DESC, and stops at a page. ix_trace_project_started
-- ordered by started_at alone, so Postgres sorted each run of traces sharing a start instant by id, and had to read
-- the whole run, filters included, before it could return any row of it. A content search checks each trace it reads
-- against its payloads, so a run of 3,000 traces imported with one timestamp made a search for a common word take
-- 1.2 s instead of 13 ms. This index is in the list's own order, so the read stops at the page whatever the ties.
-- CONCURRENTLY keeps ingest writing while it builds; a build that failed leaves an INVALID index that IF NOT EXISTS
-- would skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_trace_project_started_id ON trace (project_id, started_at DESC, id DESC);
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_trace_project_started_id;

--changeset evals:0042-drop-trace-project-started runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:1 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_trace_project_started_id' AND i.indisvalid
-- ix_trace_project_started_id serves every read ix_trace_project_started served, since it starts with the same two
-- columns in the same order, so a trace write keeps updating one index rather than two. The precondition drops the
-- old one only once the new one is there and valid.
DROP INDEX CONCURRENTLY IF EXISTS ix_trace_project_started;
--rollback CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_trace_project_started ON trace (project_id, started_at DESC);
