--liquibase formatted sql

--changeset evals:0030-classifier-call-site-scope
-- The call sites a classifier runs on. NULL runs it on every call site, which is how every classifier
-- ran before. A list is never empty: a classifier that should run nowhere is turned off instead.
-- Tenant-controlled like enabled and mode, so a catalog re-sync never writes it.
ALTER TABLE classifier ADD COLUMN call_site_ids text[];
ALTER TABLE classifier ADD CONSTRAINT classifier_call_site_ids_check
    CHECK (call_site_ids IS NULL OR cardinality(call_site_ids) > 0);

--rollback ALTER TABLE classifier DROP CONSTRAINT classifier_call_site_ids_check;
--rollback ALTER TABLE classifier DROP COLUMN call_site_ids;
