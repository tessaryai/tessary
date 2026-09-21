--liquibase formatted sql

--changeset evals:0015-frustration-paused
-- Two pieces of sweep state the Frustration classifier's decision-model calls need.
--
-- classifier.paused_reason / paused_at: a classifier that calls a provider on the org's own key stops
-- when that key is refused (provider_rejected, HTTP 401/403) or when no key is configured for its lane
-- (no_provider). A paused sweep sends nothing and advances past what it skipped; the pause is cleared
-- when a later sweep finds a usable key again. Both columns are set together or not at all.
--
-- job.page_retries: how many times the sweep has held its current page because most of the page's
-- provider calls failed. A held page leaves the cursor where it was and is sent again on the next
-- tick; after the configured number of holds the page is skipped. It counts consecutive holds of one
-- page, so any cursor move resets it.
ALTER TABLE classifier ADD COLUMN paused_reason text;
ALTER TABLE classifier ADD COLUMN paused_at text;
ALTER TABLE classifier ADD CONSTRAINT classifier_paused_reason_check
    CHECK (paused_reason IS NULL OR paused_reason IN ('provider_rejected', 'no_provider'));
ALTER TABLE classifier ADD CONSTRAINT classifier_paused_pair_check
    CHECK ((paused_reason IS NULL) = (paused_at IS NULL));

ALTER TABLE job ADD COLUMN page_retries integer DEFAULT 0 NOT NULL;
ALTER TABLE job ADD CONSTRAINT job_page_retries_check CHECK (page_retries >= 0);

--rollback ALTER TABLE job DROP CONSTRAINT job_page_retries_check;
--rollback ALTER TABLE job DROP COLUMN page_retries;
--rollback ALTER TABLE classifier DROP CONSTRAINT classifier_paused_pair_check;
--rollback ALTER TABLE classifier DROP CONSTRAINT classifier_paused_reason_check;
--rollback ALTER TABLE classifier DROP COLUMN paused_at;
--rollback ALTER TABLE classifier DROP COLUMN paused_reason;
