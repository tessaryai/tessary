--liquibase formatted sql

--changeset evals:0024-groundedness-detection
-- The groundedness classifier's detection table, moved into the open tree with the detector on
-- 2026-09-21 when the model (tessaryai/groundedness-token-v1) went public. Until then the table
-- lived in the paid overlay's own changelog and an open install carried no relation for the kind,
-- which is why the catalog's groundedness entry seeded a classifier row that could never write.
-- Same shape as secret_leak_detection / malformed_output_detection after 0012: the columns
-- ClassifierDetectionWriteRepository writes, subject_started_at included, the two CHECKs, the
-- recent, subject and event-time indexes.
--
-- IDEMPOTENT ON PURPOSE. A database that ran the overlay's changelog before this changeset landed
-- already holds this table under exactly these names. Every statement here therefore tolerates
-- its own prior effect, so that install upgrades in place instead of failing at boot with
-- "relation groundedness_detection already exists". Constraints ride inside CREATE TABLE IF NOT
-- EXISTS so they are skipped with it, and subject_started_at is added separately for a table that
-- predates it (the overlay's did not carry it), the way 0012 added it to the other three.
CREATE TABLE IF NOT EXISTS public.groundedness_detection (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    classifier_key text NOT NULL,
    project_version_id text,
    subject_session_id text,
    subject_trace_id text NOT NULL,
    subject_span_id text NOT NULL,
    severity text,
    confidence text,
    evidence jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    subject_started_at timestamptz,
    CONSTRAINT groundedness_detection_pkey PRIMARY KEY (id),
    CONSTRAINT groundedness_detection_confidence_check CHECK (((confidence IS NULL) OR (confidence = ANY (ARRAY['high'::text, 'low'::text])))),
    CONSTRAINT groundedness_detection_severity_check CHECK (((severity IS NULL) OR (severity = ANY (ARRAY['info'::text, 'warn'::text, 'critical'::text])))),
    CONSTRAINT groundedness_detection_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE,
    CONSTRAINT groundedness_detection_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_groundedness_detection_recent ON public.groundedness_detection USING btree (project_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_groundedness_detection_subject ON public.groundedness_detection USING btree (project_id, classifier_id, subject_trace_id, subject_span_id);
ALTER TABLE public.groundedness_detection ADD COLUMN IF NOT EXISTS subject_started_at timestamptz;
CREATE INDEX IF NOT EXISTS ix_groundedness_detection_subject_started
    ON public.groundedness_detection USING btree (project_id, subject_started_at DESC NULLS LAST);
COMMENT ON COLUMN public.groundedness_detection.created_at IS 'When the classifier checked the span (run time). Metering, alert digests and retention read this; nothing event-scoped does.';
COMMENT ON COLUMN public.groundedness_detection.subject_started_at IS 'When the span (or, with no span, the trace) it judged actually ran (event time). The Classifiers page, classifier_events and the arming gate read this; NULL for a row whose span and trace have both since been deleted.';
