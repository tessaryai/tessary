--liquibase formatted sql

--changeset evals:0016-frustration-detection-open
-- Frustration's detection table, in the open changelog. One row per flagged user turn: a turn is a
-- trace, so the row is unique per (project, classifier, subject_trace_id) and subject_span_id is
-- nullable. subject_session_id holds the conversation key, COALESCE(trace.thread_id,
-- trace.session_id). The table holds flagged turns only; every turn sent to the decision model,
-- flagged or not, is a frustration_assessment row (0017).
--
-- IF NOT EXISTS throughout, because an install that ran the paid overlay already has this table, with
-- the same columns up to subject_started_at. Nothing here registers the table for the events feed yet.
--
-- cleared_at is the session flag's clear: a conversation whose detection has cleared_at set no longer
-- counts as flagged, so its later turns are scored again. The partial index is what reads it: the
-- sweep's "conversation already flagged" check and the rate replay both look a conversation up by
-- its key among the uncleared rows.
CREATE TABLE IF NOT EXISTS frustration_detection (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    classifier_key text NOT NULL,
    project_version_id text,
    subject_session_id text,
    subject_trace_id text NOT NULL,
    subject_span_id text,
    severity text,
    confidence text,
    evidence jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    subject_started_at timestamp with time zone,
    CONSTRAINT frustration_detection_pkey PRIMARY KEY (id),
    CONSTRAINT frustration_detection_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT frustration_detection_classifier_id_fkey
        FOREIGN KEY (classifier_id) REFERENCES classifier(id) ON DELETE CASCADE,
    CONSTRAINT frustration_detection_confidence_check
        CHECK (confidence IS NULL OR confidence IN ('high', 'low')),
    CONSTRAINT frustration_detection_severity_check
        CHECK (severity IS NULL OR severity IN ('info', 'warn', 'critical'))
);
ALTER TABLE frustration_detection ADD COLUMN IF NOT EXISTS subject_started_at timestamp with time zone;
ALTER TABLE frustration_detection ADD COLUMN IF NOT EXISTS cleared_at text;

CREATE UNIQUE INDEX IF NOT EXISTS ux_frustration_detection_subject
    ON frustration_detection USING btree (project_id, classifier_id, subject_trace_id);
CREATE INDEX IF NOT EXISTS ix_frustration_detection_subject_started
    ON frustration_detection USING btree (project_id, subject_started_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS ix_frustration_detection_uncleared
    ON frustration_detection USING btree (project_id, classifier_id, subject_session_id, subject_started_at)
    WHERE cleared_at IS NULL;

COMMENT ON COLUMN frustration_detection.subject_session_id IS 'The conversation key, COALESCE(trace.thread_id, trace.session_id), of the flagged turn.';
COMMENT ON COLUMN frustration_detection.cleared_at IS 'When a human cleared this conversation''s flag. NULL means the conversation still counts as flagged.';

--rollback DROP INDEX IF EXISTS ix_frustration_detection_uncleared;
--rollback ALTER TABLE frustration_detection DROP COLUMN IF EXISTS cleared_at;
