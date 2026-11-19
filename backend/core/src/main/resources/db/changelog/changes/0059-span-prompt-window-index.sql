--liquibase formatted sql

--changeset evals:0059-span-prompt-window-index runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_prompt_window' AND NOT i.indisvalid
-- The Prompts list counts each prompt's calls over a window. ix_span_prompt leads with prompt_name, and that read has
-- no name to seek on, so it walked every linked index entry of the project over the whole retention period. This
-- index is keyed by time, so the window bounds the scan. The predicate is the read's own llm and live-span filter,
-- spelled as the read spells it, and trace_id rides along, so the read never touches span rows: on one million linked
-- spans a 7-day read fell from about 220 ms to about 80 ms. prompt_name is a key column, not an INCLUDE column, so
-- when the planner picks this index for one prompt's per-version read it drops other prompts' entries in the index
-- instead of reading their rows. Only linked live llm spans are written to it. CONCURRENTLY keeps ingest writing while
-- it builds; a failed build leaves an INVALID index that IF NOT EXISTS would skip, so the precondition halts on one:
-- drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_prompt_window ON span (project_id, started_at DESC, prompt_name)
    INCLUDE (trace_id) WHERE prompt_name IS NOT NULL AND kind = 'llm' AND is_deleted IS NOT TRUE;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_prompt_window;
