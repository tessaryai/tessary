--liquibase formatted sql

--changeset evals:0029-frustration-shadow
-- One row per Frustration assessment re-scored by the deployment's shadow decision model
-- (TESSARY_FRUSTRATION_SHADOW_URL): the second model's answer beside the first's, so the two can
-- be compared on real traffic before one replaces the other. Nothing a customer sees reads this
-- table. It holds no conversation text: the request body is the assessment's own, re-sent, and
-- only the shadow's answer is kept.
--
-- The row hangs off its assessment and goes with it (a project purge cascades through
-- frustration_assessment). `score` is the shadow's probability of unhappy_with_assistant, null when
-- the shadow refused the request (then `response` carries the refusal and the row keeps the turn
-- from being re-sent); `frustrated` applies the classifier's threshold at scoring time, as the
-- assessment's own flag did, so the two flags are comparable; `reference_frustrated` copies the
-- assessment's flag at the time of the shadow call.
CREATE TABLE frustration_shadow (
    id text NOT NULL,
    assessment_id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    trace_id text NOT NULL,
    scorer_version text NOT NULL,
    shadow_model text NOT NULL,
    score numeric(9,8),
    frustrated boolean,
    reference_frustrated boolean NOT NULL,
    response jsonb NOT NULL,
    latency_ms integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT frustration_shadow_pkey PRIMARY KEY (id),
    CONSTRAINT frustration_shadow_assessment_id_fkey
        FOREIGN KEY (assessment_id) REFERENCES frustration_assessment(id) ON DELETE CASCADE,
    CONSTRAINT frustration_shadow_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT ux_frustration_shadow_assessment UNIQUE (assessment_id)
);
CREATE INDEX ix_frustration_shadow_project_created
    ON frustration_shadow USING btree (project_id, created_at);

--rollback DROP TABLE frustration_shadow;
