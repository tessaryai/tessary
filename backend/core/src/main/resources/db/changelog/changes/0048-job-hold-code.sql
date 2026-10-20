--liquibase formatted sql

--changeset evals:0048-job-hold-code
-- Why a job is held for a person: the error code of a refusal that repeats until someone changes a
-- setting (a key the provider rejects, a launcher older than the backend). NULL while the job runs,
-- waits to run, or retries. Only the triage queue holds jobs today.
ALTER TABLE job ADD COLUMN hold_code text;
--rollback ALTER TABLE job DROP COLUMN hold_code;
