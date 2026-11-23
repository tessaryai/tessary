--liquibase formatted sql

--changeset evals:0055-fix-lane
-- The fix lane: a sandbox agent that writes a fix for an RCA's causes and runs only on a frontier model. It gets its
-- own model setting and its own agent runs, so both lane CHECKs widen to 'fix'. agent_run's CHECK was declared inline
-- in 0049, so it carries Postgres's generated name.
ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane
    CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text, 'agent_run'::text, 'fix'::text]));
ALTER TABLE agent_run DROP CONSTRAINT agent_run_lane_check;
ALTER TABLE agent_run ADD CONSTRAINT agent_run_lane_check CHECK (lane IN ('triage', 'rca', 'fix'));

--rollback DELETE FROM project_model_setting WHERE lane = 'fix';
--rollback ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_lane;
--rollback ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_lane CHECK (lane = ANY (ARRAY['rca'::text, 'triage'::text, 'frustration'::text, 'agent_run'::text]));
--rollback DELETE FROM agent_run WHERE lane = 'fix';
--rollback ALTER TABLE agent_run DROP CONSTRAINT agent_run_lane_check;
--rollback ALTER TABLE agent_run ADD CONSTRAINT agent_run_lane_check CHECK (lane IN ('triage', 'rca'));
