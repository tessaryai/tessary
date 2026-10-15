--liquibase formatted sql

--changeset evals:0043-span-untagged runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_span_untagged' AND NOT i.indisvalid
-- The substrate status, read on every app start, counts a project's spans with no call site up to 1,000
-- (SubstrateReadRepository.untaggedSpans). Once a project tags its spans there are none, and proving that read every
-- span of every project: 65 ms on 400k spans, most of the status read. This partial index holds only the untagged
-- spans, so the count reads at most 1,000 entries and an all-tagged project reads none. A tagged span is never written
-- to it; on a project that tags nothing, it adds about 4% to the time of inserting and updating span rows, measured
-- on a copy of span. CONCURRENTLY keeps ingest writing while it builds; a build that failed leaves an INVALID index
-- that IF NOT EXISTS would skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_span_untagged ON span (project_id) WHERE call_site_id IS NULL;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_span_untagged;
