--liquibase formatted sql

--changeset evals:0054-rca-report-finding-ids
-- The case's findings an RCA read: a JSON array of finding ids, written when a worker claims the job, so windows that
-- join the live case later read as unread. NULL on reports from before this; such a report counts as having read
-- every finding of its case created before the report.
ALTER TABLE rca_report ADD COLUMN finding_ids jsonb;
--rollback ALTER TABLE rca_report DROP COLUMN finding_ids;
