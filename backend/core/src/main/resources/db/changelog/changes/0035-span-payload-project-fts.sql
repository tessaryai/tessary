--liquibase formatted sql

--changeset evals:0035-btree-gin
-- btree_gin lets a GIN index hold project_id beside the payload words, so a search intersects one project's rows
-- with a word's rows inside the index instead of fetching every tenant's matches. It is a trusted extension, so the
-- database owner can create it.
CREATE EXTENSION IF NOT EXISTS btree_gin WITH SCHEMA public;
--rollback DROP EXTENSION IF EXISTS btree_gin;

--changeset evals:0035-span-payload-project-fts runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_payload_project_fts' AND NOT i.indisvalid
-- The traces search and the palette read payload words for one project. ix_span_payload_fts holds every project's
-- words in one list, so a word common in one tenant made every tenant's search fetch and re-parse those rows. The
-- word expression is ix_span_payload_fts's, character for character: SpanPayloadRepository.PAYLOAD_TSVECTOR must
-- match it for Postgres to use the index.
--
-- CONCURRENTLY keeps ingest writing while the index builds, which on a large span_payload takes a long time; build
-- it by hand before upgrading such an install (devdocs/guides/common-tasks.md) and this changeset finds it there. A
-- build that failed leaves an INVALID index that IF NOT EXISTS would skip, so the precondition halts on one: drop it
-- and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_payload_project_fts ON span_payload USING gin
    (project_id, to_tsvector('simple', left(coalesce(input, ''), 100000) || ' ' || left(coalesce(output, ''), 100000)));
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_payload_project_fts;

--changeset evals:0035-drop-span-payload-fts runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:1 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_payload_project_fts' AND i.indisvalid
-- ix_span_payload_project_fts serves every query ix_span_payload_fts served: the word expression is the same, and a
-- multi-column GIN index answers a condition on its second column alone. The build before this one queries with
-- p.project_id as well, so a rollback to it keeps an index. Dropping in the same migration means a payload write never
-- updates two word indexes, which roughly doubled the cost of writing a payload while both existed. The precondition
-- drops it only once the new index is there and valid.
DROP INDEX CONCURRENTLY IF EXISTS ix_span_payload_fts;
--rollback CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_payload_fts ON span_payload USING gin (to_tsvector('simple', left(coalesce(input, ''), 100000) || ' ' || left(coalesce(output, ''), 100000)));
