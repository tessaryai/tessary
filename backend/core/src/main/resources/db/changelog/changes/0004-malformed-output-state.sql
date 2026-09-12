--liquibase formatted sql

--changeset evals:0004-malformed-output-state
-- The Malformed Output classifier's CUSUM state, one row per call site: the same accumulator
-- tool_error carries per tool, because it is the same detector. A call site's outputs either parse
-- against its declared schema or they do not, which is a Bernoulli rate exactly as a tool's calls
-- either succeed or fail, so this classifier replays hourly tallies through tool_error's engine
-- (ToolErrorTrend over ToolErrorDetector) rather than keeping a second copy of tuned arithmetic.
--
-- Its own table rather than rows in tool_error_state: a shared table would put call sites in the
-- map the tool-error sweep loads as tools, and the two classifiers' human rulings must never be able
-- to touch each other's accumulators. The columns match tool_error_state one for one, key column
-- apart, because ToolErrorStateRepository reads and writes both. The pending_pin_* and reset_*
-- columns carry no writer here yet; they are the absorb and reset a human ruling on a malformed
-- output finding will need, and a table that lacked them would force the shared repository to fork.
CREATE TABLE malformed_output_state (
    project_id text NOT NULL,
    call_site_id text NOT NULL,
    baseline_calls bigint,
    baseline_failures bigint,
    s_up double precision DEFAULT 0 NOT NULL,
    s_down double precision DEFAULT 0 NOT NULL,
    onset_up_at text,
    onset_down_at text,
    calls_since_onset_up bigint DEFAULT 0 NOT NULL,
    calls_since_onset_down bigint DEFAULT 0 NOT NULL,
    watermark_bucket text,
    state_epoch text NOT NULL,
    pending_pin_by text,
    pending_pin_at text,
    reset_at text,
    reset_by text,
    reset_note text,
    updated_at text NOT NULL,
    CONSTRAINT malformed_output_state_pkey PRIMARY KEY (project_id, call_site_id),
    CONSTRAINT malformed_output_state_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT malformed_output_state_baseline_check CHECK (
        (baseline_calls IS NULL AND baseline_failures IS NULL)
        OR (baseline_calls > 0 AND baseline_failures >= 0 AND baseline_failures <= baseline_calls)),
    CONSTRAINT malformed_output_state_floor_check CHECK (s_up >= 0 AND s_down >= 0),
    CONSTRAINT malformed_output_state_run_check CHECK (calls_since_onset_up >= 0 AND calls_since_onset_down >= 0),
    CONSTRAINT malformed_output_state_up_run_check CHECK ((onset_up_at IS NULL) = (calls_since_onset_up = 0)),
    CONSTRAINT malformed_output_state_down_run_check CHECK ((onset_down_at IS NULL) = (calls_since_onset_down = 0)),
    CONSTRAINT malformed_output_state_reset_check CHECK (
        reset_at IS NULL OR (reset_note IS NOT NULL AND length(trim(reset_note)) > 0))
);

--rollback DROP TABLE malformed_output_state;
