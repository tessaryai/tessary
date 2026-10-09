--liquibase formatted sql

--changeset evals:0014-frustration-lane
-- Widens ck_project_model_setting_lane for the frustration lane, the first DECISION_CALLS lane: it
-- stores which gateway (TypeSafe or OpenRouter) carries the Frustration classifier's Jev calls.
-- Postgres has no ALTER CONSTRAINT for a CHECK's expression, so the old one is dropped and the
-- widened one recreated under the same name.
ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane
    CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text]));

--rollback DELETE FROM project_model_setting WHERE lane = 'frustration';
--rollback ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
--rollback ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text]));
