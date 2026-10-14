--liquibase formatted sql

--changeset evals:0040-rca-owned-causes-report
-- Admits owned_causes, the report a finding gets when a FindingAdapter owns it: what that classifier flagged and
-- why. The header reads the payload's current and baseline rates, as frustration_causes does.
ALTER TABLE rca_report DROP CONSTRAINT rca_report_report_kind_check;
ALTER TABLE rca_report ADD CONSTRAINT rca_report_report_kind_check
    CHECK (report_kind IN ('metric_movement', 'frustration_causes', 'groundedness_causes', 'owned_causes'));

--rollback ALTER TABLE rca_report DROP CONSTRAINT rca_report_report_kind_check;
--rollback ALTER TABLE rca_report ADD CONSTRAINT rca_report_report_kind_check CHECK (report_kind IN ('metric_movement', 'frustration_causes', 'groundedness_causes'));
