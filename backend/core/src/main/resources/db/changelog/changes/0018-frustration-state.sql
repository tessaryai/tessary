--liquibase formatted sql

--changeset evals:0018-frustration-state
-- The Frustration classifier's CUSUM state, one row per call site: tool_error's accumulator over a
-- different trial. A conversation is one trial on the call site of its first scored turn, and it
-- fails when it carries an uncleared frustration flag, so the rate of frustrated conversations is
-- a Bernoulli rate replayed through tool_error's engine (ToolErrorTrend over ToolErrorDetector),
-- as malformed_output_state does for schema failures.
--
-- Its own table for the reason 0004 gives: a human ruling on one classifier must never touch
-- another's accumulator. The columns match malformed_output_state one for one, key column apart,
-- because ToolErrorStateRepository reads and writes both. baseline_calls and baseline_failures
-- hold the learned reference in conversations and frustrated conversations. The replay rebuilds
-- the accumulator every pass; what persists is the frozen reference, the human reset (reset_*,
-- which also fences the replay), and the last accumulator for the Tuning view to show.
CREATE TABLE frustration_state (
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
    CONSTRAINT frustration_state_pkey PRIMARY KEY (project_id, call_site_id),
    CONSTRAINT frustration_state_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT frustration_state_baseline_check CHECK (
        (baseline_calls IS NULL AND baseline_failures IS NULL)
        OR (baseline_calls > 0 AND baseline_failures >= 0 AND baseline_failures <= baseline_calls)),
    CONSTRAINT frustration_state_floor_check CHECK (s_up >= 0 AND s_down >= 0),
    CONSTRAINT frustration_state_run_check CHECK (calls_since_onset_up >= 0 AND calls_since_onset_down >= 0),
    CONSTRAINT frustration_state_up_run_check CHECK ((onset_up_at IS NULL) = (calls_since_onset_up = 0)),
    CONSTRAINT frustration_state_down_run_check CHECK ((onset_down_at IS NULL) = (calls_since_onset_down = 0)),
    CONSTRAINT frustration_state_reset_check CHECK (
        reset_at IS NULL OR (reset_note IS NOT NULL AND length(trim(reset_note)) > 0))
);

--rollback DROP TABLE frustration_state;
