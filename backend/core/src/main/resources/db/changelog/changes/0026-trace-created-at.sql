--liquibase formatted sql

--changeset evals:0026-trace-created-at
-- Billing counts traces by the hour we accepted them, the way ingested_spans already counts spans on
-- span.created_at: a backfill of three months of history is work done in the hour it arrived, not
-- three months of usage. trace had no ingest timestamp, only the producer's started_at. Every
-- writer names its columns explicitly and inserts with ON CONFLICT DO NOTHING, so the default fills
-- this on first sight and nothing overwrites it.
ALTER TABLE trace ADD COLUMN created_at timestamp with time zone NOT NULL DEFAULT now();
CREATE INDEX ix_trace_project_created ON trace (project_id, created_at);

--rollback DROP INDEX ix_trace_project_created;
--rollback ALTER TABLE trace DROP COLUMN created_at;
