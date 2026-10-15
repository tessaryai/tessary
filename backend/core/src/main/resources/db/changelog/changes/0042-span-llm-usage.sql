--liquibase formatted sql

--changeset evals:0042-span-llm-usage runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_llm_usage' AND NOT i.indisvalid
-- Vitals sums the llm spans of a window (VitalsRepository.usageIn) on every Triage and Vitals visit. Through
-- ix_span_project_started it fetched every span row of the window, llm or not, to read six narrow columns: on a
-- project with 400k spans a 7-day window was 28k heap pages. This partial index holds exactly those columns for the
-- rows the read keeps, so the window is an index-only scan. It is written only for llm spans; measured on a copy of
-- span, it adds about 7% to the time of inserting and updating span rows. CONCURRENTLY keeps ingest writing while it
-- builds; a build that failed leaves an INVALID index that IF NOT EXISTS would skip, so the precondition halts on
-- one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_llm_usage ON span (project_id, started_at)
    INCLUDE (call_site_id, provided_model_name, trace_id, total_tokens, total_cost, cost_source)
    WHERE kind = 'llm' AND NOT is_deleted;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_llm_usage;
