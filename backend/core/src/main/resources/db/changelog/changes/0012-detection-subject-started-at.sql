--liquibase formatted sql

--changeset evals:0012-detection-subject-started-at
-- The three per-span detection tables only ever stored WHEN THE CLASSIFIER CHECKED THE SPAN
-- [decision 8b]: on dev, all 409 secret-leak rows read one day (the day of the most recent sweep)
-- while the spans they judged ran across three months, so the volume chart drew one bar,
-- classifier_events returned one day of history, and a regex classifier's arming gate would have
-- counted months of backfilled matches inside today's window. This adds subject_started_at, the
-- span's own started_at (or the trace's, for a future trace-grain table -- every table registered
-- today is SPAN grain with subject_span_id NOT NULL, so that fallback never fires yet), so a
-- backfill charts, pages and arms on the day its traffic actually ran.
--
-- created_at is untouched and keeps its old job: metering, alert digests and retention all stay on
-- it deliberately, because event time would let a backfill dodge billing, miss every digest window
-- it ran through, or get deleted the moment it's written -- see the comments where each of those
-- reads it.
--
-- Backfilled the same way the writer fills it from here on: the row's own span, falling back to
-- its trace when the span has since been deleted. A row whose span AND trace are both gone is left
-- NULL rather than guessed, and drops out of every event-time read rather than sorting to the top
-- of one.
ALTER TABLE secret_leak_detection ADD COLUMN subject_started_at timestamptz;
ALTER TABLE malformed_output_detection ADD COLUMN subject_started_at timestamptz;
ALTER TABLE user_classifier_detection ADD COLUMN subject_started_at timestamptz;

UPDATE secret_leak_detection d
   SET subject_started_at = s.started_at
  FROM span s
 WHERE s.project_id = d.project_id AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id;
UPDATE secret_leak_detection d
   SET subject_started_at = t.started_at
  FROM trace t
 WHERE d.subject_started_at IS NULL AND t.project_id = d.project_id AND t.id = d.subject_trace_id;

UPDATE malformed_output_detection d
   SET subject_started_at = s.started_at
  FROM span s
 WHERE s.project_id = d.project_id AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id;
UPDATE malformed_output_detection d
   SET subject_started_at = t.started_at
  FROM trace t
 WHERE d.subject_started_at IS NULL AND t.project_id = d.project_id AND t.id = d.subject_trace_id;

UPDATE user_classifier_detection d
   SET subject_started_at = s.started_at
  FROM span s
 WHERE s.project_id = d.project_id AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id;
UPDATE user_classifier_detection d
   SET subject_started_at = t.started_at
  FROM trace t
 WHERE d.subject_started_at IS NULL AND t.project_id = d.project_id AND t.id = d.subject_trace_id;

CREATE INDEX ix_secret_leak_detection_subject_started
    ON public.secret_leak_detection USING btree (project_id, subject_started_at DESC NULLS LAST);
CREATE INDEX ix_malformed_output_detection_subject_started
    ON public.malformed_output_detection USING btree (project_id, subject_started_at DESC NULLS LAST);
CREATE INDEX ix_user_classifier_detection_subject_started
    ON public.user_classifier_detection USING btree (project_id, subject_started_at DESC NULLS LAST);

COMMENT ON COLUMN public.secret_leak_detection.created_at IS 'When the classifier checked the span (run time). Metering, alert digests and retention read this; nothing event-scoped does.';
COMMENT ON COLUMN public.secret_leak_detection.subject_started_at IS 'When the span (or, with no span, the trace) it judged actually ran (event time). The Classifiers page, classifier_events and the arming gate read this; NULL for a row whose span and trace have both since been deleted.';
COMMENT ON COLUMN public.malformed_output_detection.created_at IS 'When the classifier checked the span (run time). Metering, alert digests and retention read this; nothing event-scoped does.';
COMMENT ON COLUMN public.malformed_output_detection.subject_started_at IS 'When the span (or, with no span, the trace) it judged actually ran (event time). The Classifiers page, classifier_events and the arming gate read this; NULL for a row whose span and trace have both since been deleted.';
COMMENT ON COLUMN public.user_classifier_detection.created_at IS 'When the classifier checked the span (run time). Metering, alert digests and retention read this; nothing event-scoped does.';
COMMENT ON COLUMN public.user_classifier_detection.subject_started_at IS 'When the span (or, with no span, the trace) it judged actually ran (event time). The Classifiers page, classifier_events and the arming gate read this; NULL for a row whose span and trace have both since been deleted.';

--rollback DROP INDEX ix_user_classifier_detection_subject_started;
--rollback DROP INDEX ix_malformed_output_detection_subject_started;
--rollback DROP INDEX ix_secret_leak_detection_subject_started;
--rollback ALTER TABLE user_classifier_detection DROP COLUMN subject_started_at;
--rollback ALTER TABLE malformed_output_detection DROP COLUMN subject_started_at;
--rollback ALTER TABLE secret_leak_detection DROP COLUMN subject_started_at;
