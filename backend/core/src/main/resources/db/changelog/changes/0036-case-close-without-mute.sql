--liquibase formatted sql

--changeset evals:0036-unmute-cases
-- Mute is gone: a case is open or resolved. Every muted case goes back to open, with a trail line that says why.
-- None of them pages again, because alerts select only cases opened after the last one delivered, and a muted
-- case was already the live case for its key in ux_eval_case_live. The muted and unmuted event kinds stay
-- allowed so the trail keeps its history.
INSERT INTO eval_case_event (id, case_id, project_id, kind, actor, summary, detail, created_at)
SELECT gen_random_uuid()::text, id, project_id, 'unmuted', NULL, 'Mute was removed, so this case is open again.',
       NULL, to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
FROM eval_case
WHERE state = 'muted';

UPDATE eval_case
SET state = 'open', updated_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
WHERE state = 'muted';

ALTER TABLE eval_case DROP CONSTRAINT eval_case_state_check;
ALTER TABLE eval_case ADD CONSTRAINT eval_case_state_check CHECK ((state = ANY (ARRAY['open'::text, 'resolved'::text])));
ALTER TABLE eval_case DROP COLUMN muted_at;
ALTER TABLE eval_case DROP COLUMN muted_by;
--rollback ALTER TABLE eval_case ADD COLUMN muted_by text;
--rollback ALTER TABLE eval_case ADD COLUMN muted_at text;
--rollback ALTER TABLE eval_case DROP CONSTRAINT eval_case_state_check;
--rollback ALTER TABLE eval_case ADD CONSTRAINT eval_case_state_check CHECK ((state = ANY (ARRAY['open'::text, 'resolved'::text, 'muted'::text])));

--changeset evals:0036-close-reason-optional
-- Close asks for no reason, so a human close may carry none.
ALTER TABLE eval_case DROP CONSTRAINT ck_eval_case_resolution_reason;
--rollback ALTER TABLE eval_case ADD CONSTRAINT ck_eval_case_resolution_reason CHECK (((resolution <> 'human'::text) OR (resolution_reason IS NOT NULL))) NOT VALID;
