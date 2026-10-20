--liquibase formatted sql

--changeset evals:0049-agent-run
-- One triage or RCA agent run and its tool calls in plain words, so a page can show what the agent is doing while
-- it works and what it did after. A subject keeps its newest run and the one before it; the store prunes the rest
-- when it opens a run. Raw tool input and output are never stored.
CREATE TABLE agent_run (
    id          text PRIMARY KEY,
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    lane        text NOT NULL CHECK (lane IN ('triage', 'rca')),
    subject_id  text NOT NULL,          -- finding id (triage) or rca_report id (rca)
    job_id      text NOT NULL,          -- no FK: the purge and the paid overlay delete job rows
    attempt     integer NOT NULL,
    session_id  text,
    started_at  text NOT NULL,
    ended_at    text,
    outcome     text CHECK (outcome IN ('done','retrying','held','dead','failed','released','abandoned')),
    stop_code   text,                   -- ErrorCode wire code, e.g. CLASSIFIER.TRIAGE_PROVIDER_REJECTED
    step_count  integer NOT NULL DEFAULT 0
);
CREATE INDEX ix_agent_run_subject ON agent_run (project_id, lane, subject_id, started_at DESC);
CREATE INDEX ix_agent_run_open_job ON agent_run (job_id) WHERE ended_at IS NULL;
CREATE TABLE agent_run_step (
    run_id      text NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    seq         integer NOT NULL,       -- 1-based step number in the run
    call_id     text NOT NULL,
    words       text NOT NULL,          -- done form, at most 200 chars
    doing       text NOT NULL,          -- gerund form, at most 200 chars
    started_ms  bigint NOT NULL,        -- offset from agent_run.started_at
    ended_ms    bigint,
    ok          boolean,
    PRIMARY KEY (run_id, seq)
);
--rollback DROP TABLE agent_run_step; DROP TABLE agent_run;
