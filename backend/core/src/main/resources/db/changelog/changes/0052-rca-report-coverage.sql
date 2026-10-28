--liquibase formatted sql

--changeset evals:0052-rca-report-coverage
-- What an RCA run says it read: {read: [{role, count}], how_chosen}, each count held to what the detector recorded
-- for that role. Stamped once at completion. NULL on reports from before this and on a run that returned none.
ALTER TABLE rca_report ADD COLUMN coverage jsonb;
--rollback ALTER TABLE rca_report DROP COLUMN coverage;
