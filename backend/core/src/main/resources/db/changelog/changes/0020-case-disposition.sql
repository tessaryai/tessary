--liquibase formatted sql

--changeset evals:0020-case-disposition
-- What a person said a resolved frustration case turned out to be. 'fixed': the agent was changed, so
-- the call site's CUSUM restarts and re-learns its normal rate from the traffic after the resolve.
-- 'false_alarm': the same restart, and every conversation the case cites has its frustration flag
-- cleared, so it stops counting as frustrated and its later turns are scored again. NULL on every other
-- case, and on a frustration case resolved without a choice (which restarts and clears nothing).
ALTER TABLE eval_case ADD COLUMN disposition text;
ALTER TABLE eval_case ADD CONSTRAINT eval_case_disposition_check
    CHECK (disposition IS NULL OR disposition IN ('fixed', 'false_alarm'));

--rollback ALTER TABLE eval_case DROP CONSTRAINT eval_case_disposition_check;
--rollback ALTER TABLE eval_case DROP COLUMN disposition;
