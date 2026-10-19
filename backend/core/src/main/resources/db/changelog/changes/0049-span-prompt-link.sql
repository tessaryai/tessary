--liquibase formatted sql

--changeset evals:0049-span-prompt-link-columns
-- A span produced from a managed prompt carries the prompt's name and the version string the producer sent, so the
-- Prompts page can count calls per version and the traces list can keep the traces of one prompt. Text, not integer:
-- the attribute is a string and a producer may send a label such as prod. No foreign key: spans predate and outlive
-- prompts, and retention deletes spans by age, never by prompt.
ALTER TABLE span ADD COLUMN prompt_name text, ADD COLUMN prompt_version text;
--rollback ALTER TABLE span DROP COLUMN prompt_version, DROP COLUMN prompt_name;

--changeset evals:0049-span-prompt-link-index runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_prompt' AND NOT i.indisvalid
-- The Prompts page groups a prompt's llm spans by version over a window, and the traces filter asks whether a trace
-- holds a span of one prompt. Both read only linked spans, a small share of span, so the index is partial. started_at
-- follows prompt_name so the window bounds the scan for one prompt; prompt_version rides along as an INCLUDE column,
-- because a key column ahead of started_at would leave the window unbounded (no skip scan before Postgres 18).
-- CONCURRENTLY keeps ingest writing while it builds; a failed build leaves an INVALID index that IF NOT EXISTS would
-- skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_prompt ON span (project_id, prompt_name, started_at DESC)
    INCLUDE (prompt_version) WHERE prompt_name IS NOT NULL;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_prompt;
