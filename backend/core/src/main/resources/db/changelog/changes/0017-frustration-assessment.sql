--liquibase formatted sql

--changeset evals:0017-frustration-assessment
-- One row per user turn the Frustration classifier sent to its decision model, flagged or not: the
-- exact request and response bodies, whether the turn was flagged at scoring time, and what the call
-- cost. It is a fact table. The scorer inserts; nothing updates a row except retention, which nulls
-- `request` when the turn's span payload ages out, and nothing deletes one except a project purge.
--
-- The substrate is never edited: ingest is its only writer, and this table joins trace and span by
-- key, as malformed_output_detection joins span.
--
-- conversation_id is COALESCE(trace.thread_id, trace.session_id). scorer_version is a hash of the
-- question, its instruction text and the flag threshold, so a change to any of them writes new rows
-- beside the old ones rather than orphaning them. There are no per-probability columns: the answer
-- lives whole in `response`.
CREATE TABLE frustration_assessment (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    trace_id text NOT NULL,
    span_id text NOT NULL,
    conversation_id text NOT NULL,
    call_site_id text,
    turn_started_at timestamp with time zone NOT NULL,
    frustrated boolean NOT NULL,
    scorer_version text NOT NULL,
    provider text NOT NULL,
    model text NOT NULL,
    request jsonb,
    response jsonb NOT NULL,
    input_tokens integer,
    cost_usd numeric(12,8),
    latency_ms integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT frustration_assessment_pkey PRIMARY KEY (id),
    CONSTRAINT frustration_assessment_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT frustration_assessment_classifier_id_fkey
        FOREIGN KEY (classifier_id) REFERENCES classifier(id) ON DELETE CASCADE,
    CONSTRAINT ux_frustration_assessment_turn UNIQUE (project_id, classifier_id, trace_id, scorer_version)
);
CREATE INDEX ix_frustration_assessment_turn_started
    ON frustration_assessment USING btree (project_id, classifier_id, turn_started_at);
CREATE INDEX ix_frustration_assessment_frustrated
    ON frustration_assessment USING btree (project_id, classifier_id, turn_started_at) WHERE frustrated;
CREATE INDEX ix_frustration_assessment_conversation
    ON frustration_assessment USING btree (project_id, conversation_id);

--rollback DROP TABLE frustration_assessment;
