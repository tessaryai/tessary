--liquibase formatted sql

--changeset evals:0056-fix-pr
-- After an RCA, the fix step writes a fix for its high-confidence causes and opens one pull request per case, or adds
-- a commit to the case's open one. fix_pr is that pull request as Tessary last read it; causes holds the keys of the
-- causes it addressed so far (type:path), so a rerun adds a commit only for a new cause and a pull request closed
-- without a merge keeps its causes out of later ones. fix_run is one fix step and its own queue: queued, running,
-- then done or failed. A done run names its outcome; skipped and no_fix carry a reason code. One run per report, and
-- at most one running per case, so two runs never write the same branch at once.
CREATE TABLE fix_pr (
    id          text PRIMARY KEY,
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    case_id     text NOT NULL REFERENCES eval_case(id) ON DELETE CASCADE,
    branch      text NOT NULL,
    pr_number   integer NOT NULL,
    pr_url      text NOT NULL,
    state       text NOT NULL CHECK (state IN ('open', 'merged', 'closed')),
    head_sha    text NOT NULL,
    causes      jsonb NOT NULL DEFAULT '[]'::jsonb,
    opened_at   text NOT NULL,
    updated_at  text NOT NULL
);
CREATE UNIQUE INDEX ux_fix_pr_open_case ON fix_pr (case_id) WHERE state = 'open';
CREATE INDEX ix_fix_pr_case ON fix_pr (project_id, case_id, opened_at DESC);

CREATE TABLE fix_run (
    id             text PRIMARY KEY,
    project_id     text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    fix_pr_id      text REFERENCES fix_pr(id) ON DELETE SET NULL,
    case_id        text NOT NULL REFERENCES eval_case(id) ON DELETE CASCADE,
    rca_report_id  text NOT NULL UNIQUE REFERENCES rca_report(id) ON DELETE CASCADE,
    status         text NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed')),
    outcome        text CHECK (outcome IN ('pr_opened', 'commit_added', 'no_change', 'no_fix', 'skipped')),
    reason         text,                -- snake_case code for skipped, no_fix and failed runs; never model text
    note           text,                -- the agent's own sentence: what its change does, or why it made none
    causes         jsonb NOT NULL DEFAULT '[]'::jsonb,  -- keys of the causes this run was given
    checks         jsonb NOT NULL DEFAULT '[]'::jsonb,  -- [{command, result, note}] as the agent reported them
    patch_files    integer NOT NULL DEFAULT 0,
    created_at     text NOT NULL,
    updated_at     text NOT NULL,
    CONSTRAINT ck_fix_run_outcome CHECK ((status = 'done') = (outcome IS NOT NULL))
);
CREATE INDEX ix_fix_run_case ON fix_run (project_id, case_id, created_at DESC);
CREATE INDEX ix_fix_run_queued ON fix_run (created_at) WHERE status = 'queued';
CREATE UNIQUE INDEX ux_fix_run_running_case ON fix_run (case_id) WHERE status = 'running';

ALTER TABLE eval_case_event DROP CONSTRAINT eval_case_event_kind_check;
ALTER TABLE eval_case_event ADD CONSTRAINT eval_case_event_kind_check
    CHECK ((kind = ANY (ARRAY['opened'::text, 'reopened'::text, 'recurred'::text, 'escalated'::text,
        'rca_requested'::text, 'rca_completed'::text, 'recovered'::text, 'resolved'::text, 'muted'::text,
        'unmuted'::text, 'absorbed'::text, 'fix_pr_opened'::text, 'fix_pr_updated'::text])));

--rollback DELETE FROM eval_case_event WHERE kind IN ('fix_pr_opened', 'fix_pr_updated');
--rollback ALTER TABLE eval_case_event DROP CONSTRAINT eval_case_event_kind_check;
--rollback ALTER TABLE eval_case_event ADD CONSTRAINT eval_case_event_kind_check CHECK ((kind = ANY (ARRAY['opened'::text, 'reopened'::text, 'recurred'::text, 'escalated'::text, 'rca_requested'::text, 'rca_completed'::text, 'recovered'::text, 'resolved'::text, 'muted'::text, 'unmuted'::text, 'absorbed'::text])));
--rollback DROP TABLE fix_run;
--rollback DROP TABLE fix_pr;
