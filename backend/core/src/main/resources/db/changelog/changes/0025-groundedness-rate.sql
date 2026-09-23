--liquibase formatted sql

--changeset evals:0025-groundedness-rate
-- The groundedness classifier's rate test, the way 0017 and 0018 gave Frustration its own. The model
-- is tessaryai/groundedness-classifier-v1 (0024's comment names it by its earlier name,
-- groundedness-token-v1; 0024 is left as it ran so its checksum does not change).
--
-- groundedness_assessment is one row per answer the model scored, flagged or not: the denominator the
-- detection table alone cannot give, since groundedness_detection holds flagged answers only. A trace
-- is one trial on its call site and fails when any of its scored answers is flagged, so the replay
-- groups these rows by trace. Answers the detector skips (a call site whose shape carries no source,
-- a blank answer, an answer that asserts nothing checkable, a turn with no readable evidence) write
-- nothing: they are not trials. call_site_id is '' for an unattributed span, as frustration's replay
-- treats one, and the replay never judges it. scorer_version hashes the model revision, the flag
-- threshold and the input layout, so a change to any of them writes new rows beside the old ones.
-- observation_started_at is the span's own start (event time), the clock the replay buckets on.
-- Insert only, and idempotent on the unique index, so a sweep rewound over the same spans counts
-- each answer once.
--
-- groundedness_state is the CUSUM state per call site. Its columns match frustration_state one for
-- one, because ToolErrorStateRepository reads and writes both.
--
-- groundedness_detection.cleared_at is set when a person resolves a groundedness case as a false
-- alarm, so the flags that case cites stop counting as failures. Text, as frustration_detection's is.
--
-- The case detector CHECK gains 'groundedness' (a call site whose rate of flagged answers rose opens
-- a case under its own name) and the RCA report kind gains 'groundedness_causes'. Both are dropped and
-- recreated under the same name, as 0019 and 0021 do, since Postgres cannot alter a CHECK.
CREATE TABLE groundedness_assessment (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    subject_session_id text,
    subject_trace_id text NOT NULL,
    subject_span_id text NOT NULL,
    call_site_id text NOT NULL,
    unsupported real NOT NULL,
    flagged boolean NOT NULL,
    scorer_version text NOT NULL,
    observation_started_at timestamp with time zone NOT NULL,
    scored_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT groundedness_assessment_pkey PRIMARY KEY (id),
    CONSTRAINT groundedness_assessment_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT groundedness_assessment_classifier_id_fkey
        FOREIGN KEY (classifier_id) REFERENCES classifier(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_groundedness_assessment_subject
    ON groundedness_assessment USING btree
    (project_id, classifier_id, subject_trace_id, subject_span_id, scorer_version);
CREATE INDEX ix_groundedness_assessment_started
    ON groundedness_assessment USING btree (project_id, classifier_id, observation_started_at);
CREATE INDEX ix_groundedness_assessment_call_site
    ON groundedness_assessment USING btree (project_id, classifier_id, call_site_id, observation_started_at);

CREATE TABLE groundedness_state (
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
    CONSTRAINT groundedness_state_pkey PRIMARY KEY (project_id, call_site_id),
    CONSTRAINT groundedness_state_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT groundedness_state_baseline_check CHECK (
        (baseline_calls IS NULL AND baseline_failures IS NULL)
        OR (baseline_calls > 0 AND baseline_failures >= 0 AND baseline_failures <= baseline_calls)),
    CONSTRAINT groundedness_state_floor_check CHECK (s_up >= 0 AND s_down >= 0),
    CONSTRAINT groundedness_state_run_check CHECK (calls_since_onset_up >= 0 AND calls_since_onset_down >= 0),
    CONSTRAINT groundedness_state_up_run_check CHECK ((onset_up_at IS NULL) = (calls_since_onset_up = 0)),
    CONSTRAINT groundedness_state_down_run_check CHECK ((onset_down_at IS NULL) = (calls_since_onset_down = 0)),
    CONSTRAINT groundedness_state_reset_check CHECK (
        reset_at IS NULL OR (reset_note IS NOT NULL AND length(trim(reset_note)) > 0))
);

ALTER TABLE groundedness_detection ADD COLUMN cleared_at text;
COMMENT ON COLUMN groundedness_detection.cleared_at IS 'When a person resolved the case citing this flag as a false alarm. NULL means the flag still counts toward its call site''s rate.';

ALTER TABLE eval_case DROP CONSTRAINT eval_case_detector_check;
ALTER TABLE eval_case ADD CONSTRAINT eval_case_detector_check
    CHECK (detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text,
        'tool_error'::text, 'sop_conformance'::text, 'secret_leak'::text, 'malformed_output'::text,
        'frustration'::text, 'groundedness'::text]));

ALTER TABLE rca_report DROP CONSTRAINT rca_report_report_kind_check;
ALTER TABLE rca_report ADD CONSTRAINT rca_report_report_kind_check
    CHECK (report_kind IN ('metric_movement', 'frustration_causes', 'groundedness_causes'));

--rollback ALTER TABLE rca_report DROP CONSTRAINT rca_report_report_kind_check;
--rollback ALTER TABLE rca_report ADD CONSTRAINT rca_report_report_kind_check CHECK (report_kind IN ('metric_movement', 'frustration_causes'));
--rollback ALTER TABLE eval_case DROP CONSTRAINT eval_case_detector_check;
--rollback ALTER TABLE eval_case ADD CONSTRAINT eval_case_detector_check CHECK (detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text, 'tool_error'::text, 'sop_conformance'::text, 'secret_leak'::text, 'malformed_output'::text, 'frustration'::text]));
--rollback ALTER TABLE groundedness_detection DROP COLUMN cleared_at;
--rollback DROP TABLE groundedness_state;
--rollback DROP TABLE groundedness_assessment;
