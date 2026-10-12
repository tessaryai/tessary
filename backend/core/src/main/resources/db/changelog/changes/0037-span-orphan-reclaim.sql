--liquibase formatted sql

--changeset evals:0037-span-orphan-parent-index runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_orphan_parent' AND NOT i.indisvalid
-- A span write takes back the orphans it is the parent of (substrate-model.md §6.4): for every arrived span it asks
-- for the orphan rows whose parent_span_id is its id. Without an index that is a scan of the whole trace per
-- arrival, on every batch, and quadratic over a large trace uploaded in many batches. Orphans are rare and a span is
-- inserted pending, so this partial index is tiny, costs an ingest insert nothing, and turns each probe into a
-- near-empty lookup. CONCURRENTLY keeps ingest writing while it builds; a build that failed leaves an INVALID index
-- that IF NOT EXISTS would skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_orphan_parent ON span (project_id, trace_id, parent_span_id)
    WHERE path_state = 'orphan';
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_orphan_parent;

--changeset evals:0037-span-orphan-reclaim-backfill
-- Before the reclaim existed, a parent that arrived after the trace settled left its children retired as orphans
-- beside it. Those rows are repaired once here: every orphan whose parent is present and not itself an orphan, and
-- the orphans behind it to any depth, go back to pending, and the resolver gives them their paths over the next
-- passes. An orphan whose parent is still missing is left as it is; the traces are settled, so a chain the walk
-- reclaimed behind a parent that cannot resolve is retired again by the next orphan pass.
WITH RECURSIVE reclaim AS (
    SELECT c.project_id, c.trace_id, c.id
      FROM span c
      JOIN span p ON p.project_id = c.project_id AND p.trace_id = c.trace_id AND p.id = c.parent_span_id
     WHERE c.path_state = 'orphan' AND p.path_state <> 'orphan'
    UNION
    SELECT c.project_id, c.trace_id, c.id
      FROM span c
      JOIN reclaim r ON r.project_id = c.project_id AND r.trace_id = c.trace_id AND r.id = c.parent_span_id
     WHERE c.path_state = 'orphan'
)
UPDATE span s SET path_state = 'pending'
  FROM reclaim r
 WHERE s.project_id = r.project_id AND s.trace_id = r.trace_id AND s.id = r.id;
--rollback SELECT 1;
