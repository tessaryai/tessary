--liquibase formatted sql

--changeset evals:0032-authoring-lane
-- Widens ck_project_model_setting_lane for the authoring lane, the third AGENT_VM lane: the model
-- the coding agent runs when it writes a classifier from a description. Postgres has no ALTER
-- CONSTRAINT for a CHECK's expression, so the old one is dropped and the widened one recreated
-- under the same name, exactly as 0014 did for frustration.
ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane
    CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text, 'authoring'::text]));

--rollback DELETE FROM project_model_setting WHERE lane = 'authoring';
--rollback ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
--rollback ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text]));
