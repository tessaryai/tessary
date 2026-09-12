--liquibase formatted sql

--changeset evals:0001-rca-report-repo-available
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_name = 'rca_report' AND column_name = 'repo_available'
-- The precondition exists because this changeset was renumbered: it shipped in v1.0.1 as
-- 0024-rca-report-repo-available, so every v1.0.1 install already has the column and already has
-- an `evals:0024-rca-report-repo-available` row in databasechangelog. Liquibase identity is
-- id + author + filename, so under the new number this reads as a brand-new changeset and would
-- fail on the duplicate column. MARK_RAN records it as run instead. The stale 0024 row stays
-- behind and is inert: nothing includes that file any more, so nothing ever consults it.
--
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
