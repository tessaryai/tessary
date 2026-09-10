--liquibase formatted sql

--changeset evals:0024-rca-report-repo-available
-- Whether the run actually had the project's repository to read.
--
-- An RCA without a repo still produces a report; it just cannot say what changed in the code, and
-- until now the only trace of that ceiling was a sentence the agent wrote into the markdown body,
-- where it reads as part of the analysis rather than as a limit on it. The report page needs to say
-- so above the verdict, and that claim has to be about the run that happened, not about whether a
-- repo happens to be connected when someone opens the page later.
--
-- Nullable on purpose: reports written before this column cannot answer, and "unknown" must not
-- render as "no repository". Readers show the notice only on an explicit false.
ALTER TABLE rca_report ADD COLUMN repo_available boolean;

--rollback ALTER TABLE rca_report DROP COLUMN repo_available;
