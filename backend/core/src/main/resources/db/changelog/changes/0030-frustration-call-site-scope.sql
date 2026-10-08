--liquibase formatted sql

--changeset evals:0030-frustration-call-site-scope
-- Frustration scores only the call sites a user picks, and a session is a conversation on one call
-- site: one trace can hold a router, a reply and a memory call, each its own call site, and only the
-- reply is the user's conversation.
--
-- frustration_scope holds the picks. It is its own table, not a key in classifier.config_json,
-- because a catalog version bump rewrites config_json from the catalog default.
--
-- Both per-turn unique keys gain the call site, so two picked call sites in one trace each keep their
-- assessment and their flag. frustration_detection has no call-site column: the scorer writes it into
-- evidence, and the keys read it from there. Rows written before this change have no call site in
-- evidence and read as ''.
CREATE TABLE frustration_scope (
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    call_site_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT frustration_scope_pkey PRIMARY KEY (project_id, classifier_id, call_site_id),
    CONSTRAINT frustration_scope_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT frustration_scope_classifier_id_fkey
        FOREIGN KEY (classifier_id) REFERENCES classifier(id) ON DELETE CASCADE
);

ALTER TABLE frustration_assessment DROP CONSTRAINT ux_frustration_assessment_turn;
CREATE UNIQUE INDEX ux_frustration_assessment_turn
    ON frustration_assessment (project_id, classifier_id, trace_id, (COALESCE(call_site_id, '')), scorer_version);
CREATE INDEX ix_frustration_assessment_conversation_call_site
    ON frustration_assessment (project_id, conversation_id, (COALESCE(call_site_id, '')));

DROP INDEX ux_frustration_detection_subject;
CREATE UNIQUE INDEX ux_frustration_detection_subject
    ON frustration_detection (project_id, classifier_id, subject_trace_id, (COALESCE(evidence ->> 'call_site_id', '')));
DROP INDEX ix_frustration_detection_uncleared;
CREATE INDEX ix_frustration_detection_uncleared
    ON frustration_detection
    (project_id, classifier_id, subject_session_id, (COALESCE(evidence ->> 'call_site_id', '')), subject_started_at)
    WHERE cleared_at IS NULL;

COMMENT ON COLUMN frustration_detection.subject_session_id IS 'The conversation key, COALESCE(trace.thread_id, trace.session_id), of the flagged turn. With evidence->>''call_site_id'' it names the session.';
COMMENT ON COLUMN frustration_detection.cleared_at IS 'When a human cleared this session''s flag. NULL means the session still counts as flagged.';

--rollback DROP INDEX ix_frustration_detection_uncleared;
--rollback CREATE INDEX ix_frustration_detection_uncleared ON frustration_detection (project_id, classifier_id, subject_session_id, subject_started_at) WHERE cleared_at IS NULL;
--rollback DROP INDEX ux_frustration_detection_subject;
--rollback CREATE UNIQUE INDEX ux_frustration_detection_subject ON frustration_detection (project_id, classifier_id, subject_trace_id);
--rollback DROP INDEX ix_frustration_assessment_conversation_call_site;
--rollback DROP INDEX ux_frustration_assessment_turn;
--rollback ALTER TABLE frustration_assessment ADD CONSTRAINT ux_frustration_assessment_turn UNIQUE (project_id, classifier_id, trace_id, scorer_version);
--rollback DROP TABLE frustration_scope;
