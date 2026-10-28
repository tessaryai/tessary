--liquibase formatted sql

--changeset evals:0051-reference-learned-through
-- The rate engine now learns an hour only after it has settled (its end plus the classifier's settle delay on the
-- key's own event clock), so the newest bucket folded into the reference must be stored: learned_through. A null
-- is read as watermark_bucket, exact for rows written before this, which learned every judged hour.
-- learn_before is a learning fence: hours at or after it never join the reference. It is cleared by a ruling
-- that re-learns (resetAndRelearn) and never written by a sweep.
--
-- Frustration and groundedness learned their references before labels settled, so a low reference or a learned
-- rise may stand. Their reference, watermark and accumulator are cleared; reset_* and pending_pin_* are kept.
-- Where the call site has an open finding, re-learning is fenced before its onset, so a real rise is never
-- learned as normal. The clearing is not reversible.
ALTER TABLE tool_error_state ADD COLUMN learned_through text;
ALTER TABLE tool_error_state ADD COLUMN learn_before text;
ALTER TABLE malformed_output_state ADD COLUMN learned_through text;
ALTER TABLE malformed_output_state ADD COLUMN learn_before text;
ALTER TABLE frustration_state ADD COLUMN learned_through text;
ALTER TABLE frustration_state ADD COLUMN learn_before text;
ALTER TABLE groundedness_state ADD COLUMN learned_through text;
ALTER TABLE groundedness_state ADD COLUMN learn_before text;

UPDATE frustration_state s
   SET baseline_calls = NULL, baseline_failures = NULL, watermark_bucket = NULL,
       s_up = 0, s_down = 0, onset_up_at = NULL, onset_down_at = NULL,
       calls_since_onset_up = 0, calls_since_onset_down = 0,
       learn_before = (SELECT f.onset_at FROM finding f
                        WHERE f.project_id = s.project_id AND f.classifier_key = 'frustration'
                          AND f.call_site_id = s.call_site_id AND f.status = 'open'
                        ORDER BY CAST(f.onset_at AS timestamptz) LIMIT 1);
UPDATE groundedness_state s
   SET baseline_calls = NULL, baseline_failures = NULL, watermark_bucket = NULL,
       s_up = 0, s_down = 0, onset_up_at = NULL, onset_down_at = NULL,
       calls_since_onset_up = 0, calls_since_onset_down = 0,
       learn_before = (SELECT f.onset_at FROM finding f
                        WHERE f.project_id = s.project_id AND f.classifier_key = 'groundedness'
                          AND f.call_site_id = s.call_site_id AND f.status = 'open'
                        ORDER BY CAST(f.onset_at AS timestamptz) LIMIT 1);
--rollback ALTER TABLE groundedness_state DROP COLUMN learn_before;
--rollback ALTER TABLE groundedness_state DROP COLUMN learned_through;
--rollback ALTER TABLE frustration_state DROP COLUMN learn_before;
--rollback ALTER TABLE frustration_state DROP COLUMN learned_through;
--rollback ALTER TABLE malformed_output_state DROP COLUMN learn_before;
--rollback ALTER TABLE malformed_output_state DROP COLUMN learned_through;
--rollback ALTER TABLE tool_error_state DROP COLUMN learn_before;
--rollback ALTER TABLE tool_error_state DROP COLUMN learned_through;
