--liquibase formatted sql

--changeset evals:0057-span-prompt-link-columns
-- A span produced from a managed prompt carries the prompt's name and the version string the producer sent, read from
-- tessary.prompt.*. Text, not integer: a producer may send a label such as production. No foreign key and no id:
-- spans predate and outlive prompts, retention deletes spans by age, never by prompt, every read keys on the name, and
-- ingest never looks a prompt up, so a link can never fail or slow a batch.
ALTER TABLE span ADD COLUMN prompt_name text, ADD COLUMN prompt_version text;
--rollback ALTER TABLE span DROP COLUMN prompt_version, DROP COLUMN prompt_name;

--changeset evals:0057-span-prompt-index runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_prompt' AND NOT i.indisvalid
-- One prompt's spans over a window: its per-version usage and the traces filter by prompt. Partial, because linked
-- spans are a small share of span. started_at follows prompt_name so the window bounds the scan for one prompt, and
-- prompt_version is an INCLUDE column because a key column ahead of started_at would leave the window unbounded.
-- CONCURRENTLY keeps ingest writing while it builds; a failed build leaves an INVALID index that IF NOT EXISTS would
-- skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_prompt ON span (project_id, prompt_name, started_at DESC)
    INCLUDE (prompt_version) WHERE prompt_name IS NOT NULL;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_prompt;

--changeset evals:0057-span-prompt-window-index runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_prompt_window' AND NOT i.indisvalid
-- Every prompt's calls over a window, which has no name to seek on, so this one is keyed by time. Its predicate is
-- the usage read's own filter, live llm spans that name a prompt, spelled as the read spells it, and trace_id rides
-- along so the read never touches span rows. prompt_name is a key column so a per-prompt read that picks this index
-- skips other prompts' entries in the index instead of reading their rows. On one million linked spans #256 measured
-- a 7-day read at about 80 ms with it and 220 ms without.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_prompt_window ON span (project_id, started_at DESC, prompt_name)
    INCLUDE (trace_id) WHERE prompt_name IS NOT NULL AND kind = 'llm' AND is_deleted IS NOT TRUE;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_prompt_window;
