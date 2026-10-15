--liquibase formatted sql

--changeset evals:0045-span-root runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_root' AND NOT i.indisvalid
-- A turn is its trace's first parentless span, and the Classifiers page's turn-duration chart and the metric drift
-- sweep read it once per trace. Through the primary key that fetched every span of the trace from the heap and sorted
-- the roots. This index holds only the live roots, in that order and with their end, so each lookup reads its one
-- row from the index alone. CONCURRENTLY keeps ingest writing while it builds; a build that failed leaves an INVALID
-- index that IF NOT EXISTS would skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_root ON span (project_id, trace_id, started_at, id) INCLUDE (ended_at)
    WHERE parent_span_id IS NULL AND is_deleted IS NOT TRUE;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_root;
