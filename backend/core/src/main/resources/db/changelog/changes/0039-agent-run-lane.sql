--liquibase formatted sql

--changeset evals:0039-agent-run-lane
-- Renames the authoring lane (0033) to agent_run: the lane of any agent an extension runs on the platform,
-- one-shot or as a live session, not of one caller. A saved setting and every ledger row move to the new
-- id, or the setting would read as unset and the spend page would stop naming the lane. Postgres has no
-- ALTER CONSTRAINT for a CHECK's expression, so ck_project_model_setting_lane is dropped, the rows move,
-- and the CHECK is recreated under the same name with agent_run in place of authoring. Recreating it
-- validates every row, so a setting the UPDATE missed halts the deploy here. llm_call.lane has no CHECK;
-- its UPDATE reads the table once and changes only the agent rows.
ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
UPDATE project_model_setting SET lane = 'agent_run' WHERE lane = 'authoring';
ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane
    CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text, 'agent_run'::text]));
UPDATE llm_call SET lane = 'agent_run' WHERE lane = 'authoring';

--rollback ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
--rollback UPDATE project_model_setting SET lane = 'authoring' WHERE lane = 'agent_run';
--rollback ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text, 'authoring'::text]));
--rollback UPDATE llm_call SET lane = 'authoring' WHERE lane = 'agent_run';
